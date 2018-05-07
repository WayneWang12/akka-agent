package mesh

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.{ActorMaterializer, FlowShape}
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, MergeHub, Tcp}
import akka.http.scaladsl.server.Directives._
import akka.util.ByteString

import scala.concurrent.Future

class Provider(host: String, port: Int, dubboPort: Int) {
  implicit val actorSystem = ActorSystem(s"provider-mesh-$port")
  implicit val materializer = ActorMaterializer()
  val tcps = List.fill(100)(Tcp().outgoingConnection(host, dubboPort))

  val wsHandler = Flow.fromGraph(GraphDSL.create(tcps) {implicit builder => flows =>
    import GraphDSL.Implicits._
    val balance = builder.add(Balance[ByteString](flows.size))
    val merge = builder.add(Merge[ByteString](flows.size))
    flows.foreach{ tcp =>
      balance ~> tcp ~> merge
    }
    FlowShape(balance.in, merge.out)
  })

  val mergeHub = Flow[Message].flatMapConcat {
    case msg:TextMessage =>
      msg.textStream.map(ByteString(_))
    case msg:BinaryMessage =>
      msg.dataStream
  }.via(wsHandler).map(bs => TextMessage(bs.utf8String))

  val websocketRoute =
    path("greeter") {
      handleWebSocketMessages(mergeHub)
    }

  def startService: Future[Done] = {
    Tcp().bind(host, port).runForeach { conn =>
      conn.handleWith(Tcp().outgoingConnection(host, dubboPort))
    }
  }

}
