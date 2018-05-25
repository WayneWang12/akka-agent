package mesh.utils

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.ByteString

import scala.concurrent.ExecutionContext

object DubboFlow {

  private val HEADER_LENGTH = 16

  private val MAGIC = 0xdabb.toShort
  private val FLAG_REQUEST = 0x80.toByte
  private val FLAG_TWOWAY = 0x40.toByte
  private val FLAG_EVENT = 0x20.toByte
  private val dubboVersion = ByteString("\"2.6.0\"\n")
  private val interface = ByteString("\"com.alibaba.dubbo.performance.demo.provider.IHelloService\"\n")
  private val method = ByteString("\"hash\"\n")
  private val version = ByteString("\"0.0.0\"\n")
  private val pType = ByteString("\"Ljava/lang/String;\"\n")
  private val dubboEnd = ByteString("{\"empty\":false,\"traversableAgain\":true}\n")
  val slicer = "parameter=".map(_.toByte)
  val quote = ByteString('\"')
  val QuoteAndCarriageReturn = ByteString("\"\r\n")
  val HttpOkStatus = ByteString("HTTP/1.1 200 OK\r\n")
  val KeepAlive = ByteString("Connection: Keep-Alive\r\n")
  val ContentType = ByteString("Content-Type: application/octet-stream\r\n")
  val HeaderDelimter = ByteString("\r\n")
  val SlashN = ByteString("\n")

  val dubboHeader = {
    val array = new Array[Byte](4)
    Bytes.short2bytes(MAGIC, array)
    array(2) = (FLAG_REQUEST | 6).toByte
    array(2) = (array(2) | FLAG_TWOWAY).toByte
    ByteString(array)
  }

  def map2DubboByteString(requestId: Long, parameter: ByteString): ByteString = {
    val idAndLength = new Array[Byte](12)
    Bytes.long2bytes(requestId, idAndLength, 0)
    val data = dubboVersion ++ interface ++ version ++ method ++ pType ++ parameter ++ dubboEnd
    Bytes.int2bytes(data.size, idAndLength, 8)
    dubboHeader ++ ByteString(idAndLength) ++ data
  }


  def cLength(length: Int) = {
    ByteString(s"Content-Length: $length\r\n")
  }

  def connectionIdFlow(implicit executionContext: ExecutionContext): Flow[(Long, ByteString), ByteString, NotUsed] =
    Flow[(Long, ByteString)].map {
      case (cid, bs) =>
        http2DubboByteString(cid, bs)
    }

  def http2DubboByteString(cid: Long, bs: ByteString): ByteString = {
    val n = bs.indexOfSlice(slicer)
    val s = bs.drop(n + slicer.size)
    val d = quote ++ s ++ QuoteAndCarriageReturn
    val data = map2DubboByteString(cid, d)
    data
  }

  val headers = HttpOkStatus ++ KeepAlive ++ ContentType

  def decoder(implicit actorSystem: ActorSystem) = {
      Sink.foreach[ByteString]{
        bs =>
          val cid = Bytes.bytes2long(bs, 4)
          val data = bs.slice(18, bs.size - 1)
          val resp = headers ++ cLength(data.size) ++ HeaderDelimter ++ data
          val actor = actorSystem.actorSelection(s"/user/consumer-agent/$cid")
          actor ! resp
      }
  }
}
