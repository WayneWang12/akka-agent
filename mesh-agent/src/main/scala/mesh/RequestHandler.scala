package mesh

import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

import scala.concurrent.Future


class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system

  import scala.concurrent.duration._

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def throttleTcp(num: Int, tcp: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]]) = {
    val list = List.fill(3)(tcp.async.buffer(256, OverflowStrategy.backpressure).to(DubboFlow.decoder))
    val flow = Flow[ByteString].throttle(num, 50.millis)
    val sink = Sink.combine(list.head, list(1), list(2))(Balance[ByteString](_))
    flow.to(sink)
  }

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.map { endpoint =>
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port)
      endpoint.scale match {
        case ProviderScale.Small =>
          throttleTcp(30, tcp)
        case ProviderScale.Medium =>
          throttleTcp(100, tcp)
        case ProviderScale.Large =>
          throttleTcp(150, tcp)
      }
    }
    Sink.combine(tcpFlows.head, tcpFlows(1), tcpFlows(2))(Balance[ByteString](_))
  }

  def getSourceByEndpoints(endpoints: Set[Endpoint]) = {
    val handleFlow = Flow[(Long, ByteString)]
      .async
      .via(DubboFlow.connectionIdFlow)
      .async
      .buffer(256, OverflowStrategy.backpressure)
      .to(endpointsFlow(endpoints))
      .async
    Source.queue[(Long, ByteString)](256, OverflowStrategy.backpressure).async
      .to(handleFlow).run()
  }

  var source: SourceQueueWithComplete[(Long, ByteString)] = _

  override def receive: Receive = {
    case (cid: Long, bs: ByteString) =>
      source.offer(cid -> bs)
    case EndpointsUpdate(newEndpoints) =>
      log.info(s"start new source for endpoints $newEndpoints")
      source = getSourceByEndpoints(newEndpoints)
  }
}
