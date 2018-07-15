# 第四届阿里中间件性能大赛初赛攻略——RDP飞起来

## 赛题背景分析及理解

初赛题目是吸引我参加比赛的最大原因。其中一段描述了Service Mesh的作用:

> 作为 Service Mesh 的核心组件之一，高性能的服务间代理（Agent）是不可或缺的，其可以做到请求转发、协议转换、服务注册与发现、动态路由、负载均衡、流量控制、降级和熔断等诸多功能，也是区别于传统微服务架构的重要特征。

而这种思想与《反应式设计模式》不约而同。在反应式系统设计的过程中，很重要的一块就是如何与现存的非反应式系统进行交互。非反应式系统典型地都具有同步阻塞调用者、无界输入队列、不遵循有界响应延迟的原则等缺点，这使得流量控制、资源高效利用以及降级、熔断等功能都比较难以实现。《反应式设计模式》一书中专门推荐了要使用单独的资源来与这些系统整合，并赋予他们“反应式”的假象。而Service Mesh中的Agent，则可以看作成专门用来与非反应式系统进行整合的组件。在第14章的资源管理模式中，描述了如何使用这样的资源与之进行交互的方法，尤其是托管阻塞模式；而第16章的流量控制模式，则指导了我们如何在调用过程中行之有效进行流量控制。当然，对于比赛来说，这些设计相对来说过于概括。不过我们可以先基于这种概括性地原则构建出体系架构来，之后我们再具体优化相关的细节，提高成绩。而基于之前描述的原因，我的第一版使用了Akka来进行开发。接下来我们先分析一下具体的题目。

### 题目分析

题目的要求是：

>实现一个高性能的 Service Mesh Agent 组件，并包含如下一些功能：
> 1.  服务注册与发现
> 2.  协议转换 
> 3.  负载均衡 

服务注册与发现是为了获得资源的访问方式。这个过程最好不要与正式的调用过程耦合。所以我们用一个单独的Actor来做服务发现。如果是在Consumer中，这个Actor会去监听ETCD的变更，如果发现Endpoints发生了变化，则将信息发布到ActorSystem的事件流中。之后关注`EndpointsUpdated`事件的Actor就会收到此消息，并根据它来更新自己的端点列表，进行负载的动态变更。

协议转换相对来说是一个打铁的活，根据Dubbo协议一点点写好就行了。

重要的则在于负载均衡。进一步回到题目描述中：

> 1.  每轮评测在一台服务器中启动五个服务（以 Docker 实例的形式启动），一个 etcd 服务作为注册表、一个 Consumer 服务和三个 Provider 服务；
>  2.  使用一台独立的施压服务器，分不同压力场景对 Consumer 服务进行压力测试，得到 QPS；

总共有3轮压力测试，分别是128、256、512个连接。由于每次请求的往返时间最少也是50ms，那么每秒钟，按照512连接的最大速度，则是1000 / 50 * 512 = 10240的最大QPS。

其中，三台Provider的负载能力有所不同，按照CPU的quota分配以及内存的大小分配，正常情况下应该是1比2比3。只是由于Provider的dubbo端最多同时只能处理200个请求，多出来的直接被reject掉。那么最好的分配比例在512条件下则是 `112 : 200 : 200`。

当然，反应式系统的设计原则并不是固定分配比例的。它希望的理想情形是`你先告诉我你能处理多少任务，一旦任务来了，我就尽量按照这个数量发给你`。不要Consumer去强行推，不要Provider一直来拉。而这种模式最好的实现方式，就是利用Akka Stream啦。

## 核心思路

按照前面的分析，核心思路就是将每个Provider的处理过程看作是一条流。来自调用端的所有请求先汇聚到一个队列里面，之后根据后端Provider的处理能力，分别分配到三个不同的流中。而如果汇聚队列的长度达到了界限值，则降级服务，对外部请求进行按比例丢弃，直到与系统的处理能力重新匹配（详情参见《反应式设计模式》第十六章丢弃模式）。这样整个系统就又健壮又迅速。

## 关键代码

下面一段是用来抽象Consumer的Actor里面的代码，所有连接的请求都被注册到RequestHandler这个Actor了。

