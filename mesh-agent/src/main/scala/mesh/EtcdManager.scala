package mesh

import java.net.InetAddress
import java.text.MessageFormat

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.event.Logging
import com.coreos.jetcd.Client
import com.coreos.jetcd.data.ByteSequence
import com.coreos.jetcd.kv.GetResponse
import com.coreos.jetcd.options.{GetOption, PutOption}
import mesh.ProviderScale.ProviderScale

class EtcdHandler(etcdUrl: String)(implicit actorSystem: ActorSystem) {

  private val log = Logging(actorSystem, this.getClass)

  log.info(s"get etcd uri $etcdUrl")

  val client: Client = Client.builder().endpoints(etcdUrl).build()

  val kvClient = client.getKVClient
  val lease = client.getLeaseClient
  var leaseId: Long = _

  val rootPath = "dubbomesh"
  val serviceName = "com.alibaba.dubbo.performance.demo.provider.IHelloService"

  try {
    val id = lease.grant(30).get.getID
    leaseId = id
  } catch {
    case e: Throwable => e.printStackTrace()
  }

  def keepAlive(): Unit = {
    try {
      val listener = lease.keepAlive(leaseId)
      listener.listen
      log.info("KeepAlive lease:" + leaseId + "; Hex format:" + leaseId.toHexString)
    } catch {
      case e: Exception =>
        e.printStackTrace()
    }
  }

  def register(serviceName: String, port: Int, scale: ProviderScale): Unit = { // 服务注册的key为:    /dubbomesh/com.some.package.IHelloService/192.168.100.100:2000
    val strKey = MessageFormat.format("/{0}/{1}/{2}:{3}", rootPath, serviceName, IpHelper.getHostIp, String.valueOf(port))

    val key = ByteSequence.fromString(strKey)
    val value = ByteSequence.fromString(scale.toString)
    kvClient.put(key, value, PutOption.newBuilder.withLeaseId(leaseId).build).get()
    log.info("Register a new service at:" + strKey)
  }

  def find(serviceName: String): Set[Endpoint] = {
    val strKey: String = MessageFormat.format("/{0}/{1}", rootPath, serviceName)
    val key: ByteSequence = ByteSequence.fromString(strKey)
    val response: GetResponse = kvClient.get(key, GetOption.newBuilder.withPrefix(key).build).get

    import scala.collection.JavaConverters._
    val ed = for (kv <- response.getKvs.asScala) yield {
      val scale = kv.getValue.toStringUtf8
      val s: String = kv.getKey.toStringUtf8
      val index: Int = s.lastIndexOf("/")
      val endpointStr: String = s.substring(index + 1, s.length)
      val host: String = endpointStr.split(":")(0)
      val port: Int = Integer.valueOf(endpointStr.split(":")(1))
      Endpoint(host, port, ProviderScale.withName(scale))
    }
    ed.toSet
  }


}

class EtcdManager(etcdHandler: EtcdHandler, serverPort: Int) extends Actor with ActorLogging {

  import etcdHandler._

  var endpoints = Set.empty[Endpoint]

  override def receive: Receive = {
    case "consumer" =>
      val found = find(serviceName)
      if (found != endpoints) {
        endpoints = found
        log.info(s"found new endpoints $found")
        context.system.eventStream.publish(EndpointsUpdate(endpoints))
        client.close()
        context stop self
      }
  }

}

object ProviderScale extends Enumeration {
  type ProviderScale = Value
  val Small, Medium, Large = Value
}

case class Endpoint(host: String, port: Int, scale: ProviderScale)

case class EndpointsUpdate(endpoints: Set[Endpoint])

object IpHelper {
  def getHostIp: String = {
    val ip = InetAddress.getLocalHost.getHostAddress
    ip
  }
}
