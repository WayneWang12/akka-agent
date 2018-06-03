package mesh.proxy

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._

class DubboFrontend(val remoteHost: String, val remotePort: Int) extends ChannelInboundHandlerAdapter {
  // As we use inboundChannel.eventLoop() when building the Bootstrap this does not need to be volatile as
  // the outboundChannel will use the same EventLoop (and therefore Thread) as the inboundChannel.
  private var outboundChannel: Channel = _

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    val inboundChannel = ctx.channel
    // Start the connection attempt.
    val b = new Bootstrap()
    b.group(inboundChannel.eventLoop).channel(ctx.channel.getClass)
      .handler(new DubboBakcend(inboundChannel))
      .option(ChannelOption.AUTO_READ, java.lang.Boolean.FALSE)
    val f = b.connect(remoteHost, remotePort)
    outboundChannel = f.channel
    f.addListener((future: ChannelFuture) => {
      if (future.isSuccess) { // connection complete start to read first data
        inboundChannel.read
      }
      else { // Close the connection if the connection attempt has failed.
        inboundChannel.close
      }
    })
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    if (outboundChannel.isActive)
      outboundChannel.writeAndFlush(msg).addListener((future: ChannelFuture) => {
        if (future.isSuccess) { // was able to flush out data, start to read the next chunk
          ctx.channel.read
        }
        else future.channel.close
      })
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    if (outboundChannel != null) closeOnFlush(outboundChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    cause.printStackTrace()
    closeOnFlush(ctx.channel)
  }


  def closeOnFlush(ch: Channel): Unit = {
    if (ch.isActive) ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
  }
}
