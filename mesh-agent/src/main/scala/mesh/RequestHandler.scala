package mesh

import java.nio.ByteOrder

import akka.NotUsed
import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, Framing, GraphDSL, Merge, Partition, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.{dispatcher, system}

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def proportionalEndpoints(endpoints: List[(Endpoint, Int)]): Flow[ByteString, ByteString, NotUsed] = {
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val sum = endpoints.map(_._2).sum
      val merge = builder.add(Merge[ByteString](sum))
      val balancer = builder.add(Balance[ByteString](sum))

      val framing = Framing.lengthField(4, 12, Int.MaxValue, ByteOrder.BIG_ENDIAN)

      endpoints.foreach {
        case (ed, num) =>
          val tcp = Tcp().outgoingConnection(ed.host, ed.port)
          val flows = List.fill(num)(tcp)
          flows.foreach(f => balancer ~> f.async ~> framing.async ~> merge)
      }
      FlowShape(balancer.in, merge.out)
    })
  }

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.map { endpoint =>
      endpoint.scale match {
        case ProviderScale.Small =>
          (endpoint, 0)
        case ProviderScale.Medium =>
          (endpoint, 1)
        case ProviderScale.Large =>
          (endpoint, 1)
      }
    }

    proportionalEndpoints(tcpFlows.filter(_._2 > 0))
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
      source.offer(cid -> bs)
    case EndpointsUpdate(newEndpoints) =>
      log.info(s"start new source for endpoints $newEndpoints")
      source = getSourceByEndpoints(newEndpoints)
  }
}
