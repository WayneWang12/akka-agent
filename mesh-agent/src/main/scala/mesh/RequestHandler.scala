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
      val merge = builder.add(Merge[ByteString](endpoints.size))
      val balancer = builder.add(Balance[ByteString](sum))

      val framing = Framing.lengthField(4, 12, Int.MaxValue, ByteOrder.BIG_ENDIAN)

      endpoints.foreach {
        case (ed, num) =>
          val tcp = Flow[ByteString].buffer(100, OverflowStrategy.backpressure).via(Tcp().outgoingConnection(ed.host, ed.port))
          val flows = List.fill(num)(Flow[ByteString])
          val m = builder.add(Merge[ByteString](num))
          flows.foreach(f => balancer ~> f ~> m)
          m ~> tcp ~> framing ~> merge
      }
      FlowShape(balancer.in, merge.out)
    })
  }

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.map { endpoint =>
      endpoint.scale match {
        case ProviderScale.Small =>
          (endpoint, 1)
        case ProviderScale.Medium =>
          (endpoint, 4)
        case ProviderScale.Large =>
          (endpoint, 5)
      }
    }
    proportionalEndpoints(tcpFlows)
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
