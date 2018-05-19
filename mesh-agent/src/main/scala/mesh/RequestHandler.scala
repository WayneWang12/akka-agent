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
    val tcpFlows = endpoints.toList.map { endpoint =>
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port)
      endpoint.scale match {
        case ProviderScale.Small =>
          tcp.throttle(50, 50.millis).async
        case ProviderScale.Medium =>
          tcp.throttle(100, 50.millis).async
        case ProviderScale.Large =>
          tcp.throttle(150, 50.millis).async
      }
    }
    Sink.fromGraph(GraphDSL.create(tcpFlows) {implicit builder => tcps =>
      import GraphDSL.Implicits._
      val balancer = builder.add(Balance[ByteString](tcps.size))
      val merge = builder.add(Merge[ByteString](tcps.size))
      tcps.foreach {tcp =>
        balancer ~> tcp ~> merge
      }

      merge ~> Flow[ByteString].buffer(256, OverflowStrategy.backpressure) ~> DubboFlow.decoder

      SinkShape(balancer.in)
    })
  }

  def getSourceByEndpoints(endpoints: Set[Endpoint]) = {
    val handleFlow = Flow[(Long, ByteString)]
      .via(DubboFlow.connectionIdFlow)
      .buffer(256, OverflowStrategy.backpressure)
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
