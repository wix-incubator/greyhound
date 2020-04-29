package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core.consumer.Consumer.Records
import com.wixpress.dst.greyhound.core.consumer.ConsumerMetric._
import com.wixpress.dst.greyhound.core.consumer.ReportingConsumer.OrderedOffsets
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetric.GreyhoundMetrics
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, Metrics}
import com.wixpress.dst.greyhound.core._
import zio.duration.Duration
import zio.{RIO, UIO, ZIO}
import scala.collection.JavaConverters._

case class ReportingConsumer[R](clientId: ClientId, group: Group, internal: Consumer[R])
  extends Consumer[R with GreyhoundMetrics] {

  override def subscribe[R1](topics: Set[Topic], rebalanceListener: RebalanceListener[R1]): RIO[R with GreyhoundMetrics with R1, Unit] =
    for {
      r <- ZIO.environment[R with R1 with GreyhoundMetrics]
      _ <- Metrics.report(SubscribingToTopics(clientId, group, topics))
      _ <- internal.subscribe(
        topics = topics,
        rebalanceListener = new RebalanceListener[Any] {
          override def onPartitionsRevoked(partitions: Set[TopicPartition]): UIO[Any] =
            (Metrics.report(PartitionsRevoked(clientId, group, partitions)) *>
              rebalanceListener.onPartitionsRevoked(partitions)).provide(r)

          override def onPartitionsAssigned(partitions: Set[TopicPartition]): UIO[Any] =
            (Metrics.report(PartitionsAssigned(clientId, group, partitions)) *>
              rebalanceListener.onPartitionsAssigned(partitions)).provide(r)
        }).tapError(error => Metrics.report(SubscribeFailed(clientId, group, error)))
    } yield ()

  override def poll(timeout: Duration): RIO[R with GreyhoundMetrics, Records] =
    for {
      records <- internal.poll(timeout).tapError { error =>
        Metrics.report(PollingFailed(clientId, group, error))
      }
      _ <- Metrics.report(PolledRecords(clientId, group, orderedPolledRecords(records))).as(records)
    } yield records

  private def orderedPolledRecords(records: Records): OrderedOffsets = {
    val recordsPerPartition = records.partitions.asScala.map { tp => (tp, records.records(tp)) }
    val byTopic = recordsPerPartition.groupBy(_._1.topic)
    val byPartition = byTopic.map { case (topic, recordsPerPartition) =>
      (topic, recordsPerPartition.map { case (tp, records) => (tp.partition, records) })
    }

    val onlyOffsets = byPartition.mapValues(_.map { case (partition, records) => (partition, records.asScala.map(_.offset)) }
      .toSeq.sortBy(_._1)
    )
      .toSeq
      .sortBy(_._1)

    onlyOffsets
  }

  override def commit(offsets: Map[TopicPartition, Offset], calledOnRebalance: Boolean): RIO[R with GreyhoundMetrics, Unit] =
    ZIO.when(offsets.nonEmpty) {
      Metrics.report(CommittingOffsets(clientId, group, offsets, calledOnRebalance)) *>
        internal.commit(offsets, calledOnRebalance).tapError { error =>
          Metrics.report(CommitFailed(clientId, group, error, offsets))
        }
    }

  override def pause(partitions: Set[TopicPartition]): ZIO[R with GreyhoundMetrics, IllegalStateException, Unit] =
    ZIO.when(partitions.nonEmpty) {
      Metrics.report(PausingPartitions(clientId, group, partitions)) *>
        internal.pause(partitions).tapError { error =>
          Metrics.report(PausePartitionsFailed(clientId, group, error, partitions))
        }
    }

  override def resume(partitions: Set[TopicPartition]): ZIO[R with GreyhoundMetrics, IllegalStateException, Unit] =
    ZIO.when(partitions.nonEmpty) {
      Metrics.report(ResumingPartitions(clientId, group, partitions)) *>
        internal.resume(partitions).tapError { error =>
          Metrics.report(ResumePartitionsFailed(clientId, group, error, partitions))
        }
    }

  override def seek(partition: TopicPartition, offset: Offset): ZIO[R with GreyhoundMetrics, IllegalStateException, Unit] =
    Metrics.report(SeekingToOffset(clientId, group, partition, offset)) *>
      internal.seek(partition, offset).tapError { error =>
        Metrics.report(SeekToOffsetFailed(clientId, group, error, partition, offset))
      }

}

object ReportingConsumer {
  type OrderedOffsets = Seq[(Topic, Seq[(Partition, Seq[Offset])])]
}

sealed trait ConsumerMetric extends GreyhoundMetric {
  def clientId: ClientId

  def group: Group
}

object ConsumerMetric {

  case class SubscribingToTopics(clientId: ClientId, group: Group, topics: Set[Topic]) extends ConsumerMetric

  case class CommittingOffsets(clientId: ClientId, group: Group, offsets: Map[TopicPartition, Offset], calledOnRebalance: Boolean) extends ConsumerMetric

  case class PausingPartitions(clientId: ClientId, group: Group, partitions: Set[TopicPartition]) extends ConsumerMetric

  case class ResumingPartitions(clientId: ClientId, group: Group, partitions: Set[TopicPartition]) extends ConsumerMetric

  case class SeekingToOffset(clientId: ClientId, group: Group, partition: TopicPartition, offset: Offset) extends ConsumerMetric

  case class PartitionsAssigned(clientId: ClientId, group: Group, partitions: Set[TopicPartition]) extends ConsumerMetric

  case class PartitionsRevoked(clientId: ClientId, group: Group, partitions: Set[TopicPartition]) extends ConsumerMetric

  case class SubscribeFailed(clientId: ClientId, group: Group, error: Throwable) extends ConsumerMetric

  case class PollingFailed(clientId: ClientId, group: Group, error: Throwable) extends ConsumerMetric

  case class CommitFailed(clientId: ClientId, group: Group, error: Throwable, offsets: Map[TopicPartition, Offset]) extends ConsumerMetric

  case class PausePartitionsFailed(clientId: ClientId, group: Group, error: IllegalStateException, partitions: Set[TopicPartition]) extends ConsumerMetric

  case class ResumePartitionsFailed(clientId: ClientId, group: Group, error: IllegalStateException, partitions: Set[TopicPartition]) extends ConsumerMetric

  case class SeekToOffsetFailed(clientId: ClientId, group: Group, error: IllegalStateException, partition: TopicPartition, offset: Offset) extends ConsumerMetric

  case class PolledRecords(clientId: ClientId, group: Group, records: OrderedOffsets) extends ConsumerMetric

}
