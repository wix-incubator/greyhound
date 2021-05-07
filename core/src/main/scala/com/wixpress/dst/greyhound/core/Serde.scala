package com.wixpress.dst.greyhound.core

import java.time.Instant
import org.apache.kafka.common.serialization.Serdes.{IntegerSerde, LongSerde, StringSerde, VoidSerde}
import org.apache.kafka.common.serialization.{Serde => KafkaSerde}
import zio.duration.Duration
import zio.{Chunk, Task}

trait Serde[A] extends Serializer[A] with Deserializer[A] {
  def inmap[B](f: A => B)(g: B => A): Serde[B] =
    Serde(contramap(g), map(f))
}

object Serde {
  def apply[A](serde: KafkaSerde[A]): Serde[A] =
    apply(Serializer(serde.serializer()), Deserializer(serde.deserializer()))

  def apply[A](serializer: Serializer[A], deserializer: Deserializer[A]): Serde[A] = new Serde[A] {
    override def deserialize(topic: Topic, headers: Headers, data: Chunk[Byte]): Task[A] =
      deserializer.deserialize(topic, headers, data)

    override def serialize(topic: String, value: A): Task[Chunk[Byte]] =
      serializer.serialize(topic, value)
  }
}

object Serdes {
  val StringSerde = Serde(new StringSerde)
  val IntSerde = Serde(new IntegerSerde).inmap(_.toInt)(Integer.valueOf)
  val LongSerde = Serde(new LongSerde).inmap(_.toLong)(java.lang.Long.valueOf)
  val InstantSerde = LongSerde.inmap(Instant.ofEpochMilli)(_.toEpochMilli)
  val DurationSerde = LongSerde.inmap(Duration.fromNanos)(_.toNanos)
  val UnitSerde = Serde(new VoidSerde).inmap(_ => Unit)(_ => null)
}
