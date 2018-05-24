package mesh

import java.net.InetAddress
import java.text.MessageFormat

import akka.actor.{Actor, ActorLogging}
import com.coreos.jetcd.Client
import com.coreos.jetcd.data.ByteSequence
import com.coreos.jetcd.kv.GetResponse
import com.coreos.jetcd.options.{GetOption, PutOption}
import mesh.ProviderScale.ProviderScale

class EtcdManager(etcdUrl: String, serverPort: Int) extends Actor with ActorLogging {

  log.info(s"get etcd uri $etcdUrl")

  val client: Client = Client.builder().endpoints(etcdUrl).build()

  val kvClient = client.getKVClient
  val lease = client.getLeaseClient

  var leaseId: Long = _

  private val rootPath = "dubbomesh"
  private val serviceName = "com.alibaba.dubbo.performance.demo.provider.IHelloService"

  var endpoints = Set.empty[Endpoint]

  import scala.concurrent.duration._
  import context.dispatcher

  context.system.scheduler.scheduleOnce(60.second, self, "consumer")

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
    case ("provider", scale:ProviderScale) =>
      try {
        val id = lease.grant(30).get.getID
        leaseId = id
      } catch {
        case e:Throwable => e.printStackTrace()
      }

      keepAlive()
      register(serviceName, serverPort, scale)
  }

  // 该EtcdRegistry没有使用etcd的Watch机制来监听etcd的事件
  // 添加watch，在本地内存缓存地址列表，可减少网络调用的次数
  // 使用的是简单的随机负载均衡，如果provider性能不一致，随机策略会影响性能
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


  def register(serviceName: String, port: Int, scale:ProviderScale): Unit = { // 服务注册的key为:    /dubbomesh/com.some.package.IHelloService/192.168.100.100:2000
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

object ProviderScale extends Enumeration {
  type ProviderScale = Value
  val Small, Medium, Large = Value
}

case class Endpoint(host: String, port: Int, scale:ProviderScale)

case class EndpointsUpdate(endpoints: Set[Endpoint])

object IpHelper {
  def getHostIp: String = {
    val ip = InetAddress.getLocalHost.getHostAddress
    ip
  }
}
