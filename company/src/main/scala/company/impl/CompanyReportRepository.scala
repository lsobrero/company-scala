package company.impl

import java.time.Instant

import akka.Done
import company.api.CompanyReport
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * The report repository manage the storage of CompanyReport which is a API class (view model).
 *
 * It saves data in a ready for consumption format for that specific API model.
 * If the API changes, we must regenerated the stored models.
 */
class CompanyReportRepository(database: Database) {

  class CompanyReportTable(tag: Tag) extends Table[CompanyReport](tag, "company_report") {
    def companyId = column[String]("company_id", O.PrimaryKey)

    def creationDate = column[Instant]("creation_date")

    def inputLocation: Rep[String] = column[String]("input_path")

    def outputLocation: Rep[String] = column[String]("output_path")

    def isSuspended: Rep[Boolean] = column[Boolean]("is_suspended")

    def * = (companyId, creationDate, inputLocation, outputLocation, isSuspended) <> ((CompanyReport.apply _).tupled, CompanyReport.unapply)
  }

  val reportTable = TableQuery[CompanyReportTable]

  def createTable() = reportTable.schema.createIfNotExists

  def findById(id: String): Future[Option[CompanyReport]] =
    database.run(findByIdQuery(id))

  def createReport(companyId: String, inputLocation: String, outputLocation: String, isSuspended: Boolean): DBIO[Done] = {
    findByIdQuery(companyId)
      .flatMap {
        case None => reportTable += CompanyReport(companyId, Instant.now(), inputLocation, outputLocation, isSuspended)
        case _    => DBIO.successful(Done)
      }
      .map(_ => Done)
      .transactionally
  }

  def suspendCompany(companyId: String, isSuspended: Boolean): DBIO[Done] = {
    findByIdQuery(companyId)
      .flatMap {
        case Some(company) => reportTable.insertOrUpdate(company.copy(suspended = isSuspended))
        // if that happens we have a corrupted system
        // cart checkout can only happens for a existing cart
        case None => throw new RuntimeException(s"Didn't find company for Suspend. CompanyID: $companyId")
      }
      .map(_ => Done)
      .transactionally
  }

  def configureCompany(companyId: String, input: String, output: String): DBIO[Done] = {
    findByIdQuery(companyId)
      .flatMap {
        case Some(company) => reportTable.insertOrUpdate(company.copy(inputLocation = input, outputLocation = output))
        // if that happens we have a corrupted system
        // cart checkout can only happens for a existing cart
        case None => throw new RuntimeException(s"Didn't find company for Suspend. CompanyID: $companyId")
      }
      .map(_ => Done)
      .transactionally
  }

  private def findByIdQuery(companyId: String): DBIO[Option[CompanyReport]] =
    reportTable
      .filter(_.companyId === companyId)
      .result
      .headOption
}
