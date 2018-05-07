package mesh

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.stream._
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Sink, Tcp}
import akka.util.ByteString
import mesh.utils.Bytes

class RequestHandler(implicit actorSystem: ActorSystem, materializer: Materializer) {

  val blockDispatcherId = "blocking-io-dispatcher"

  def endpointsFlow(endpoints: List[Endpoint]) = {
    val tcps = endpoints.flatMap { endpoint =>
      val ws = Http().webSocketClientFlow(WebSocketRequest(s"ws://${endpoint.host}:${endpoint.port}"))
      val tcp = Tcp().outgoingConnection(endpoint.host, endpoint.port)
      val connections = endpoint.port match {
        case 30000 => 100
        case 30001 => 100
        case 30002 => 120
        case _ => 120
      }
      List.fill(connections)(tcp)
    }

    Sink.fromGraph(GraphDSL.create(tcps) { implicit builder =>
      flows =>
        import GraphDSL.Implicits._

        val balance = builder.add(Balance[ByteString](tcps.size))
        val merge = builder.add(Merge[ByteString](tcps.size))

        val bs2resp = Flow[ByteString].map {
          bs =>
            val connId = if (bs.drop(16).dropRight(1).utf8String == "null") -1
            else Bytes.bytes2long(bs.toArray, 4)
            (connId, HttpResponse(entity = bs.drop(18).dropRight(1)))
        }

        import actorSystem.dispatcher

        val sink = Sink.foreachParallel[(Long, HttpResponse)](8) {
          case (connId, resp) =>
            if (connId != -1) {
              val actor = actorSystem.actorSelection(s"/user/$connId")
              actor ! resp
            }
        }


        flows.foreach { tcpFlow =>
          balance ~> tcpFlow ~> merge
        }
        merge ~> bs2resp ~> sink

        SinkShape(balance.in)
    })
  }

}
