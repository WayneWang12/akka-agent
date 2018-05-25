package mesh

import java.nio.ByteOrder

import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, Framing, GraphDSL, Merge, Partition, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.{dispatcher, system}

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.sortBy(_.scale).map { endpoint =>
      val tcp = Flow[ByteString].buffer(20, OverflowStrategy.backpressure).via(Tcp().outgoingConnection(endpoint.host, endpoint.port))
      tcp
    }

  def proportional[T]: T => Int = {
    var i = -1
    _ => {
      i += 1
      if (i < 0) i
      else if (i <= 4) 1
      else if (i <= 8) 2
      else {
        i = -1
        2
      }
    }
  }

  Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._
    val balancer = builder.add(Partition[ByteString](tcpFlows.size, proportional))
    val merge = builder.add(Merge[ByteString](tcpFlows.size))
    tcpFlows.foreach { tcp =>
      balancer ~> tcp.async ~> merge
    }
    FlowShape(balancer.in, merge.out)
  })
}

def getSourceByEndpoints (endpoints: Set[Endpoint] ): SourceQueueWithComplete[(Long, ByteString)] = {
  val handleFlow = Flow[(Long, ByteString)]
  .via (DubboFlow.connectionIdFlow)
  .via (endpointsFlow (endpoints) )
  .via (Framing.lengthField (4, 12, 64 * 1024, ByteOrder.BIG_ENDIAN) )
  .to (DubboFlow.decoder)
  Source.queue[(Long, ByteString)] (256, OverflowStrategy.backpressure)
  .to (handleFlow).run ()
}

  var source: SourceQueueWithComplete[(Long, ByteString)] = _

  override def receive: Receive = {
  case (cid: Long, bs: ByteString) =>
  source.offer (cid -> bs)
  case EndpointsUpdate (newEndpoints) =>
  log.info (s"start new source for endpoints $newEndpoints")
  source = getSourceByEndpoints (newEndpoints)
}
}
