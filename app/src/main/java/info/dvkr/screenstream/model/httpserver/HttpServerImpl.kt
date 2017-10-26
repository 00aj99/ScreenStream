package info.dvkr.screenstream.model.httpserver


import android.support.annotation.Keep
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.HttpServer
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import rx.Observable
import rx.Scheduler
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

class HttpServerImpl constructor(serverAddress: InetSocketAddress,
                                 favicon: ByteArray,
                                 logo: ByteArray,
                                 baseIndexHtml: String,
                                 backgroundColor: Int,
                                 disableMJpegCheck: Boolean,
                                 pinEnabled: Boolean,
                                 pin: String,
                                 basePinRequestHtmlPage: String,
                                 pinRequestErrorMsg: String,
                                 jpegBytesStream: Observable<ByteArray>,
                                 eventBus: EventBus,
                                 private val eventScheduler: Scheduler) : HttpServer {
    private val TAG = "HttpServerImpl"

    companion object {
        private const val NETTY_IO_THREADS_NUMBER = 2
        private const val MAX_HISTORY_SECONDS = 30

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()

            val rxEventLoopProvider = RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
            rxEventLoopProvider.globalServerParentEventLoop().shutdownGracefully()
        }

        internal fun randomString(len: Int): String {
            val chars = CharArray(len)
            val symbols = "0123456789abcdefghijklmnopqrstuvwxyz"
            val random = Random()
            for (i in 0 until len) chars[i] = symbols[random.nextInt(symbols.length)]
            return String(chars)
        }
    }

    // Server internal components
    private val globalServerEventLoop: EventLoopGroup = RxNetty.getRxEventLoopProvider().globalServerEventLoop()
    private val httpServer: io.reactivex.netty.protocol.http.server.HttpServer<ByteBuf, ByteBuf>
    private val httpServerRxHandler: HttpServerRxHandler
    @Volatile private var isRunning: Boolean = false
    private val subscriptions = CompositeSubscription()

    // Clients
    @Keep class LocalClient(clientAddress: InetSocketAddress,
                            var sendBytes: Long = 0,
                            var disconnectedTime: Long = 0) : HttpServer.Client(clientAddress)

    @Keep sealed class LocalEvent {
        @Keep data class ClientConnected(val address: InetSocketAddress) : LocalEvent()
        @Keep data class ClientBytesCount(val address: InetSocketAddress, val bytesCount: Int) : LocalEvent()
        @Keep data class ClientDisconnected(val address: InetSocketAddress) : LocalEvent()
        @Keep data class ClientBackpressure(val address: InetSocketAddress) : LocalEvent()
    }

    private val clientsMap = HashMap<InetSocketAddress, LocalClient>()

    // Traffic
    private val trafficHistory = ConcurrentLinkedDeque<HttpServer.TrafficPoint>()

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Create")

        val httpServerPort = serverAddress.port
        if (httpServerPort !in 1025..65535) throw IllegalArgumentException("Tcp port must be in range [1025, 65535]")

        // Init traffic data
        val past = System.currentTimeMillis() - MAX_HISTORY_SECONDS * 1000
        Observable.range(0, MAX_HISTORY_SECONDS + 1).map { i -> i * 1000 + past }
                .subscribe { trafficHistory.addLast(HttpServer.TrafficPoint(it, 0)) }
        eventBus.sendEvent(EventBus.GlobalEvent.TrafficHistory(trafficHistory.toList()))

        eventBus.getEvent().filter {
            it is EventBus.GlobalEvent.CurrentClientsRequest || it is EventBus.GlobalEvent.TrafficHistoryRequest
        }.subscribe { globalEvent ->
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
            when (globalEvent) {
                is EventBus.GlobalEvent.CurrentClientsRequest -> eventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsMap.values.toList()))
                is EventBus.GlobalEvent.TrafficHistoryRequest -> eventBus.sendEvent(EventBus.GlobalEvent.TrafficHistory(trafficHistory.toList()))
            }
        }.also { subscriptions.add(it) }

        Observable.interval(1, TimeUnit.SECONDS, eventScheduler).subscribe {
            clientsMap.values.removeAll { it.disconnected && System.currentTimeMillis() - it.disconnectedTime > 5000 }
            val trafficPoint = HttpServer.TrafficPoint(System.currentTimeMillis(), clientsMap.values.map { it.sendBytes }.sum())
            trafficHistory.removeFirst()
            trafficHistory.addLast(trafficPoint)
            clientsMap.values.forEach { it.sendBytes = 0 }

            eventBus.sendEvent(EventBus.GlobalEvent.TrafficPoint(trafficPoint))
            eventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsMap.values.toList()))
        }.also { subscriptions.add(it) }

        httpServer = io.reactivex.netty.protocol.http.server.HttpServer.newServer(serverAddress, globalServerEventLoop, NioServerSocketChannel::class.java)
                .clientChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
