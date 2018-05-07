package mesh

import java.util.NoSuchElementException

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

object Server extends App {

  implicit val actorSystem: ActorSystem = ActorSystem("dubbo-mesh")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val config = ConfigFactory.load()

  val serviceType = config.getString("type")
  val serverPort = config.getInt("server.port")

  val hostIp = IpHelper.getHostIp

  serviceType match {
    case "consumer" =>
      actorSystem.actorOf(Props(new Consumer(hostIp)), "consumer-agent")
    case "provider" =>
      val dubboPort = config.getInt("dubbo.protocol.port")
      val provider = new Provider(hostIp, serverPort, dubboPort)
      provider.startService
    case _ =>
      throw new NoSuchElementException("No such service!")
  }

  val etcdHost = config.getString("etcd.url")

  val etcdManager = actorSystem.actorOf(Props(
    new EtcdManager(etcdHost, serverPort)
  ).withDispatcher("pinned-dispatcher"))

  etcdManager ! serviceType

}