```scala
  val requestHandler: ActorRef = context.actorOf(Props(new RequestHandler).withDispatcher("mpsc"), "request-handler")

  def receive: Receive = {
    case Bound(localAddress) ⇒
      etcdManager ! "consumer"
      log.info(s"service started at ${localAddress.getHostString}:${localAddress.getPort}")
    case CommandFailed(_: Bind) ⇒
      context stop self
    case Connected(_, _) ⇒
      val connection = sender()
      connection ! Register(requestHandler) 
      //将Connection全部注册到RequestHandler，就是说所有连接发过来的数据都回转发到这个Actor
      //注意这个是Hack写法。正统的还是应该一个Actor一个连接，这样逻辑才会清晰。
  }
```

然后在`Requesthandler`里面，接收到的`ByteString`直接作为元素提供给后面的处理流代码里面。

```scala
  var source: SourceQueueWithComplete[(Long, ByteString)] = _
  
  override def receive: Receive = {
    case Received(bs) =>
      source.offer(sender().path.name.toLong, bs) //这里的sender是处理连接的actor，它们的名字刚刚好是ID，所以直接复用。
    case EndpointsUpdate(newEndpoints) =>
      log.info(s"start new source for endpoints $newEndpoints")
      source.complete()
      source = getSourceByEndpoints(newEndpoints)
```

这里的`source`是一个可完成`Source`。`Source`, `Flow`, `Sink`是Akka Stream里面的基本构建块。其大体意义如下：

1. Source: 只有一个输出流的构件块;
2. Sink: 只接收一个输入流的构件块;
3. Flow: 接收一个输入流，并拥有一个输出流的构件块。
4. Graph: 一个打包好的流处理拓扑，它可以拥有一组输入端口或者输出端口。

我们这里是一个可完成`Source`，它由`Source.queue`声明并物化后产生：

```scala
  def getSourceByEndpoints(endpoints: Set[Endpoint]): SourceQueueWithComplete[(Long, ByteString)] = {
    val handleFlow = Flow[(Long, ByteString)]
      .via(DubboFlow.connectionIdFlow)
      .via(endpointsFlow(endpoints))
      .to(DubboFlow.decoder)
      
    Source.queue[(Long, ByteString)](512, OverflowStrategy.backpressure)
      .to(handleFlow).run()
  }
```

这里是由这个函数基于`Endpoint`的个数构建。第一段`handleFlow`是构建了一个`Flow`，这个`Flow`可以接收一个二元组`(Long, ByteString)`，并将其交给`DubboFlow.connectionIdFlow`来encode成自定义协议，之后将其发送到`endpointsFlow`进行对Provider的调用，并得到结果。得到结果之后，经由`DubboFlow.decoder`来decode，并发送回给各个连接Actor，由其返回给客户端。

上面的内容里面，`DubboFlow.connectionIdFlow`和`DubboFlow.decoder`不多说，都是打铁代码。核心逻辑`endpointsFlow(endPoints)`贴出如下：

```scala
  def endpointsFlow(endpoints: List[Endpoint]) = {
    
    val tcpFlows = endpoints.map { endpoint =>
      Tcp().outgoingConnection(endpoint.host, endpoint.port).async
    }
    
    val framing = Framing.lengthField(4, 12, Int.MaxValue, ByteOrder.BIG_ENDIAN)

    Flow.fromGraph(GraphDSL.create(tcpFlows) { implicit builder =>
      tcpFlows =>
        import GraphDSL.Implicits._
        
        val balance = builder.add(Balance[ByteString](tcpFlows.size))
        val bigMerge = builder.add(Merge[(Long, ByteString)](tcpFlows.size))
        tcpFlows.foreach { tcp =>
          balance  ~> tcp ~> framing ~> bigMerge
        }
        
        FlowShape(balance.in, bigMerge.out)
    })
  }
```

每一个`endpoint`都被映射成为一个`Tcp`的`Flow`，通往Provider端。之后使用Akka Stream的DSL方法，构建了一个`Graph`。这个`Graph`用图形表示，其拓扑结果则是如下：

```
                      +------> Small Provider +--------> Framing +-------+
                      |                                                  |
    Input             |                                                  |                Output
+--------> Balancer ---------> Medium Provider+--------> Framing +----------------> Merge +-------->
                      |                                                  |
                      |                                                  |
                      +------> Large Provider +--------> Framing +-------+
```

数据由左边输入，经过Balancer，这个Balancer是由Akka Stream提供的现成组件，它可以将上游的元素路由到下游，其特性如下：

