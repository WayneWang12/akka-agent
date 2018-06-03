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

  val etcdHandler = new EtcdHandler(etcdHost)

  serviceType match {
    case "consumer" =>
      val etcdManager = actorSystem.actorOf(Props(
        new EtcdManager(etcdHandler, serverPort)
      ))
      actorSystem.actorOf(Props(new Consumer(hostIp, etcdManager)), "consumer-agent")
    case "provider" =>
      val dubboPort = config.getInt("dubbo.protocol.port")
      val scale = config.getString("scale")
      val providerScale = ProviderScale.withName(scale)
      etcdHandler.register(etcdHandler.serviceName, serverPort, providerScale)
      etcdHandler.keepAlive()
      val provider = new Provider(hostIp, serverPort, dubboPort, providerScale)
      provider.startService
  }

}
