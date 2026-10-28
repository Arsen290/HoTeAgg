package com.ubiquiti.simulator

import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** An open connection to the backend. */
interface Link {
    fun send(text: String): CompletableFuture<*>
    fun close()
}

/** Opens a connection; throws on failure. [onClosed] fires once when the connection ends. */
fun interface Connector {
    fun connect(uri: URI, onClosed: (Throwable?) -> Unit, onText: (String) -> Unit): Link
}

/**
 * Runs one simulated device on a single thread: connect, send every interval, and on any
 * failure reconnect with bounded exponential backoff + jitter. One loop per device means
 * there can never be concurrent reconnect loops for it.
 */
class DeviceRunner(
    private val deviceId: String,
    private val telemetry: DeviceTelemetry,
    private val url: URI,
    private val interval: Duration,
    private val backoffBase: Duration,
    private val backoffMax: Duration,
    private val connector: Connector,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val jitter: Random = Random(),
    private val sendTimeout: Duration = Duration.ofSeconds(5),
) : Runnable {
    private val log = LoggerFactory.getLogger("simulator.$deviceId")

    @Volatile
    var sentCount = 0L
        private set

    @Volatile
    var connectCount = 0
        private set

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    override fun run() {
        thread = Thread.currentThread()
        var failures = 0
        try {
            while (!stopped) {
                val closed = CompletableFuture<Throwable?>()
                val link = try {
                    log.info("Connecting to {}", url)
                    connector.connect(url, { closed.complete(it) }, { onAck(it) })
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failures++
                    log.warn("Connection failed (attempt {}): {}", failures, e.toString())
                    if (!pause(backoffDelayMs(failures, backoffBase, backoffMax, jitter))) break
                    continue
                }
                connectCount++
                log.info("Connected")
                val sentOk = session(link, closed)
                if (sentOk) failures = 0
                if (stopped) break
                failures++
                val delay = backoffDelayMs(failures, backoffBase, backoffMax, jitter)
                log.info("Reconnecting in {} ms", delay)
                if (!pause(delay)) break
            }
        } finally {
            log.info("Stopped after {} messages", sentCount)
        }
    }

    /** Sends until the connection drops, a write fails or we are stopped. Returns true if any send succeeded. */
    private fun session(link: Link, closed: CompletableFuture<Throwable?>): Boolean {
        var anyOk = false
        try {
            while (!stopped && !closed.isDone) {
                val json = DeviceTelemetry.toJson(mapper, deviceId, clock.instant(), telemetry.next())
                try {
                    link.send(json).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    sentCount++
                    anyOk = true
                    log.debug("Sent message #{} ({} bytes)", sentCount, json.length)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Send failed, dropping connection: {}", e.toString())
                    return anyOk
                }
                try {
                    val reason = closed.get(interval.toMillis(), TimeUnit.MILLISECONDS)
                    log.warn("Server closed connection{}", reason?.let { ": $it" } ?: "")
                    return anyOk
                } catch (_: TimeoutException) {
                    // still open: next tick
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { link.close() }
        }
        return anyOk
    }

    private fun onAck(text: String) {
        val status = runCatching { mapper.readTree(text).get("status")?.asString() }.getOrNull()
        if (status == null || status == "PERSISTED") log.debug("Ack: {}", status)
        else log.warn("Backend replied {}: {}", status, text.take(300))
    }

    /** Sleeps; returns false if interrupted or stopped. */
    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        !stopped
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
    }

    companion object {
        /** Exponential backoff capped at [max], with jitter in the upper half: [cap/2, cap]. */
        fun backoffDelayMs(attempt: Int, base: Duration, max: Duration, random: Random): Long {
            val shift = (attempt - 1).coerceIn(0, 20)
            val cap = minOf(base.toMillis() * (1L shl shift), max.toMillis())
            return cap / 2 + (random.nextDouble() * (cap - cap / 2)).toLong()
        }
    }
}

