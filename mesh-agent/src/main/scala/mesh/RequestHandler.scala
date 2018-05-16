package mesh

import akka.actor.{Actor, ActorLogging}
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import mesh.utils.DubboFlow

import scala.util.Success

class RequestHandler(implicit materializer: Materializer) extends Actor with ActorLogging {

  import context.system

  context.system.eventStream.subscribe(self, classOf[EndpointsUpdate])

  def endpointsFlow(endpoints: Set[Endpoint]) = {
    val tcpFlows = endpoints.toList.flatMap { endpoint =>
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port).async
      val num = endpoint.port match {
        case 30000 =>
//          tcp.throttle(500, 1.second)
          0
        case 30001 =>
//          tcp.throttle(1800, 1.second)
          1
        case 30002 =>
//          tcp.throttle(2700, 1.second)
          0
        case _     =>
          4
      }
      List.fill(num)(tcp)
    }

    Flow.fromGraph(GraphDSL.create(tcpFlows) { implicit builder =>
      tcpFlows =>
        import GraphDSL.Implicits._

        val balance = builder.add(Balance[ByteString](tcpFlows.size))
        val bigMerge = builder.add(Merge[(Long, ByteString)](tcpFlows.size))

        tcpFlows.foreach { tcp =>
          balance ~> tcp ~> DubboFlow.decoder.async ~> bigMerge
        }

        FlowShape(balance.in, bigMerge.out)
    })
  }


  def getSourceByEndpoints(endpoints: Set[Endpoint]) = {
    val handleFlow = Flow[(Long, ByteString)]
      .via(DubboFlow.connectionIdFlow)
      .via(endpointsFlow(endpoints))
    Source.queue[(Long, ByteString)](256, OverflowStrategy.backpressure)
      .via(handleFlow).to(Sink.foreach {
      case (connectionId, bs) =>
        val actor = context.actorSelection(s"/user/consumer-agent/$connectionId")
        actor ! bs
    }).run()
  }

  var endpoints = List(30000, 30001, 30002).map(port => Endpoint("localhost", port))

  var source: Option[SourceQueueWithComplete[(Long, ByteString)]] = None

  import context.dispatcher

  override def receive: Receive = {
    case (cid: Long, bs: ByteString) =>
      source.foreach(_.offer(cid -> bs))
    case EndpointsUpdate(newEndpoints) =>
      source.foreach {
        s =>
          s.complete()
          s.watchCompletion().onComplete {
            case Success(_) =>
              log.info(s"new endpoints $newEndpoints found. Close old ones.")
          }
      }
      log.info(s"start new source for endpoints $newEndpoints")
      source = Some(getSourceByEndpoints(newEndpoints))
  }
}
