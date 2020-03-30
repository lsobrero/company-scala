package company.impl

import java.time.Instant

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl._
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.Effect.reply
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplyEffect
import com.lightbend.lagom.scaladsl.persistence.AggregateEvent
import com.lightbend.lagom.scaladsl.persistence.AggregateEventShards
import com.lightbend.lagom.scaladsl.persistence.AggregateEventTag
import com.lightbend.lagom.scaladsl.persistence.AggregateEventTagger
import com.lightbend.lagom.scaladsl.persistence.AkkaTaggerAdapter
import com.lightbend.lagom.scaladsl.playjson.JsonSerializer
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import play.api.libs.json.Format
import play.api.libs.json._

import scala.collection.immutable.Seq

object Company {

  // COMPANY COMMANDS

  // This is a marker trait for company commands.
  // We will serialize them using Akka's Jackson support that is able to deal with the replyTo field.
  // (see application.conf).
  // Keep in mind that when configuring it on application.conf you need to use the FQCN which is:
  // impl.Company$CommandSerializable
  // Note the "$".
  trait CommandSerializable

  sealed trait Command extends CommandSerializable

  final case class Suspend(replyTo: ActorRef[Confirmation]) extends Command

  final case class Get(replyTo: ActorRef[Summary]) extends Command

  final case class Configure(inputLocation: String, outputLocation: String, replyTo: ActorRef[Confirmation]) extends Command

  // COMAPANY REPLIES
  final case class Summary(inputLocation: String, outputLocation: String, isSuspended: Boolean)

  sealed trait Confirmation

  final case class Accepted(summary: Summary) extends Confirmation

  final case class Rejected(reason: String) extends Confirmation

  implicit val summaryFormat: Format[Summary]               = Json.format
  implicit val confirmationAcceptedFormat: Format[Accepted] = Json.format
  implicit val confirmationRejectedFormat: Format[Rejected] = Json.format
  implicit val confirmationFormat: Format[Confirmation] = new Format[Confirmation] {
    override def reads(json: JsValue): JsResult[Confirmation] = {
      if ((json \ "reason").isDefined)
        Json.fromJson[Rejected](json)
      else
        Json.fromJson[Accepted](json)
    }

    override def writes(o: Confirmation): JsValue = {
      o match {
        case acc: Accepted => Json.toJson(acc)
        case rej: Rejected => Json.toJson(rej)
      }
    }
  }

  // COMPANY EVENTS
  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }

  object Event {
    val Tag: AggregateEventShards[Event] = AggregateEventTag.sharded[Event](numShards = 10)
  }

  final case class CompanySuspended(isSuspended: Boolean) extends Event
  final case class CompanyConfiguration(inputLocation: String, outputLocation: String) extends Event

  // Events get stored and loaded from the database, hence a JSON format
  //  needs to be declared so that they can be serialized and deserialized.
  implicit val companySuspended: Format[CompanySuspended]             = Json.format
  implicit val companyConfiguration: Format[CompanyConfiguration]     = Json.format

  val empty: Company = Company("undefined", "undefined", false)

  val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Company")

  // We can then access the entity behavior in our test tests, without the need to tag
  // or retain events.
  def apply(persistenceId: PersistenceId): EventSourcedBehavior[Command, Event, Company] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, Company](
        persistenceId = persistenceId,
        emptyState = Company.empty,
        commandHandler = (company, cmd) => company.applyCommand(cmd),
        eventHandler = (company, evt) => company.applyEvent(evt)
      )
  }

  def apply(entityContext: EntityContext[Command]): Behavior[Command] =
    apply(PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))

  /**
   * The aggregate get snapshoted every configured number of events. This
   * means the state gets stored to the database, so that when the entity gets
   * loaded, you don't need to replay all the events, just the ones since the
   * snapshot. Hence, a JSON format needs to be declared so that it can be
   * serialized and deserialized when storing to and from the database.
   */
  implicit val companyFormat: Format[Company] = Json.format
}

final case class Company(inputLocation:String, outputLocation: String, isSuspended: Boolean) {

  import Company._

//  def isOpen: Boolean = checkedOutTime.isEmpty
//  def checkedOut: Boolean = !isOpen

  //The company behavior changes if it's suspended or not. The command handles are different for each case.
  def applyCommand(cmd: Command): ReplyEffect[Event, Company] =
    if (isSuspended) {
      cmd match {
        case Suspend(replyTo)                             => onResume(replyTo)
        case Get(replyTo)                                 => onGet(replyTo)
        case Configure(inputLocation, outputLocation, replyTo)   => onConfigure(inputLocation, outputLocation, replyTo)
      }
    } else {
      cmd match {
        case Suspend(replyTo)                             => onSuspend(replyTo)
        case Get(replyTo)                                 => onGet(replyTo)
        case Configure(inputLocation, outputLocation, replyTo)   => onConfigure(inputLocation, outputLocation, replyTo)
      }
    }


  private def onConfigure(inputLocation: String, outputLocation: String, replyTo:ActorRef[Confirmation]): ReplyEffect[Event, Company] = {
  Effect
      .persist(CompanyConfiguration(inputLocation, outputLocation))
      .thenReply(replyTo)(configuredCompany => Accepted(toSummary(configuredCompany)))
  }


  private def onSuspend(replyTo: ActorRef[Confirmation]): ReplyEffect[Event, Company] = {
    Effect
        .persist(CompanySuspended(true))
        .thenReply(replyTo)(updatedCompany => Accepted(toSummary(updatedCompany)))
  }

  private def onResume(replyTo: ActorRef[Confirmation]): ReplyEffect[Event, Company] = {
    Effect
      .persist(CompanySuspended(false))
      .thenReply(replyTo)(updatedCompany => Accepted(toSummary(updatedCompany)))
  }

  private def onGet(replyTo: ActorRef[Summary]): ReplyEffect[Event, Company] = {
    reply(replyTo)(toSummary(this))
  }

  private def toSummary(company: Company): Summary =
    Summary(company.inputLocation, company.outputLocation, company.isSuspended)

  // we don't make a distinction of checked or open for the event handler
  // because a checked-out cart will never persist any new event
  def applyEvent(evt: Event): Company =
    evt match {
      case CompanySuspended(checkedOutTime)         => onSuspend(checkedOutTime)
      case CompanyConfiguration(input, output)      => onConfiguration(input, output)
    }

  private def onSuspend(suspend: Boolean): Company = {
    copy(isSuspended = suspend)
  }

  private def onConfiguration(input: String, output: String): Company = {
    copy(inputLocation = input, outputLocation = output)
  }
}

/**
 * Akka serialization, used by both persistence and remoting, needs to have
 * serializers registered for every type serialized or deserialized. While it's
 * possible to use any serializer you want for Akka messages, out of the box
 * Lagom provides support for JSON, via this registry abstraction.
 *
 * The serializers are registered here, and then provided to Lagom in the
 * application loader.
 */
object CompanySerializerRegistry extends JsonSerializerRegistry {

  import Company._

  override def serializers: Seq[JsonSerializer[_]] = Seq(
    // state and events can use play-json, but commands should use jackson because of ActorRef[T] (see application.conf)
    JsonSerializer[Company],
    JsonSerializer[CompanySuspended],
    JsonSerializer[CompanyConfiguration],

    // the replies use play-json as well
    JsonSerializer[Summary],
    JsonSerializer[Confirmation],
    JsonSerializer[Accepted],
    JsonSerializer[Rejected],
  )
}
