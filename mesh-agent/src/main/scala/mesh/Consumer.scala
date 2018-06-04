package mesh

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.stream.Materializer

class Consumer(host:String, etcdManager: ActorRef)(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("127.0.0.1", 20000))

  val requestHandler: ActorRef = context.actorOf(Props(new RequestHandler).withDispatcher("mpsc"), "request-handler")

  def receive: Receive = {
    case Bound(localAddress) ⇒
      etcdManager ! "consumer"
      log.info(s"service started at ${localAddress.getHostString}:${localAddress.getPort}")
    case CommandFailed(_: Bind) ⇒
      context stop self
    case Connected(_, _) ⇒
      val connection = sender()
      connection ! Register(requestHandler)
  }
}

