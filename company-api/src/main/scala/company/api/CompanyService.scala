package company.api

import java.time.Instant

import akka.Done
import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.broker.kafka.KafkaProperties
import com.lightbend.lagom.scaladsl.api.broker.kafka.PartitionKeyStrategy
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.Service
import com.lightbend.lagom.scaladsl.api.ServiceCall
import play.api.libs.json.Format
import play.api.libs.json.Json
import java.text.Normalizer.Form

object CompanyService {
  val TOPIC_NAME = "company"
}

/**
 * The Company service interface.
 * <p>
 * This describes everything that Lagom needs to know about how to serve and
 * consume the CompanyService.
 */
trait CompanyService extends Service {

  /**
   * Get a company.
   *
   * Example: curl http://localhost:9000/company/123
   */
  def get(id: String): ServiceCall[NotUsed, CompanyView]

  /**
   * Get a company report (view model).
   *
   * Example: curl http://localhost:9000/company/123/report
   */
  def getReport(id: String): ServiceCall[NotUsed, CompanyReport]


  /**
   * Suspend the company.
   *
   * Example: curl -X POST http://localhost:9000/company/123/suspend
   */
  def suspend(id: String): ServiceCall[NotUsed, CompanyView]

  /**
   * Update company configuration .
   *
   * Example: curl -H "Content-Type: application/json" -X POST -d '{"inputLocation": "/tmp/input", "outputLocation": "/tmp/output"}' http://localhost:9000/company/1234/configure
   */
  def configure(id: String): ServiceCall[CompanyConfiguration,CompanyView]

  /**
   * This gets published to Kafka.
   */
  def companyTopic: Topic[CompanyView]

  final override def descriptor = {
    import Service._
    // @formatter:off
    named("company")
      .withCalls(
        restCall(Method.GET, "/company/:id", get _),
        restCall(Method.GET, "/company/:id/report", getReport _),
        restCall(Method.POST, "/company/:id/suspend", suspend _),
        restCall(Method.POST, "/company/:id/configure", configure _)
      )
      .withTopics(
        topic(CompanyService.TOPIC_NAME, companyTopic)
          // Kafka partitions messages, messages within the same partition will
          // be delivered in order, to ensure that all messages for the same user
          // go to the same partition (and hence are delivered in order with respect
          // to that user), we configure a partition key strategy that extracts the
          // name as the partition key.
          .addProperty(
            KafkaProperties.partitionKeyStrategy,
            PartitionKeyStrategy[CompanyView](_.id)
          )
      )
      .withAutoAcl(true)
    // @formatter:on
  }
}


/**
 * A company.
 *
 * @param id The id of the company.
 * @param suspended Whether the company has been suspended.
 */
final case class CompanyView(id: String, inputLocation: String, outputLocation: String, suspended: Boolean)

object CompanyView {
  implicit val format: Format[CompanyView] = Json.format
}

/**
 * A company report exposes information about a Company.
 */
final case class CompanyReport(companyId: String, creationDate: Instant, inputLocation: String, outputLocation: String, suspended: Boolean)

object CompanyReport {
  implicit val format: Format[CompanyReport] = Json.format
}

/**
 * A company data updates the company info
 */
final case class CompanyConfiguration(inputLocation: String, outputLocation: String)

object CompanyConfiguration {
  implicit val format: Format[CompanyConfiguration] = Json.format
}
