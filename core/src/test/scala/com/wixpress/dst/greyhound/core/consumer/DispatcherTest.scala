package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core.Headers
import com.wixpress.dst.greyhound.core.consumer.Dispatcher.Record
import com.wixpress.dst.greyhound.core.consumer.DispatcherMetric.RecordHandled
import com.wixpress.dst.greyhound.core.consumer.DispatcherTest._
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetric
import com.wixpress.dst.greyhound.core.testkit.{BaseTest, CountDownLatch, TestMetrics}
import zio.clock.Clock
import zio.duration._
import zio.test.environment.TestClock
import zio.{test, _}

class DispatcherTest extends BaseTest[Clock with TestClock with TestMetrics] {

  override def env: UManaged[Clock with TestClock with TestMetrics] =
    for {
      env <- test.environment.testEnvironment.build
      testMetrics <- TestMetrics.make
    } yield env ++ testMetrics

  "handle submitted records" in {
    for {
      promise <- Promise.make[Nothing, Record]
      dispatcher <- Dispatcher.make("group", "clientId", promise.succeed, lowWatermark, highWatermark)
      _ <- dispatcher.submit(record)
      handled <- promise.await
    } yield handled must equalTo(record)
  }

  "parallelize handling based on partition" in {
    val partitions = 8

    for {
      latch <- CountDownLatch.make(partitions)
      slowHandler = { _: ConsumerRecord[Chunk[Byte], Chunk[Byte]] =>
        clock.sleep(1.second) *> latch.countDown
      }
      dispatcher <- Dispatcher.make("group", "clientId", slowHandler, lowWatermark, highWatermark)
      _ <- ZIO.foreach(0 until partitions) { partition =>
        dispatcher.submit(record.copy(partition = partition))
      }
      _ <- TestClock.adjust(1.second)
      _ <- latch.await
    } yield ok // If execution is not parallel, the latch will not be released
  }

  "reject records when high watermark is reached" in {
    for {
      dispatcher <- Dispatcher.make("group", "clientId", _ => ZIO.never, lowWatermark, highWatermark)
      _ <- dispatcher.submit(record.copy(offset = 0L)) // Will be polled
      _ <- dispatcher.submit(record.copy(offset = 1L))
      _ <- dispatcher.submit(record.copy(offset = 2L))
      _ <- dispatcher.submit(record.copy(offset = 3L))
      _ <- dispatcher.submit(record.copy(offset = 4L))
      result <- dispatcher.submit(record.copy(offset = 5L)) // Will be dropped
    } yield result must equalTo(SubmitResult.Rejected)
  }

  "resume paused partitions" in {
    for {
      queue <- Queue.bounded[Record](1)
      dispatcher <- Dispatcher.make("group", "clientId", queue.offer, lowWatermark, highWatermark)
      _ <- ZIO.foreach(0 to (highWatermark + 1)) { offset =>
        dispatcher.submit(ConsumerRecord[Chunk[Byte], Chunk[Byte]](topic, partition, offset, Headers.Empty, None, Chunk.empty, 0L, 0L, 0L))
      }
      _ <- dispatcher.submit(ConsumerRecord[Chunk[Byte], Chunk[Byte]](topic, partition, 6L, Headers.Empty, None, Chunk.empty, 0L, 0L, 0L)) // Will be dropped
      partitionsToResume1 <- dispatcher.resumeablePartitions(Set(topicPartition))
      _ <- queue.take *> queue.take
      _ <- UIO(Thread.sleep(1))
      partitionsToResume2 <- dispatcher.resumeablePartitions(Set(topicPartition))
    } yield (partitionsToResume1 must beEmpty) and
      (partitionsToResume2 must equalTo(Set(TopicPartition(topic, partition))))
  }

  "pause handling" in {
    for {
      ref <- Ref.make(0)
      dispatcher <- Dispatcher.make("group", "clientId", _ => ref.update(_ + 1), lowWatermark, highWatermark)
      _ <- dispatcher.pause
      _ <- dispatcher.submit(record) // Will be queued
      invocations <- ref.get
    } yield invocations must equalTo(0)
  }

  "complete executing task before pausing" in {
    for {
      ref <- Ref.make(0)
      promise <- Promise.make[Nothing, Unit]
      handler = { _: Record =>
        clock.sleep(1.second) *>
          ref.update(_ + 1) *>
          promise.succeed(())
      }
      dispatcher <- Dispatcher.make("group", "clientId", handler, lowWatermark, highWatermark)
      _ <- dispatcher.submit(record) // Will be handled
      _ <- TestMetrics.reported.flatMap(waitUntilRecordHandled(3.seconds))
      _ <- dispatcher.pause
      _ <- dispatcher.submit(record) // Will be queued
      _ <- TestClock.adjust(1.second)
      _ <- promise.await
      invocations <- ref.get
    } yield invocations must equalTo(1)
  }

  private def waitUntilRecordHandled(timeout: Duration)(metrics: Seq[GreyhoundMetric]) =
    ZIO.when(metrics.collect { case r: RecordHandled[_, _] => r }.nonEmpty)(ZIO.fail(TimeoutWaitingForHandledMetric))
      .retry(Schedule.duration(timeout))

  "resume handling" in {
    for {
      ref <- Ref.make(0)
      promise <- Promise.make[Nothing, Unit]
      handler = { _: ConsumerRecord[Chunk[Byte], Chunk[Byte]] =>
        ref.update(_ + 1) *> promise.succeed(())
      }
      dispatcher <- Dispatcher.make("group", "clientId", handler, lowWatermark, highWatermark)
      _ <- dispatcher.pause
      _ <- dispatcher.submit(record)
      _ <- dispatcher.resume
      _ <- promise.await
      invocations <- ref.get
    } yield invocations must equalTo(1)
  }

}

object DispatcherTest {
  val lowWatermark = 2
  val highWatermark = 4

  val topic = "topic"
  val partition = 0
  val topicPartition = TopicPartition(topic, partition)
  val record = ConsumerRecord[Chunk[Byte], Chunk[Byte]](topic, partition, 0L, Headers.Empty, None, Chunk.empty, 0L, 0L, 0L)
}

case object TimeoutWaitingForHandledMetric extends GreyhoundMetric
