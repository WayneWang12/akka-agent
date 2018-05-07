package mesh

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Keep, MergeHub, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.{ByteString, Timeout}
import mesh.utils.DubboEncoder.DubboEncoder

import scala.collection.mutable
import scala.concurrent.Future

class Consumer(implicit actorSystem: ActorSystem, materializer: Materializer) {

  val endpoints = List(30000, 30001, 30002).map(port => Endpoint("localhost", port))

  def requestIdFlow(requestId: Long) = {
    Flow[HttpRequest].flatMapConcat(_.entity.dataBytes).map { bs =>
      val map = bs.utf8String.split("&").map { s =>
        val seq = s.split("=")
        if (seq.length == 1) {
          seq(0) -> ""
        }
        else if ("parameterTypesString" == seq(0)) seq(0) -> URLDecoder.decode(seq(1), "utf8")
        else seq(0) -> seq(1)
      }.toMap + ("requestId" -> requestId.toString)
      map
    }.via(DubboEncoder.flow)
  }


  val sink = MergeHub.source[ByteString]
    .log("merge hub")
    .toMat(new RequestHandler().endpointsFlow(endpoints))(Keep.left)
    .run()

  import actorSystem.dispatcher

  import scala.concurrent.duration._
  implicit val askTimeout = Timeout(5.seconds)

  def startService = {
    val connectionId = new AtomicInteger()

    Http().bind("localhost", 20000).runForeach { conn =>
      val connActorId = connectionId.getAndIncrement()

      val connFlow = requestIdFlow(connActorId).to(sink)
      val source = Source.queue(10, OverflowStrategy.backpressure).to(connFlow).run()
      val connectionActor = actorSystem.actorOf(Props(new Actor {
        val senders = new mutable.Queue[ActorRef]()
        override def receive: Receive = {
          case request: HttpRequest =>
            senders.enqueue(sender())
            source.offer(request)
          case resp: HttpResponse =>
            senders.dequeue() ! resp
        }
      }), connActorId.toString)

      val handleFlow = Flow[HttpRequest].ask[HttpResponse](connectionActor).watchTermination()(Keep.right)

      val closingCallback = conn.handleWith(handleFlow)
      closingCallback.onComplete(_ => actorSystem.stop(connectionActor))
    }
  }

}

case class Endpoint(host: String, port: Int)


