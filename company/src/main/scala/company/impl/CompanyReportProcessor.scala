package company.impl

import com.lightbend.lagom.scaladsl.persistence.AggregateEventTag
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.slick.SlickReadSide
import slick.dbio.DBIOAction
import akka.Done
import company.impl.Company.{CartCheckedOut, Event, ItemAdded, ItemQuantityAdjusted, ItemRemoved}

class CompanyReportProcessor(readSide: SlickReadSide, repository: CompanyReportRepository)
    extends ReadSideProcessor[Event] {

  override def buildHandler(): ReadSideProcessor.ReadSideHandler[Event] =
    readSide
      .builder[Event]("company-report")
      .setGlobalPrepare(repository.createTable())
      .setEventHandler[ItemAdded] { envelope =>
        repository.createReport(envelope.entityId)
      }
      .setEventHandler[ItemRemoved] { envelope =>
        DBIOAction.successful(Done) // not used in report
      }
      .setEventHandler[ItemQuantityAdjusted] { envelope =>
        DBIOAction.successful(Done) // not used in report
      }
      .setEventHandler[CartCheckedOut] { envelope =>
        repository.addCheckoutTime(envelope.entityId, envelope.event.eventTime)
      }
      .build()

  override def aggregateTags: Set[AggregateEventTag[Event]] = Event.Tag.allTags
}
