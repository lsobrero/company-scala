package company.impl

import java.util.UUID

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.typed.PersistenceId
import org.scalatest.wordspec.AnyWordSpecLike


class CompanyEntitySpec extends ScalaTestWithActorTestKit(s"""
                                                                  |akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
                                                                  |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
                                                                  |akka.persistence.snapshot-store.local.dir = "target/snapshot-${UUID.randomUUID().toString}"
                                                                  |""".stripMargin) with AnyWordSpecLike with LogCapturing {

  import Company._
  private def randomId(): String = UUID.randomUUID().toString

  "Company" must {
    "add an item" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))
      company ! Company.AddItem(UUID.randomUUID().toString, 2, probe.ref)

      probe.expectMessageType[Company.Accepted]
    }

    "remove an item" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Then remove the item
      company ! Company.RemoveItem(itemId, probe.ref)
      probe.receiveMessage() match {
        case Company.Accepted(summary) => summary.items.contains(itemId) shouldBe false
        case Company.Rejected(reason)  => fail(s"Message was rejected with reason: $reason")
      }
    }

    "update item quantity" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Update item quantity
      company ! Company.AdjustItemQuantity(itemId, 5, probe.ref)
      probe.receiveMessage() match {
        case Company.Accepted(summary) => summary.items.get(itemId) shouldBe Some(5)
        case Company.Rejected(reason)  => fail(s"Message was rejected with reason: $reason")
      }
    }

    "allow checking out" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item
      company ! Company.AddItem(randomId(), 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Checkout shopping cart
      company ! Company.Checkout(probe.ref)
      probe.receiveMessage() match {
        case Company.Accepted(summary) => summary.checkedOut shouldBe true
        case Company.Rejected(reason)  => fail(s"Message was rejected with reason: $reason")
      }
    }

    "allow getting shopping cart summary" in {
      val probeAdd     = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probeAdd.ref)

      // Get the summary
      // Use another probe since Company.Get does not return a Company.Confirmation
      val probeGet = createTestProbe[Company.Summary]()
      company ! Company.Get(probeGet.ref)
      probeGet.receiveMessage().items.get(itemId) shouldBe Some(2)
    }

    "fail when removing an item that isn't added" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Removing is idempotent, so command will not be Rejected
      val toRemoveItemId = randomId()
      company ! Company.RemoveItem(toRemoveItemId, probe.ref)
      probe.receiveMessage() match {
        case Company.Accepted(summary) => summary.items.get(itemId) shouldBe Some(2)
        case Company.Rejected(reason)  => fail(s"Message was rejected with reason: $reason")
      }
    }

    "fail when adding a negative number of items" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      val quantity = -2
      company ! Company.AddItem(randomId(), quantity, probe.ref)
      probe.expectMessage(Company.Rejected("Quantity must be greater than zero"))
    }

    "fail when adjusting item quantity to negative number" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item so it is possible to checkout
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      val quantity = -2
      company ! Company.AdjustItemQuantity(itemId, quantity, probe.ref)
      probe.expectMessage(Company.Rejected("Quantity must be greater than zero"))
    }

    "fail when adjusting quantity for an item that isn't added" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      val itemId = randomId()
      company ! Company.AdjustItemQuantity(itemId, 2, probe.ref)
      probe.expectMessage(Company.Rejected(s"Cannot adjust quantity for item '$itemId'. Item not present on cart"))
    }

    "fail when adding an item to a checked out cart" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item so it is possible to checkout
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Then checkout the shopping cart
      company ! Company.Checkout(probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Then fail when adding new items
      company ! Company.AddItem(randomId(), 2, probe.ref)
      probe.expectMessage(Company.Rejected("Cannot add an item to a checked-out cart"))
    }

    "fail when checking out twice" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // First add a item so it is possible to checkout
      val itemId = randomId()
      company ! Company.AddItem(itemId, 2, probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Then checkout the shopping cart
      company ! Company.Checkout(probe.ref)
      probe.expectMessageType[Company.Accepted]

      // Then fail to checkout again
      company ! Company.Checkout(probe.ref)
      probe.expectMessage(Company.Rejected("Cannot checkout a checked-out cart"))
    }

    "fail when checking out an empty cart" in {
      val probe        = createTestProbe[Company.Confirmation]()
      val company = spawn(Company(PersistenceId("Company", randomId())))

      // Fail to checkout empty shopping cart
      company ! Company.Checkout(probe.ref)
      probe.expectMessage(Company.Rejected("Cannot checkout an empty shopping cart"))
    }
  }
}
