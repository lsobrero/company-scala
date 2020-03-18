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
   * Add an item in the company.
   *
   * Example: curl -H "Content-Type: application/json" -X POST -d '{"itemId": 456, "quantity": 2}' http://localhost:9000/company/123
   */
  def addItem(id: String): ServiceCall[CompanyItem, CompanyView]

  /**
   * Remove an item in the company.
   *
   * Example: curl -H "Content-Type: application/json" -X DELETE -d '{"itemId": 456 }' http://localhost:9000/company/123/item/456
   */
  def removeItem(id: String, itemId: String): ServiceCall[NotUsed, CompanyView]

  /**
   * Adjust the quantity of an item in the company.
   *
   * Example: curl -H "Content-Type: application/json" -X PATCH -d '{"quantity": 2}' http://localhost:9000/company/123/item/456
   */
  def adjustItemQuantity(id: String, itemId: String): ServiceCall[Quantity, CompanyView]

  /**
   * Checkout the company.
   *
   * Example: curl -X POST http://localhost:9000/company/123/checkout
   */
  def checkout(id: String): ServiceCall[NotUsed, CompanyView]

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
        restCall(Method.POST, "/company/:id", addItem _),
        restCall(Method.DELETE, "/company/:id/item/:itemId", removeItem _),
        restCall(Method.PATCH, "/company/:id/item/:itemId", adjustItemQuantity _),
        restCall(Method.POST, "/company/:id/checkout", checkout _)
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
 * A company item.
 *
 * @param itemId The ID of the item.
 * @param quantity The quantity of the item.
 */
final case class CompanyItem(itemId: String, quantity: Int)

object CompanyItem {

  /**
   * Format for converting the company item to and from JSON.
   *
   * This will be picked up by a Lagom implicit conversion from Play's JSON format to Lagom's message serializer.
   */
  implicit val format: Format[CompanyItem] = Json.format
}

final case class Quantity(quantity: Int)

object Quantity {
  implicit val format: Format[Quantity] = Json.format
}

/**
 * A company.
 *
 * @param id The id of the company.
 * @param items The items in the company.
 * @param checkedOut Whether the company has been checked out (submitted).
 */
final case class CompanyView(id: String, items: Seq[CompanyItem], checkedOut: Boolean)

object CompanyView {
  implicit val format: Format[CompanyView] = Json.format
}

/**
 * A company report exposes information about a Company.
 */
final case class CompanyReport(companyId: String, creationDate: Instant, checkoutDate: Option[Instant])

object CompanyReport {
  implicit val format: Format[CompanyReport] = Json.format
}
