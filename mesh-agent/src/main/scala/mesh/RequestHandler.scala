package mesh

import java.nio.ByteOrder

import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, Framing, GraphDSL, Merge, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

import scala.concurrent.Future

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system
  import context.dispatcher

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.flatMap { endpoint =>
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port)
        .via(Framing
          .lengthField(4, 12, 64 * 1024, ByteOrder.BIG_ENDIAN)
        )
      endpoint.scale match {
        case ProviderScale.Small =>
          List.fill(1)(tcp)
        case ProviderScale.Medium =>
          List.fill(2)(tcp)
        case ProviderScale.Large =>
          List.fill(3)(tcp)
      }
    }
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val balancer = builder.add(Balance[ByteString](tcpFlows.size))
      val merge = builder.add(Merge[ByteString](tcpFlows.size))
      tcpFlows.foreach { tcp =>
        balancer ~> tcp.async ~> merge
      }
      FlowShape(balancer.in, merge.out)
    })
  }

  def getSourceByEndpoints(endpoints: Set[Endpoint]): SourceQueueWithComplete[(Long, ByteString)] = {
    val handleFlow = Flow[(Long, ByteString)]
      .via(DubboFlow.connectionIdFlow)
      .via(endpointsFlow(endpoints))
      .to(DubboFlow.decoder)
    Source.queue[(Long, ByteString)](256, OverflowStrategy.backpressure)
      .to(handleFlow).run()
  }

  var source: SourceQueueWithComplete[(Long, ByteString)] = _

  override def receive: Receive = {
    case (cid: Long, bs: ByteString) =>
      Future.successful("").flatMap()
      source.offer(cid -> bs)
    case EndpointsUpdate(newEndpoints) =>
      log.info(s"start new source for endpoints $newEndpoints")
      source = getSourceByEndpoints(newEndpoints)
  }
}
