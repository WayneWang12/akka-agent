package mesh

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Tcp}
import akka.util.ByteString

import scala.concurrent.Future

class Provider(host: String, port: Int, dubboPort: Int) {
  implicit val actorSystem = ActorSystem(s"provider-mesh-$port")
  implicit val materializer = ActorMaterializer()

  val handleFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] = Tcp().outgoingConnection(host, dubboPort)

  def startService: Future[Done] = {
    Tcp().bind(host, port).runForeach { conn =>
      conn.handleWith(handleFlow)
    }
  }

}
