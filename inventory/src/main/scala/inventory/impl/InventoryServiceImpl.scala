package inventory.impl

import java.util.concurrent.atomic.AtomicInteger

import akka.stream.scaladsl.Flow
import akka.Done
import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.ServiceCall
import company.api.{CompanyService, CompanyView}
import inventory.api.InventoryService

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * Implementation of the inventory service.
 *
 * This just stores the inventory in memory, so it will be lost on restart, and also won't work
 * with more than one replicas, but it's enough to demonstrate things working.
 */
class InventoryServiceImpl(companyService: CompanyService) extends InventoryService {

  private val inventory = TrieMap.empty[String, AtomicInteger]

  private def getInventory(itemId: String) = inventory.getOrElseUpdate(itemId, new AtomicInteger)

  companyService.companyTopic.subscribe.atLeastOnce(Flow[CompanyView].map { company =>
    // Since this is at least once event handling, we really should track by shopping cart, and
    // not update inventory if we've already seen this shopping cart. But this is an in memory
    // inventory tracker anyway, so no need to be that careful.
    println(s"Company ${company.id} changed status ${company.suspended}" )
    Done
  })

  override def get(itemId: String): ServiceCall[NotUsed, Int] = ServiceCall { _ =>
    println(s"Get Item ${itemId}")
    Future.successful(inventory.get(itemId).fold(0)(_.get()))
  }

  override def add(itemId: String): ServiceCall[Int, Done] = ServiceCall { quantity =>
    getInventory(itemId).addAndGet(quantity)
    Future.successful(Done)
  }
}
