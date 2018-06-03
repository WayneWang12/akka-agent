package mesh

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

object Server extends App {

  val config = ConfigFactory.load()

  val serviceType = config.getString("type")
  val serverPort = config.getInt("server.port")

  implicit val actorSystem: ActorSystem = ActorSystem("dubbo-mesh")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val hostIp = IpHelper.getHostIp

  val etcdHost = config.getString("etcd.url")

  val etcdManager = actorSystem.actorOf(Props(
    new EtcdManager(etcdHost, serverPort)
  ))


  serviceType match {
    case "consumer" =>
      actorSystem.actorOf(Props(new Consumer(hostIp)), "consumer-agent")
      Thread.sleep(1000) //one second for etcd client to start.
      etcdManager ! serviceType
    case "provider" =>
      val dubboPort = config.getInt("dubbo.protocol.port")
      val scale = config.getString("scale")
      val provider = new Provider(hostIp, serverPort, dubboPort)
      Thread.sleep(1000) //one second for etcd client to start.
      etcdManager ! (serviceType, ProviderScale.withName(scale))
      provider.startService
  }

}
