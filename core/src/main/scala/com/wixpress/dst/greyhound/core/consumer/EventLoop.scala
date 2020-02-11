package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetric.GreyhoundMetrics
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, Metrics}
import org.apache.kafka.clients.consumer.ConsumerRecords
import zio._
import zio.clock.Clock
import zio.duration._

import scala.collection.JavaConverters._

object EventLoop {
  type Handler[-R] = RecordHandler[R, Nothing, Chunk[Byte], Chunk[Byte]]

  def make[R1, R2](consumer: Consumer[R1],
                   handler: Handler[R2],
                   config: EventLoopConfig = EventLoopConfig.Default): RManaged[R1 with R2 with GreyhoundMetrics with Clock, Resource[R2 with GreyhoundMetrics with Clock]] = {
    val start = for {
      _ <- Metrics.report(StartingEventLoop)
      offsets <- Offsets.make
      handle = handler.andThen(offsets.update).handle(_)
      dispatcher <- Dispatcher.make(handle, config.lowWatermark, config.highWatermark)
      partitionsAssigned <- Promise.make[Nothing, Unit]
      // TODO how to handle errors in subscribe?
      _ <- consumer.subscribe(
        topics = handler.topics,
        onPartitionsAssigned = { _ =>
          partitionsAssigned.succeed(())
        })
      running <- Ref.make(true)
      fiber <- loop(running, consumer, dispatcher, Set.empty, offsets, config).fork
      _ <- partitionsAssigned.await
    } yield (dispatcher, fiber, offsets, running)

    start.toManaged {
      case (dispatcher, fiber, offsets, running) => for {
        _ <- Metrics.report(StoppingEventLoop)
        _ <- running.set(false)
        drained <- (fiber.join *> dispatcher.shutdown).timeout(config.drainTimeout)
        _ <- ZIO.when(drained.isEmpty)(Metrics.report(DrainTimeoutExceeded))
        _ <- commitOffsets(consumer, offsets)
      } yield ()
    }.map {
      case (dispatcher, fiber, _, _) =>
        new Resource[R2 with GreyhoundMetrics with Clock] {
          override def pause: URIO[R2 with GreyhoundMetrics with Clock, Unit] =
            Metrics.report(PausingEventLoop) *> dispatcher.pause

          override def resume: URIO[R2 with GreyhoundMetrics with Clock, Unit] =
            Metrics.report(ResumingEventLoop) *> dispatcher.resume

          override def isAlive: UIO[Boolean] = fiber.poll.map {
            case Some(Exit.Failure(_)) => false
            case _ => true
          }
        }
    }
  }

  private def loop[R1, R2](running: Ref[Boolean],
                           consumer: Consumer[R1],
                           dispatcher: Dispatcher[R2],
                           paused: Set[TopicPartition],
                           offsets: Offsets,
                           config: EventLoopConfig): URIO[R1 with R2 with GreyhoundMetrics, Unit] =
    running.get.flatMap {
      case true => for {
        paused1 <- resumePartitions(consumer, dispatcher, paused)
        paused2 <- pollAndHandle(consumer, dispatcher, paused1, config)
        _ <- commitOffsets(consumer, offsets)
        result <- loop(running, consumer, dispatcher, paused2, offsets, config)
      } yield result

      case false => ZIO.unit
    }

  private def resumePartitions[R1, R2](consumer: Consumer[R1],
                                       dispatcher: Dispatcher[R2],
                                       paused: Set[TopicPartition]) =
    for {
      partitionsToResume <- dispatcher.resumeablePartitions(paused)
      _ <- consumer.resume(partitionsToResume).ignore
    } yield paused diff partitionsToResume

  private val emptyRecords = ZIO.succeed(ConsumerRecords.empty())

  private def pollAndHandle[R1, R2](consumer: Consumer[R1],
                                    dispatcher: Dispatcher[R2],
                                    paused: Set[TopicPartition],
                                    config: EventLoopConfig) =
    consumer.poll(config.pollTimeout).catchAll(_ => emptyRecords).flatMap { records =>
      ZIO.foldLeft(records.asScala)(paused) { (acc, kafkaRecord) =>
        val record = ConsumerRecord(kafkaRecord)
        val partition = TopicPartition(record)
        if (acc contains partition) Metrics.report(PartitionThrottled(partition)).as(acc)
        else dispatcher.submit(record).flatMap {
          case SubmitResult.Submitted => ZIO.succeed(acc)
          case SubmitResult.Rejected =>
            Metrics.report(HighWatermarkReached(partition)) *>
              consumer.pause(record).fold(_ => acc, _ => acc + partition)
        }
      }
    }

  private def commitOffsets[R](consumer: Consumer[R], offsets: Offsets) =
    offsets.committable.flatMap { committable =>
      consumer.commit(committable).catchAll { _ =>
        ZIO.foreach_(committable) {
          case (partition, offset) =>
            offsets.update(partition, offset)
        }
      }
    }

}

case class EventLoopConfig(pollTimeout: Duration,
                           drainTimeout: Duration,
                           lowWatermark: Int,
                           highWatermark: Int)

object EventLoopConfig {
  val Default = EventLoopConfig(
    pollTimeout = 100.millis,
    drainTimeout = 30.seconds,
    lowWatermark = 128,
    highWatermark = 256)
}

sealed trait EventLoopMetric extends GreyhoundMetric
case object StartingEventLoop extends EventLoopMetric
case object PausingEventLoop extends EventLoopMetric
case object ResumingEventLoop extends EventLoopMetric
case object StoppingEventLoop extends EventLoopMetric
case object DrainTimeoutExceeded extends EventLoopMetric
case class HighWatermarkReached(partition: TopicPartition) extends EventLoopMetric
case class PartitionThrottled(partition: TopicPartition) extends EventLoopMetric

sealed trait EventLoopState

object EventLoopState {
  case object Running extends EventLoopState
  case object Paused extends EventLoopState
  case object ShuttingDown extends EventLoopState
}