package mesh.proxy

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel


class DubboInitializer(val remoteHost: String, val remotePort: Int) extends ChannelInitializer[SocketChannel] {
  override def initChannel(ch: SocketChannel): Unit = {
    ch.pipeline
      .addLast("proxy", new DubboFrontend(remoteHost, remotePort))
  }
}
