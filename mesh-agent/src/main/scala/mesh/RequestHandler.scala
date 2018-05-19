package mesh

import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

import scala.util.Success

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system
  import scala.concurrent.duration._

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.flatMap { endpoint =>
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port).async
      val num = endpoint.scale match {
        case ProviderScale.Small =>
          //          tcp.throttle(20, 55.millis)
          1
        case ProviderScale.Medium =>
          //          tcp.throttle(80, 55.millis)
          2
        case ProviderScale.Large =>
          //          tcp.throttle(150, 55.millis)
          3
        case _ =>
          //          tcp
          0
      }
      List.fill(num)(tcp)
    }

    Sink.fromGraph(GraphDSL.create(tcpFlows) { implicit builder =>
      tcpFlows =>
        import GraphDSL.Implicits._
        val balance = builder.add(Balance[ByteString](tcpFlows.size))
        tcpFlows.foreach { tcp =>
          balance ~> tcp ~> DubboFlow.decoder.async
        }

        SinkShape(balance.in)
    })
  }

  def getSourceByEndpoints(endpoints: Set[Endpoint]) = {
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
