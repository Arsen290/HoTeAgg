package com.ubiquiti.simulator

import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** [Connector] backed by the JDK's built-in WebSocket client (no extra dependencies). */
class JdkWebSocketConnector(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val connectTimeout: Duration = Duration.ofSeconds(5),
) : Connector {
    override fun connect(uri: URI, onClosed: (Throwable?) -> Unit, onText: (String) -> Unit): Link {
        val ws = client.newWebSocketBuilder()
            .connectTimeout(connectTimeout)
            .buildAsync(uri, object : WebSocket.Listener {
                private val buf = StringBuilder()
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buf.append(data)
                    if (last) {
                        onText(buf.toString())
                        buf.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    onClosed(null)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) = onClosed(error)
            })
            .get(connectTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS)
        return object : Link {
            override fun send(text: String): CompletableFuture<*> = ws.sendText(text, true)
            override fun close() {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "simulator stopping")
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally { ws.abort(); null }
                    .join()
            }
        }
    }
}

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("simulator")
    val config = try {
        SimulatorConfig.parse(args)
    } catch (e: Exception) {
        System.err.println("Invalid configuration: ${e.message}\n\n${SimulatorConfig.USAGE}")
        exitProcess(2)
    }
    val mapper = JsonMapper.builder().build()
    val connector = JdkWebSocketConnector()
    val runners = config.deviceIds().map { id ->
        DeviceRunner(
            deviceId = id,
            telemetry = DeviceTelemetry(id, DeviceTelemetry.seedFor(id, config.seed)),
            url = config.url,
            interval = config.interval,
            backoffBase = config.backoffBase,
            backoffMax = config.backoffMax,
            connector = connector,
            mapper = mapper,
        )
    }
    log.info(
        "Starting {} device(s) {}..{} -> {} every {} ms, duration={}",
        runners.size, config.deviceIds().first(), config.deviceIds().last(), config.url,
        config.interval.toMillis(), config.duration ?: "unbounded",
    )

    val threads = runners.mapIndexed { i, r -> Thread(r, "device-$i").apply { start() } }
    val done = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        runners.forEach { it.stop() }
        threads.forEach { it.join(5000) }
    })
    config.duration?.let {
        Thread {
            if (!done.await(it.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("Duration elapsed, stopping")
                runners.forEach { r -> r.stop() }
            }
        }.apply { isDaemon = true }.start()
    }
    threads.forEach { it.join() }
    done.countDown()
    // Non-daemon JDK HttpClient threads are daemon by default; explicit exit keeps shutdown prompt.
    exitProcess(0)
}

