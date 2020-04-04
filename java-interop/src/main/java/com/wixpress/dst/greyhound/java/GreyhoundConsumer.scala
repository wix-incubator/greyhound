package com.wixpress.dst.greyhound.java

import java.util.concurrent.Executor

import com.wixpress.dst.greyhound.core.consumer.EventLoop.Handler
import com.wixpress.dst.greyhound.core.consumer.{ConsumerRecord => CoreConsumerRecord, RecordHandler => CoreRecordHandler}
import com.wixpress.dst.greyhound.core.{Deserializer => CoreDeserializer}
import com.wixpress.dst.greyhound.future.GreyhoundRuntime.Env
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Deserializer
import zio.ZIO

class GreyhoundConsumer[K >: AnyRef, V](val topic: String,
                                        val group: String,
                                        val handler: RecordHandler[K, V],
                                        val keyDeserializer: Deserializer[K],
                                        val valueDeserializer: Deserializer[V],
                                        val offsetReset: OffsetReset) {

  def recordHandler(executor: Executor): Handler[Env] = {
    val baseHandler = CoreRecordHandler(topic) { record: CoreConsumerRecord[K, V] =>
      ZIO.effectAsync[Any, Throwable, Unit] { cb =>
        val kafkaRecord = new ConsumerRecord(
          record.topic,
          record.partition,
          record.offset,
          record.key.orNull,
          record.value) // TODO headers

        handler
          .handle(kafkaRecord, executor)
          .handle[Unit] { (_, error) =>
            if (error != null) cb(ZIO.fail(error))
            else cb(ZIO.unit)
          }
      }
    }
    baseHandler
      .withDeserializers(CoreDeserializer(keyDeserializer), CoreDeserializer(valueDeserializer))
      .withErrorHandler { case (error, _) => error match {
        // TODO handle errors
        case Left(_) => ZIO.unit
        case Right(_) => ZIO.unit
      }
      }
  }
}