//                .enableWireLogging(LogLevel.ERROR)

        var indexHtmlPage = baseIndexHtml.replaceFirst(HttpServer.BACKGROUND_COLOR.toRegex(), String.format("#%06X", 0xFFFFFF and backgroundColor))
        if (disableMJpegCheck) indexHtmlPage = indexHtmlPage.replaceFirst("id=mj".toRegex(), "").replaceFirst("id=pmj".toRegex(), "")

        val streamAddress: String
        val pinAddress: String
        if (pinEnabled) {
            streamAddress = "/" + randomString(16) + ".mjpeg"
            indexHtmlPage = indexHtmlPage.replaceFirst(HttpServer.SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)
            pinAddress = HttpServer.DEFAULT_PIN_ADDRESS + pin
        } else {
            streamAddress = HttpServer.DEFAULT_STREAM_ADDRESS
            indexHtmlPage = indexHtmlPage.replaceFirst(HttpServer.SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)
            pinAddress = HttpServer.DEFAULT_PIN_ADDRESS
        }

        val pinRequestHtmlPage = basePinRequestHtmlPage.replaceFirst(HttpServer.WRONG_PIN_MESSAGE.toRegex(), "&nbsp")
        val pinRequestErrorHtmlPage = basePinRequestHtmlPage.replaceFirst(HttpServer.WRONG_PIN_MESSAGE.toRegex(), pinRequestErrorMsg)

        httpServerRxHandler = HttpServerRxHandler(
                favicon,
                logo,
                indexHtmlPage,
                pinEnabled,
                pinAddress,
                streamAddress,
                pinRequestHtmlPage,
                pinRequestErrorHtmlPage,
                Action1 { clientEvent -> toEvent(clientEvent) },
                jpegBytesStream)
        try {
            httpServer.start(httpServerRxHandler)
            isRunning = true
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Started ${httpServer.serverAddress}")
        } catch (exception: Exception) {
            eventBus.sendEvent(EventBus.GlobalEvent.Error(exception))
        }
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsMap.values.toList()))
    }

    override fun stop() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Stop")

        if (isRunning) {
            httpServer.shutdown()
            httpServer.awaitShutdown()
        }
        httpServerRxHandler.stop()
        globalServerEventLoop.shutdownGracefully()
        subscriptions.clear()
        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
        isRunning = false
    }

    fun toEvent(event: LocalEvent) {
        Observable.just(event).observeOn(eventScheduler).subscribe { toEvent ->
            // if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] toEvent: $toEvent")

            when (toEvent) {
                is LocalEvent.ClientConnected -> clientsMap.put(toEvent.address, LocalClient(toEvent.address))
                is LocalEvent.ClientBytesCount -> clientsMap[toEvent.address]?.let { it.sendBytes = it.sendBytes.plus(toEvent.bytesCount) }
                is LocalEvent.ClientDisconnected -> {
                    clientsMap[toEvent.address]?.disconnected = true
                    clientsMap[toEvent.address]?.disconnectedTime = System.currentTimeMillis()
                }
                is LocalEvent.ClientBackpressure -> clientsMap[toEvent.address]?.hasBackpressure = true
            }
        }
    }
}