1. 一个`Balance`由一个`in`端口和2到多个`out`端口，
2. 当任意下游端口停止回压之后，它输出元素到下游输出端口；
3. 当下游所有端口都在回压的时候，它就回压上游；
4. 当上游完成时，它也完成；
5. 当其`eagerCancel`参数设置为`true`时，任意下游取消，则其也取消；设置为`false`的时候，当所有下游取消，它才取消。

由上面的拓扑结构可以看到，当任意Provider向上游表示可以处理请求的时候，Balancer就会在有请求到来的时候，向其输出；Provider处理完的请求，经过TCP拆包过程之后，就合并到一起，交由下游的流继续处理。如此，只要连接有请求过来，那么整个流就能一直运转。这个过程中，即使某个通往provider的连接断掉了，Balancer也能继续将请求路由到其他两个连接上。而这个时候，负责服务发现的Actor就会发出`EndpointsUpdated`的消息，此时`RequestHandler`会进入第二个匹配，用新的Endpoint来更新我们的处理流：

```scala
    case EndpointsUpdate(newEndpoints) =>
      log.info(s"start new source for endpoints $newEndpoints")
      source.complete()
      source = getSourceByEndpoints(newEndpoints)
```

注意这里的`complete`是表示流不再接收新的请求，这之前已经入队的请求仍然会继续完成，直到全部处理完毕。

Provider的代码相对Consumer就简单很多：

```scala
  val handleFlow = Tcp().outgoingConnection(host, dubboPort).async

  def startService: Future[Done] = {
    Tcp().bind(host, port).runForeach { conn =>
      conn.handleWith(handleFlow)
    }
  }
```

它只需要将Consumer过来的连接转发给后端的Dubbo，或者为了性能原因，它需要将自定义协议包装成Dubbo协议，然后发过去，再将结果转回，即可。

到这里，我们用了大约不到300行代码，就完成了初赛题目的所有要求。并且代码的普适性和健壮性都很不错，后续还能依据需求，快速地实现任意一端的限流要求(`Flow[Request].throttle(...)`)，或者加入断路器，进行快速失败。

这套代码在CPU资源充足的时候，例如在我本地(注意，已经按照docker参数限定了CPU quota和内存)，256连接的时候可以跑4960, 512的时候可以跑9500。

然而线上则表现不好，分别最多4500和6400。这是为什么呢？

经过查询源码以后发现，问题出现在这一段：

```scala
@tailrec def innerRead(buffer: ByteBuffer, remainingLimit: Int): ReadResult =
        if (remainingLimit > 0) {
          // never read more than the configured limit
          buffer.clear()
          val maxBufferSpace = math.min(DirectBufferSize, remainingLimit)
          buffer.limit(maxBufferSpace)
          val readBytes = channel.read(buffer)
          buffer.flip()

          if (TraceLogging) log.debug("Read [{}] bytes.", readBytes)
          if (readBytes > 0) info.handler ! Received(ByteString(buffer)) //这一段

          readBytes match {
            case `maxBufferSpace` ⇒ if (pullMode) MoreDataWaiting else innerRead(buffer, remainingLimit - maxBufferSpace)
            case x if x >= 0      ⇒ AllRead
            case -1               ⇒ EndOfStream
            case _ ⇒
              throw new IllegalStateException("Unexpected value returned from read: " + readBytes)
          }
        } else MoreDataWaiting
```

其中`info.handler ! Received(ByteString(buffer))`是将`SocketChannel`接收到的数据复制成`ByteString`类型之后，再发送出去的，所以相当于是从堆外把数据复制了出去，于是导致整个流程都是非zero copy的。本来在正常的逻辑下，不ZC是必然的，因为肯定要把数据读出来进行处理。但是在本次比赛的场景里，这种复制就是非常昂贵的操作了，直接导致Akka版本的代码无法和各位竞争，即使代码再精简，思想再先进，也无法取得好的成绩。所以在第二个版本中，我换用了netty来跑分。

Netty版本下的核心代码，分ConsumerAgent和调用PrivderAgent的NettyClient列出如下：

ConsumerAgent:
```scala
  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    msg match {
      case in: ByteBuf =>
        val meshRequest = MeshRequest(cid) //cid是connectionId,在连接建立的时候获取
        val maybeClient = ClientChannelHolder.clientChannelCache.get() //client的channel存在了ThreadLocal里面，直接通过ThreadLocal获取到channelHandler
        maybeClient.writeAndFlush(meshRequest.toCustomProtocol(in), maybeClient.voidPromise()) //将流入的bytebuffer转变成自定义协议的格式，并直接向client的channel刷入数据
        meshRequest.recycle //回收MeshRequest对象
    }
  }
 
```

