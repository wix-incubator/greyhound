package com.wixpress.dst.greyhound.core.admin

import java.util.Properties
import java.util.concurrent.TimeUnit.SECONDS

import com.wixpress.dst.greyhound.core.admin.AdminClient.isTopicExistsError
import com.wixpress.dst.greyhound.core.zioutils.KafkaFutures._
import com.wixpress.dst.greyhound.core.{Topic, TopicConfig}
import org.apache.kafka.clients.admin.{NewTopic, AdminClient => KafkaAdminClient, AdminClientConfig => KafkaAdminClientConfig}
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.ConfigResource.Type.TOPIC
import org.apache.kafka.common.errors.TopicExistsException
import zio.blocking.{Blocking, effectBlocking}
import zio.{RIO, RManaged, ZIO, ZManaged}

import scala.collection.JavaConverters._

trait AdminClient {
  def listTopics(): RIO[Blocking, Set[String]]

  def createTopics(configs: Set[TopicConfig], ignoreErrors: Throwable => Boolean = isTopicExistsError): RIO[Blocking, Map[String, Option[Throwable]]]

  def numberOfBrokers: RIO[Blocking, Int]

  def propertiesFor(topics: Set[Topic]): RIO[Blocking, Map[Topic, TopicPropertiesResult]]
}

case class TopicPropertiesResult(partitions: Int, properties: Map[String, String])

object TopicPropertiesResult {
  def empty = TopicPropertiesResult(0, Map.empty)
}

object AdminClient {
  def make(config: AdminClientConfig): RManaged[Blocking, AdminClient] = {
    val acquire = effectBlocking(KafkaAdminClient.create(config.properties))
    ZManaged.make(acquire)(client => effectBlocking(client.close()).ignore).map { client =>
      new AdminClient {
        override def createTopics(configs: Set[TopicConfig], ignoreErrors: Throwable => Boolean = isTopicExistsError): RIO[Blocking, Map[String, Option[Throwable]]] =
          effectBlocking(client.createTopics(configs.map(toNewTopic).asJava)).flatMap { result =>
            ZIO.foreach(result.values.asScala) {
              case (topic, topicResult) =>
                topicResult.asZio.either.map(topic -> _.left.toOption.filterNot(ignoreErrors))
            }.map(_.toMap)
          }

        override def numberOfBrokers: RIO[Blocking, Int] =
          effectBlocking(client.describeCluster()).flatMap(result =>
            effectBlocking {
              result.nodes().get(30, SECONDS)
            }.map(x => x.asScala.toSeq.size))


        override def propertiesFor(topics: Set[Topic]): RIO[Blocking, Map[Topic, TopicPropertiesResult]] =
          (describeConfigs(client, topics) zipPar describePartitions(client, topics)).map {
            case (propertiesMap, partitionsMap) =>
              partitionsMap.map { case (topic, partitions) =>
                (topic, TopicPropertiesResult(partitions, propertiesMap.getOrElse(topic, Map.empty))) }
          }


        override def listTopics(): RIO[Blocking, Set[String]] = for {
          result <- effectBlocking(client.listTopics())
          topics <- result.names().asZio
        } yield topics.asScala.toSet

        private def toNewTopic(config: TopicConfig): NewTopic =
          new NewTopic(config.name, config.partitions, config.replicationFactor.toShort)
            .configs(config.propertiesMap.asJava)

      }
    }
  }

  private def describeConfigs(client: KafkaAdminClient, topics: Set[Topic]): RIO[Blocking, Map[Topic, Map[String, String]]] =
    effectBlocking(client.describeConfigs(topics.map(t => new ConfigResource(TOPIC, t)).asJavaCollection)).map(result =>
      result.all().get(30, SECONDS).asScala.map { case (resource, config) =>
        (resource.name, config.entries().asScala.map(entry => (entry.name -> entry.value)).toMap)
      }.toMap)

  private def describePartitions(client: KafkaAdminClient, topics: Set[Topic]): RIO[Blocking, Map[Topic, Int]] =
    effectBlocking(client.describeTopics(topics.asJavaCollection)).map(result =>
      result.all().get(30, SECONDS).asScala.mapValues(_.partitions().size()).toMap)

  def isTopicExistsError(e: Throwable): Boolean = e.isInstanceOf[TopicExistsException] ||
    Option(e.getCause).exists(_.isInstanceOf[TopicExistsException])
}

case class AdminClientConfig(bootstrapServers: String,
                             extraProperties: Map[String, String] = Map.empty) {

  def properties: Properties = {
    val props = new Properties
    props.setProperty(KafkaAdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    extraProperties.foreach {
      case (key, value) =>
        props.setProperty(key, value)
    }
    props
  }

}
