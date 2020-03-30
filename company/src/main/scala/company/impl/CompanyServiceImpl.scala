package company.impl

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.transport.BadRequest
import com.lightbend.lagom.scaladsl.api.transport.NotFound
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.EventStreamElement
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry

import scala.concurrent.ExecutionContext
import akka.cluster.sharding.typed.scaladsl.ClusterSharding

import scala.concurrent.duration._
import akka.util.Timeout
import akka.cluster.sharding.typed.scaladsl.EntityRef
import company.api.{CompanyConfiguration, CompanyReport, CompanyService, CompanyView}
import company.impl.Company._

/**
 * Implementation of the `CompanyService`.
 */
class CompanyServiceImpl(
    clusterSharding: ClusterSharding,
    persistentEntityRegistry: PersistentEntityRegistry,
    reportRepository: CompanyReportRepository
)(implicit ec: ExecutionContext)
    extends CompanyService {

  /**
   * Looks up the shopping cart entity for the given ID.
   */
  private def entityRef(id: String): EntityRef[Command] =
    clusterSharding.entityRefFor(Company.typeKey, id)

  implicit val timeout = Timeout(5.seconds)

  override def get(id: String): ServiceCall[NotUsed, CompanyView] = ServiceCall { _ =>
    entityRef(id)
      .ask(reply => Get(reply))
      .map(cartSummary => convertCompany(id, cartSummary))
  }


  override def suspend(id: String): ServiceCall[NotUsed, CompanyView] = ServiceCall { _ =>
    entityRef(id)
      .ask(replyTo => Suspend(replyTo))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  override def configure(id: String): ServiceCall[CompanyConfiguration, CompanyView] = ServiceCall { configuration =>
    entityRef(id)
      .ask(reply => Configure(configuration.inputLocation, configuration.outputLocation, reply))
      .map { confirmation =>
        confirmationToResult(id, confirmation)
      }
  }

  private def confirmationToResult(id: String, confirmation: Confirmation): CompanyView =
    confirmation match {
      case Accepted(cartSummary) => convertCompany(id, cartSummary)
      case Rejected(reason)      => throw BadRequest(reason)
    }

  override def companyTopic: Topic[CompanyView] = TopicProducer.taggedStreamWithOffset(Event.Tag) {
    (tag, fromOffset) =>
      persistentEntityRegistry
        .eventStream(tag, fromOffset)
        .filter(_.event.isInstanceOf[CompanySuspended])
        .mapAsync(4) {
          case EventStreamElement(id, _, offset) =>
            entityRef(id)
              .ask(reply => Get(reply))
              .map(company => convertCompany(id, company) -> offset)
        }
  }

  private def convertCompany(id: String, companySummary: Summary) = {
    CompanyView(
      id,
      companySummary.inputLocation,
      companySummary.outputLocation,
      companySummary.isSuspended
    )
  }

  override def getReport(companyId: String): ServiceCall[NotUsed, CompanyReport] = ServiceCall { _ =>
    reportRepository.findById(companyId).map {
      case Some(company) => company
      case None       => throw NotFound(s"Couldn't find a shopping cart report for $companyId")
    }
  }
}
