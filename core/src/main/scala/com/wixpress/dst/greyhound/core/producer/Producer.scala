package com.wixpress.dst.greyhound.core.producer

import java.util.Properties

import com.wixpress.dst.greyhound.core._
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerConfig => KafkaProducerConfig, ProducerRecord => KafkaProducerRecord, RecordMetadata => KafkaRecordMetadata}
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.duration._

import scala.collection.JavaConverters._

trait Producer {
  def produceAsync(record: ProducerRecord[Chunk[Byte], Chunk[Byte]]): ZIO[Blocking, ProducerError, ZIO[Any, ProducerError, RecordMetadata]]

  def produce(record: ProducerRecord[Chunk[Byte], Chunk[Byte]]): ZIO[Blocking, ProducerError, RecordMetadata] =
    produceAsync(record).flatten

  def produce[K, V](record: ProducerRecord[K, V],
                    keySerializer: Serializer[K],
                    valueSerializer: Serializer[V]): ZIO[Blocking, ProducerError, RecordMetadata] =
    serialized(record, keySerializer, valueSerializer)
      .mapError(SerializationError)
      .flatMap(produce)

  def produceAsync[K, V](record: ProducerRecord[K, V],
                         keySerializer: Serializer[K],
                         valueSerializer: Serializer[V]): ZIO[Blocking, ProducerError, ZIO[Any, ProducerError, RecordMetadata]] =
    serialized(record, keySerializer, valueSerializer)
      .mapError(SerializationError)
      .flatMap(produceAsync)

  private def serialized[V, K](record: ProducerRecord[K, V], keySerializer: Serializer[K], valueSerializer: Serializer[V]) = {
    for {
      keyBytes <- ZIO.foreach(record.key)(keySerializer.serialize(record.topic, _))
      valueBytes <- valueSerializer.serialize(record.topic, record.value)
    } yield ProducerRecord(
      topic = record.topic,
      value = valueBytes,
      key = keyBytes,
      partition = record.partition,
      headers = record.headers)
  }
}

object Producer {
  private val serializer = new ByteArraySerializer

  def make(config: ProducerConfig): RManaged[Blocking, Producer] = {
    val acquire = effectBlocking(new KafkaProducer(config.properties, serializer, serializer))
    ZManaged.make(acquire)(producer => effectBlocking(producer.close()).ignore).map { producer =>
      new Producer {
        override def produce(record: ProducerRecord[Chunk[Byte], Chunk[Byte]]): ZIO[Blocking, ProducerError, RecordMetadata] =
          produceAsync(record).flatten

        private def recordFrom(record: ProducerRecord[Chunk[Byte], Chunk[Byte]]) =
          new KafkaProducerRecord(
            record.topic,
            record.partition.fold[Integer](null)(Integer.valueOf),
            record.key.fold[Array[Byte]](null)(_.toArray),
            record.value.toArray,
            headersFrom(record.headers).asJava)

        private def headersFrom(headers: Headers): Iterable[Header] =
          headers.headers.map {
            case (key, value) =>
              new RecordHeader(key, value.toArray)
          }

        override def produceAsync(record: ProducerRecord[Chunk[Byte], Chunk[Byte]]): ZIO[Blocking, ProducerError, ZIO[Any, ProducerError, RecordMetadata]] =
          for {
            produceCompletePromise <- Promise.make[ProducerError, RecordMetadata]
            runtime <- ZIO.runtime[Any]
            _ <- effectBlocking(producer.send(recordFrom(record), new Callback {
              override def onCompletion(metadata: KafkaRecordMetadata, exception: Exception): Unit =
                runtime.unsafeRun(
                  if (exception != null) produceCompletePromise.complete(ProducerError(exception))
                  else produceCompletePromise.succeed(RecordMetadata(metadata)))
            }))
              .tapError(e => produceCompletePromise.complete(ProducerError(e)))
              .mapError(e => runtime.unsafeRun(ProducerError(e)))
          } yield produceCompletePromise.await
      }
    }
  }
}

case class ProducerConfig(bootstrapServers: String,
                          retryPolicy: ProducerRetryPolicy = ProducerRetryPolicy.Default,
                          extraProperties: Map[String, String] = Map.empty) {
  def withBootstrapServers(servers: String) = copy(bootstrapServers = servers)

  def withRetryPolicy(retryPolicy: ProducerRetryPolicy) = copy(retryPolicy = retryPolicy)

  def withProperties(extraProperties: Map[String, String]) = copy(extraProperties = extraProperties)

  def properties: Properties = {
    val props = new Properties
    props.setProperty(KafkaProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.setProperty(KafkaProducerConfig.RETRIES_CONFIG, retryPolicy.retries.toString)
    props.setProperty(KafkaProducerConfig.RETRY_BACKOFF_MS_CONFIG, retryPolicy.backoff.toMillis.toString)
    props.setProperty(KafkaProducerConfig.ACKS_CONFIG, "all")
    extraProperties.foreach {
      case (key, value) =>
        props.setProperty(key, value)
    }
    props
  }

}

case class ProducerRetryPolicy(retries: Int, backoff: Duration)

object ProducerRetryPolicy {
  val Default = ProducerRetryPolicy(30, 200.millis)
}
