package mesh

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Tcp

import scala.concurrent.Future

class Provider(host: String, port: Int, dubboPort: Int)(implicit actorSystem: ActorSystem, materializer: Materializer) {

  val handleFlow = Tcp().outgoingConnection(host, dubboPort)

  def startService: Future[Done] = {
    Tcp().bind(host, port).runForeach { conn =>
      conn.handleWith(handleFlow)
    }
  }

}
