package mesh.utils

import java.io.{ByteArrayOutputStream, OutputStream, OutputStreamWriter, PrintWriter}
import java.util.concurrent.atomic.AtomicLong

import akka.stream.scaladsl.Flow
import akka.util.ByteString

object DubboEncoder {

  object DubboEncoder {

    private val HEADER_LENGTH = 16
    private val requestId = new AtomicLong()

    private val MAGIC = 0xdabb.toShort
    private val FLAG_REQUEST = 0x80.toByte
    private val FLAG_TWOWAY = 0x40.toByte
    private val FLAG_EVENT = 0x20.toByte

    def encodeRequestData(out: OutputStream, path: String, method: String, parameterTypes: String, arguments: Array[Byte]): Unit = {
      val writer = new PrintWriter(new OutputStreamWriter(out))
      JsonUtils.writeObject("2.6.0", writer)
      JsonUtils.writeObject(path, writer)
      JsonUtils.writeObject("0.0.0", writer)
      JsonUtils.writeObject(method, writer)
      JsonUtils.writeObject(parameterTypes, writer)
      JsonUtils.writeBytes(arguments, writer)
      JsonUtils.writeObject(Map("path" -> path, "dubbo" -> "2.6.0"), writer)
    }

    def map2DubboByteString(map:Map[String, String]): ByteString = {

      val header = new Array[Byte](HEADER_LENGTH)
      // set magic number.
      Bytes.short2bytes(MAGIC, header)

      // set request and serialization flag.
      header(2) = (FLAG_REQUEST | 6).toByte

      header(2) = (header(2) | FLAG_TWOWAY).toByte

      Bytes.long2bytes(map("requestId").toLong, header, 4)

      val out = new ByteArrayOutputStream()

      val writer = new PrintWriter(new OutputStreamWriter(out))
      JsonUtils.writeObject(map("parameter"), writer)

      val bos = new ByteArrayOutputStream
      encodeRequestData(bos, map("interface"), map("method"), map("parameterTypesString"), out.toByteArray)


      val len = bos.size()

      Bytes.int2bytes(len, header, 12)

      ByteString(header ++ bos.toByteArray)

    }

    val flow = Flow[Map[String, String]].map(map2DubboByteString)

  }
}
