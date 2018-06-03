package mesh

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.{ChannelOption, EventLoopGroup}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import mesh.proxy.DubboInitializer

import scala.util.control.NonFatal

class Provider(localhost: String, port: Int, dubboPort: Int) {

  def startService: Unit = {
    // Configure the bootstrap.
    val bossGroup: EventLoopGroup = new EpollEventLoopGroup(1)
    val workerGroup: EventLoopGroup = new EpollEventLoopGroup(2)
    try {
      val b = new ServerBootstrap()
      val c = b.group(bossGroup, workerGroup).channel(classOf[EpollServerSocketChannel])
        .childHandler(new DubboInitializer(localhost, dubboPort))
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .childOption(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
        .bind(localhost, port)
        .sync()

      c.channel().closeFuture().sync()
    } catch {
      case NonFatal(t) =>
        t.printStackTrace()
      case e: Exception =>
        e.printStackTrace()
    } finally {
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
    }
  }

}

