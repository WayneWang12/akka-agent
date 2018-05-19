package mesh

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Tcp}
import akka.util.ByteString

import scala.concurrent.Future

class Provider(host: String, port: Int, dubboPort: Int)(implicit actorSystem: ActorSystem, materializer: Materializer) {

  val handleFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] =
    Tcp().outgoingConnection(host, dubboPort).async.buffer(256, OverflowStrategy.backpressure)

  def startService: Future[Done] = {
    Tcp().bind(host, port).runForeach { conn =>
      conn.handleWith(handleFlow)
    }
  }

}
