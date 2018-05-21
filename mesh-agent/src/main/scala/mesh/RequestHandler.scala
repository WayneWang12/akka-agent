package mesh

import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

import scala.concurrent.duration._

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.flatMap { endpoint =>
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port).async
      endpoint.scale match {
        case ProviderScale.Small =>
          List.fill(0)(tcp)
        case ProviderScale.Medium =>
          List.fill(1)(tcp)
        case ProviderScale.Large =>
          List.fill(3)(tcp)
      }
    }
    Sink.fromGraph(GraphDSL.create(tcpFlows) {implicit builder => tcps =>
      import GraphDSL.Implicits._
      val balancer = builder.add(Balance[ByteString](tcps.size))
      val merge = builder.add(Merge[ByteString](tcps.size))
      val sink = DubboFlow.decoder
      tcps.foreach {tcp =>
        balancer ~> tcp ~> merge
      }
      merge ~> sink
      SinkShape(balancer.in)
    })
  }

  def getSourceByEndpoints(endpoints: Set[Endpoint]): SourceQueueWithComplete[(Long, ByteString)] = {
    val handleFlow = Flow[(Long, ByteString)]
      .via(DubboFlow.connectionIdFlow)
      .to(endpointsFlow(endpoints))
    Source.queue[(Long, ByteString)](256, OverflowStrategy.backpressure)
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
