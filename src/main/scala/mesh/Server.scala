package mesh

import java.util.NoSuchElementException

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

object Server extends App {

  implicit val actorSystem: ActorSystem = ActorSystem("dubbo-mesh")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val config = ConfigFactory.load()

  val serviceType = config.getString("type")

  serviceType match {
    case "consumer" =>
      val consumer = new Consumer()
      consumer.startService
    case "provider" =>
      val port = config.getInt("server.port")
      val dubboPort = config.getInt("dubbo.protocol.port")
      val provider = new Provider("localhost", port, dubboPort)
      provider.startService
    case _ =>
      throw new NoSuchElementException("No such service!")
  }

}
