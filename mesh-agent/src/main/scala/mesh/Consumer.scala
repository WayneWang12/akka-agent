package mesh

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.stream.Materializer
import akka.util.ByteString

class Consumer(host:String)(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case t:Throwable =>
      log.error(t, "error happened")
      SupervisorStrategy.restart
  }

  IO(Tcp) ! Bind(self, new InetSocketAddress("127.0.0.1", 20000))

  val connectionId = new AtomicLong()
  val requestHandler = context.actorOf(Props(new RequestHandler), "request-handler")

  def receive: Receive = {
    case b@Bound(localAddress) ⇒
      log.info(s"service started at ${localAddress.getHostString}:${localAddress.getPort}")
    case CommandFailed(_: Bind) ⇒
      context stop self
    case c@Connected(remote, local) ⇒
      val cid = connectionId.getAndIncrement()
      val connection = sender()
      val handler = context.actorOf(Props(classOf[ConnectionHandler], cid, connection, requestHandler), cid.toString)
      connection ! Register(handler)
  }

}

class ConnectionHandler(connectionId: Long,
                        connection: ActorRef,
                        requestHandler: ActorRef) extends Actor {

  override def receive: Receive = {
    case Received(data) ⇒
      requestHandler ! (connectionId, data)
    case data: ByteString => connection ! Write(data)
    case PeerClosed ⇒ context stop self
  }
}

