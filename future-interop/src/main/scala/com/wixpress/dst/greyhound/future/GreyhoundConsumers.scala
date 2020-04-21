package com.wixpress.dst.greyhound.future

import com.wixpress.dst.greyhound.core.{Group, NonEmptySet, Topic}
import com.wixpress.dst.greyhound.core.consumer.EventLoop.Handler
import com.wixpress.dst.greyhound.core.consumer.{OffsetReset, RecordConsumer, RecordConsumerConfig}
import com.wixpress.dst.greyhound.future.GreyhoundRuntime.Env
import zio.{Exit, ZIO, ZManaged}

import scala.concurrent.Future

trait GreyhoundConsumers extends Closeable {
  def pause: Future[Unit]

  def resume: Future[Unit]

  def isAlive: Future[Boolean]
}

case class GreyhoundConsumersBuilder(config: GreyhoundConfig,
                                     handlers: Map[Group, (OffsetReset, NonEmptySet[Topic], Handler[Env])] = Map.empty) {

  def withConsumer[K, V](consumer: GreyhoundConsumer[K, V]): GreyhoundConsumersBuilder = {
    copy(handlers = handlers + (consumer.group -> (consumer.offsetReset, consumer.initialTopics, consumer.recordHandler)))
  }

  def build: Future[GreyhoundConsumers] = config.runtime.unsafeRunToFuture {
    for {
      runtime <- ZIO.runtime[Env]
      makeConsumer = ZManaged.foreach(handlers) { case (group, (offsetReset, initialTopics, handler)) =>
        RecordConsumer.make(RecordConsumerConfig(config.bootstrapServers, group, initialTopics,  offsetReset = offsetReset), handler)
      }
      reservation <- makeConsumer.reserve
      consumers <- reservation.acquire
    } yield new GreyhoundConsumers {
      override def pause: Future[Unit] =
        runtime.unsafeRunToFuture(ZIO.foreach(consumers)(_.pause).unit)

      override def resume: Future[Unit] =
        runtime.unsafeRunToFuture(ZIO.foreach(consumers)(_.resume).unit)

      override def isAlive: Future[Boolean] =
        runtime.unsafeRunToFuture(ZIO.foreach(consumers)(_.isAlive).map(_.forall(_ == true)))

      override def shutdown: Future[Unit] =
        runtime.unsafeRunToFuture(reservation.release(Exit.Success(())).unit)
    }
  }

}