NettyClient:
```scala
override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
          msg match {
            case bs: ByteBuf =>
              val cid = bs.getLong(4) //从ByteBuffer中获取connectionId
              val resp = MeshResponse(cid) //包装成MeshResponse
              val ch = ServerChannelHolder.serverChannelMap.get().get(cid) //根据connectionId获取这个连接的SocketChannel
              if(ch != null) { //如果存在的话，则刷入响应
                ch.writeAndFlush(resp.toHttpResponse(bs), ch.voidPromise())
              }
              resp.recycle //回收MeshResponse对象
          }
        }
```

这个是我所发现的最短的路线。其中省略了路由的过程。整体的线程设置如下：

```scala
  val acceptorGroup = new EpollEventLoopGroup(1)
  val threadFactory = new DefaultThreadFactory("atf_wrk")
  val workerNumber = 3
  val workerGroup = new EpollEventLoopGroup(workerNumber, threadFactory)
```
一个负责IO的线程，三个负责处理请求的线程。三个NettyClient分别使用三个worker中的一个就好了：

```scala
  val eventLoop = workerGroup.next()
  new NettyClient(ed.host, ed.port, eventLoop, 1, ed.scale)
```

主要的trick就是我只起了4个线程，1个负责IO，3个负责请求处理。通过连接绑定的线程来进行路由，所以少了很多人加权轮询的步骤，而且每个连接只通过同一个线程进行流转，所以也少了context switch的过程。情况好的话，4个线程应该pin到它们的cpu上，没有任何的上下文切换。

至于其他就是一些打铁的小细节，比如使用Recycler生成对象池来回收对象，使用池化的ByteBuf来避免堆外内存分配的开销，预先定义好一些要用来包装请求和回复的对象，使用`Unpooled.unreleasableBuffer(buffer)`来反复利用。如此，整个过程下来，不会有FGC，而YGC最多也就两三次而已。Recycler的代码列出如下：

```scala
class MeshRequest private(handle: Handle[MeshRequest]) {
  private var cid: Long = _
  private var buffer: ByteBuf = _
  private var composite: CompositeByteBuf = _

  def recycle = {
    cid = 0l
    buffer = null
    composite = null
    handle.recycle(this)
  }

  def toCustomProtocol(bb: ByteBuf) = {
    val n = bb.indexOf(280, bb.readableBytes(), '='.toByte)
    val parameter = bb.skipBytes(n + 1)
    buffer.writeLong(cid)
    buffer.writeInt(parameter.readableBytes())
    composite.addComponents(true, buffer, parameter)
  }

}

object MeshRequest {
  private val RECYCLER = new Recycler[MeshRequest]() {
    override def newObject(handle: Recycler.Handle[MeshRequest]): MeshRequest = {
      new MeshRequest(handle)
    }
  }

  def apply(id: Long): MeshRequest = {
    val request = RECYCLER.get()
    request.cid = id
    request.buffer = ConsumerAgent.allocator.directBuffer(12)
    request.composite = ConsumerAgent.allocator.compositeBuffer()
    request
  }
}
```

最终，Netty版本的代码停留在6894，而Akka版本我没记错的话，应该是6400左右。

## 比赛经验总结和感想

其实是第一次参加这种编程的比赛，开始的时候看得蛮轻，因为按照实际生产的场景来说，我的第一种方案肯定是非常好的，编码简单、健壮、可扩展性强，应该是能够出彩的。但是因为比赛是唯成绩论的，或者说至少在初赛和复赛的时候是唯成绩论的，所以后续不得已，只能放弃我对Akka的信仰，使用Netty写了一个版本的打铁代码，以往前冲击一个比较好的名次，然后来向大家吹嘘Akka。事实证明，限定场景来做极致优化的话，Netty确实好很多，不过，在通用场景下，用Akka stream的思想，则可以迅速构建出一个集各种流控功能于一体，也非常好扩展，并且性能也不会相差太多的组件。所以，不管怎样，到最终的总结还是，如果是我来开发这个Service Mesh组件，Akka和Akka Stream绝对会是主力，而Netty则可以被应用在不需要将数据读出内存的场景(如只负责转发或者解析自定义协议的Provider端)。两者相结合，应该可以达到比较好的平衡。


