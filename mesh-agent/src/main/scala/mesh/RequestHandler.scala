package mesh

import java.nio.ByteOrder

import akka.NotUsed
import akka.actor.{Actor, ActorLogging}
import akka.io.Tcp.Received
import akka.stream._
import akka.stream.scaladsl.{Flow, Framing, GraphDSL, Merge, Partition, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

import scala.util.Random

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.{dispatcher, system}

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def proportionalEndpoints(endpoints: List[(Endpoint, Int)]): Flow[ByteString, ByteString, NotUsed] = {
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val sum = endpoints.size

      val list = endpoints.map(_._2)
      val listUpperBound = list.sum
      val ranges = list.scanLeft(0)(_ + _).sliding(2, 1).toList.map(t => (t.head, t(1)))
      val routeStrategy = (bs: ByteString) => {
        val x = Random.nextInt(listUpperBound)
        ranges.indexWhere(t => x >= t._1 && x < t._2)
      }

      val merge = builder.add(Merge[ByteString](sum))
      val balancer = builder.add(Partition[ByteString](sum, routeStrategy))

      val framing = Framing.lengthField(4, 12, Int.MaxValue, ByteOrder.BIG_ENDIAN)

      endpoints.foreach {
        case (ed, _) =>
          val tcp = Flow[ByteString]
            .buffer(200, OverflowStrategy.backpressure)
            .via(Tcp().outgoingConnection(ed.host, ed.port))
          balancer ~> tcp.async ~> framing.async ~> merge
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
          (endpoint, 2)
        case ProviderScale.Large =>
          (endpoint, 8)
      }
    }

    proportionalEndpoints(tcpFlows.filter(_._2 > 0))
  }

  def getSourceByEndpoints(endpoints: Set[Endpoint]): SourceQueueWithComplete[(Long, ByteString)] = {
    val handleFlow = Flow[(Long, ByteString)]
      .via(DubboFlow.connectionIdFlow)
      .via(endpointsFlow(endpoints))
      .to(DubboFlow.decoder)
    Source.queue[(Long, ByteString)](512, OverflowStrategy.backpressure)
      .to(handleFlow).run()
  }

  var source: SourceQueueWithComplete[(Long, ByteString)] = _

  override def receive: Receive = {
    case Received(bs) =>
      source.offer(sender().path.name.toLong, bs)
    case EndpointsUpdate(newEndpoints) =>
      log.info(s"start new source for endpoints $newEndpoints")
      source.complete()
      source = getSourceByEndpoints(newEndpoints)
    case _ =>
  }
}
