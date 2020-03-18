package company.impl

import com.lightbend.lagom.scaladsl.akka.discovery.AkkaDiscoveryComponents
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.slick.SlickPersistenceComponents
import com.lightbend.lagom.scaladsl.server._
import com.softwaremill.macwire._
import play.api.db.HikariCPComponents
import play.api.libs.ws.ahc.AhcWSComponents
import akka.cluster.sharding.typed.scaladsl.Entity
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import company.api.CompanyService

import scala.concurrent.ExecutionContext

class CompanyLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new CompanyApplication(context) with AkkaDiscoveryComponents

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new CompanyApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[CompanyService])
}

trait CompanyComponents
    extends LagomServerComponents
    with SlickPersistenceComponents
    with HikariCPComponents
    with AhcWSComponents {

  implicit def executionContext: ExecutionContext

  // Bind the service that this server provides
  override lazy val lagomServer: LagomServer =
    serverFor[CompanyService](wire[CompanyServiceImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry: JsonSerializerRegistry =
    CompanySerializerRegistry

  lazy val reportRepository: CompanyReportRepository =
    wire[CompanyReportRepository]
  readSide.register(wire[CompanyReportProcessor])

  // Initialize the sharding for the ShoppingCart aggregate.
  // See https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html
  clusterSharding.init(
    Entity(Company.typeKey) { entityContext =>
      Company(entityContext)
    }
  )
}

abstract class CompanyApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
  with CompanyComponents
  with LagomKafkaComponents {

}
