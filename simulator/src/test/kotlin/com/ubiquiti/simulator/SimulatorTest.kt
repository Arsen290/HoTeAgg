package com.ubiquiti.simulator

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimulatorTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `message JSON matches the backend contract`() {
        val stats = DeviceTelemetry("host-0001", 1).next()
        val json = DeviceTelemetry.toJson(mapper, "host-0001", Instant.parse("2026-10-26T14:03:20Z"), stats)
        val node = mapper.readTree(json)
        assertEquals("stats", node.get("type").asString())
        assertEquals("host-0001", node.get("deviceId").asString())
        assertEquals("2026-10-26T14:03:20Z", node.get("timestamp").asString())
        assertEquals(
            setOf("cpuUtilPct", "memUtilPct", "rxBps", "txBps", "powerW", "temperatureC"),
            node.get("stats").propertyNames().toSet(),
        )
        node.get("stats").properties().forEach { assertTrue(it.value.isNumber, it.key) }
        // Same timestamp parsing the backend uses.
        assertEquals(
            Instant.parse("2026-10-26T14:03:20Z"),
            OffsetDateTime.parse(node.get("timestamp").asString()).toInstant(),
        )
    }

    @Test
    fun `generated values stay in bounds and change gradually`() {
        val gen = DeviceTelemetry("host-0001", 42)
        var prevCpu: Double? = null
        var different = false
        repeat(5000) {
            val s = gen.next()
            val cpu = s.getValue("cpuUtilPct").toDouble()
            assertTrue(cpu in 0.0..100.0)
            assertTrue(s.getValue("memUtilPct").toDouble() in 0.0..100.0)
            assertTrue(s.getValue("rxBps").toLong() >= 0)
            assertTrue(s.getValue("txBps").toLong() >= 0)
            assertTrue(s.getValue("powerW").toDouble() >= 0)
            assertTrue(s.getValue("temperatureC").toDouble() in -273.15..1000.0)
            if (prevCpu != null && prevCpu != cpu) different = true
            prevCpu = cpu
        }
        assertTrue(different)
    }

    @Test
    fun `same seed is reproducible and devices differ`() {
        val a = DeviceTelemetry("host-0001", DeviceTelemetry.seedFor("host-0001", 7))
        val b = DeviceTelemetry("host-0001", DeviceTelemetry.seedFor("host-0001", 7))
        val c = DeviceTelemetry("host-0002", DeviceTelemetry.seedFor("host-0002", 7))
        assertEquals(a.next(), b.next())
        assertNotEquals(DeviceTelemetry("x", DeviceTelemetry.seedFor("host-0001", 7)).next(), c.next())
    }

    @Test
    fun `device IDs are stable and unique`() {
        assertEquals(listOf("host-0001", "host-0002", "host-0003"), SimulatorConfig(devices = 3).deviceIds())
        val big = SimulatorConfig(devices = 500, devicePrefix = "edge-", firstIndex = 10).deviceIds()
        assertEquals(500, big.toSet().size)
        assertEquals("edge-0010", big.first())
    }

    @Test
    fun `config parsing applies options, env and defaults`() {
        val d = SimulatorConfig.parse(emptyArray(), emptyMap())
        assertEquals(URI.create("ws://localhost:8080/ws/telemetry"), d.url)
        assertEquals(Duration.ofSeconds(10), d.interval)
        assertEquals(null, d.duration)

        val c = SimulatorConfig.parse(
            arrayOf(
                "--url=ws://h:9/ws/telemetry", "--devices=4", "--interval-seconds=2.5",
                "--duration-seconds=60", "--seed=5", "--device-prefix=n-", "--first-index=7",
            ),
            emptyMap(),
        )
        assertEquals(4, c.devices)
        assertEquals(Duration.ofMillis(2500), c.interval)
        assertEquals(Duration.ofSeconds(60), c.duration)
        assertEquals(5L, c.seed)
        assertEquals("n-0007", c.deviceIds().first())

        val e = SimulatorConfig.parse(
            arrayOf("--devices=2"),
            mapOf("SIMULATOR_DEVICES" to "9", "SIMULATOR_URL" to "ws://app:8080/ws/telemetry"),
        )
        assertEquals(2, e.devices, "CLI overrides env")
        assertEquals("app", e.url.host)

        assertFailsWith<IllegalArgumentException> { SimulatorConfig.parse(arrayOf("--bogus=1"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SimulatorConfig.parse(arrayOf("--url=http://x"), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SimulatorConfig.parse(arrayOf("--devices=0"), emptyMap()) }
    }

    @Test
    fun `backoff is bounded and exponential with jitter`() {
        val base = Duration.ofSeconds(1)
        val max = Duration.ofSeconds(30)
        val rnd = Random(1)
        repeat(100) {
            assertTrue(DeviceRunner.backoffDelayMs(1, base, max, rnd) in 500..1000)
            assertTrue(DeviceRunner.backoffDelayMs(3, base, max, rnd) in 2000..4000)
            assertTrue(DeviceRunner.backoffDelayMs(50, base, max, rnd) in 15_000..30_000)
        }
    }

    private class FakeLink(val sent: MutableList<String>) : Link {
        @Volatile var closed = false
        override fun send(text: String): CompletableFuture<*> {
            sent += text
            return CompletableFuture.completedFuture(null)
        }
        override fun close() { closed = true }
    }

    private fun runner(connector: Connector, id: String = "host-0001") = DeviceRunner(
        deviceId = id,
        telemetry = DeviceTelemetry(id, 1),
        url = URI.create("ws://localhost/ws/telemetry"),
        interval = Duration.ofMillis(20),
        backoffBase = Duration.ofMillis(10),
        backoffMax = Duration.ofMillis(40),
        connector = connector,
        mapper = mapper,
        clock = Clock.fixed(Instant.parse("2026-10-26T14:03:20Z"), ZoneOffset.UTC),
    )

    private fun awaitTrue(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue(cond(), "condition not met in time")
    }

    @Test
    fun `reconnects after failures and server disconnects, then stops cleanly`() {
        val attempts = AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        val links = CopyOnWriteArrayList<FakeLink>()
        val connector = Connector { _, onClosed, _ ->
            val n = attempts.incrementAndGet()
            if (n == 1) throw java.io.IOException("refused")
            FakeLink(sent).also {
                links += it
                if (n == 2) onClosed(null) // server drops the first successful connection
            }
        }
        val r = runner(connector)
        val t = Thread(r).apply { start() }
        awaitTrue { attempts.get() >= 3 && r.sentCount >= 3 }
        r.stop()
        t.join(5000)
        assertTrue(!t.isAlive)
        assertTrue(links.all { it.closed }, "all links closed")
        assertTrue(sent.all { mapper.readTree(it).get("type").asString() == "stats" })
        assertTrue(r.connectCount >= 2)
    }

    @Test
    fun `write failure drops the connection and retries with backoff`() {
        val attempts = AtomicInteger()
        val link = object : Link {
            override fun send(text: String): CompletableFuture<*> =
                CompletableFuture.failedFuture<Unit>(java.io.IOException("broken pipe"))
            override fun close() {}
        }
        val r = runner(Connector { _, _, _ -> attempts.incrementAndGet(); link })
        val t = Thread(r).apply { start() }
        awaitTrue { attempts.get() >= 3 }
        r.stop()
        t.join(5000)
        assertTrue(!t.isAlive)
        assertEquals(0L, r.sentCount)
    }
}

