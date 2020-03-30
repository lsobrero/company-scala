package company.impl

import com.lightbend.lagom.scaladsl.persistence.AggregateEventTag
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.slick.SlickReadSide
import slick.dbio.DBIOAction
import akka.Done
import company.impl.Company.{CompanyConfiguration, CompanySuspended, Event}

class CompanyReportProcessor(readSide: SlickReadSide, repository: CompanyReportRepository)
    extends ReadSideProcessor[Event] {

  override def buildHandler(): ReadSideProcessor.ReadSideHandler[Event] =
    readSide
      .builder[Event]("company-report")
      .setGlobalPrepare(repository.createTable())
      .setEventHandler[CompanySuspended] { envelope =>
        repository.createReport(envelope.entityId, "", "", true)
//        repository.suspendCompany(envelope.entityId, envelope.event.isSuspended)
      }
      .setEventHandler[CompanyConfiguration] { configuration =>
        repository.configureCompany(configuration.entityId, configuration.event.inputLocation, configuration.event.outputLocation)
      }
      .build()

  override def aggregateTags: Set[AggregateEventTag[Event]] = Event.Tag.allTags
}
