package mesh.utils

import java.io.PrintWriter

import com.alibaba.fastjson.serializer.{JSONSerializer, SerializeWriter, SerializerFeature}

object JsonUtils {

  def writeObject(obj: Any, writer: PrintWriter): Unit = {
    val out = new SerializeWriter
    val serializer = new JSONSerializer(out)
    serializer.config(SerializerFeature.WriteEnumUsingToString, true)
    serializer.write(obj)
    out.writeTo(writer)
    out.close() // for reuse SerializeWriter buf

    writer.println()
    writer.flush()
  }

  def writeBytes(b: Array[Byte], writer: PrintWriter): Unit = {
    writer.print(new String(b))
    writer.flush()
  }
}
