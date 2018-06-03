package mesh.proxy

import io.netty.channel.{Channel, ChannelFuture, ChannelHandlerContext, ChannelInboundHandlerAdapter}

class DubboBackend(val inboundChannel: Channel) extends ChannelInboundHandlerAdapter {
  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    ctx.read
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    inboundChannel.writeAndFlush(msg).addListener((future: ChannelFuture) => {
      if (future.isSuccess) ctx.channel.read
      else future.channel.close
    })
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    cause.printStackTrace()
//    HexDumpProxyFrontendHandler.closeOnFlush(ctx.channel)
  }
}
