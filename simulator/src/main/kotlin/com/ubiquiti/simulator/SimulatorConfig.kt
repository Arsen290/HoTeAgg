package com.ubiquiti.simulator

import java.net.URI
import java.time.Duration

/** Simulator settings. Defaults target a locally running HoTeAgg (endpoint `/ws/telemetry`). */
data class SimulatorConfig(
    val url: URI = URI.create(DEFAULT_URL),
    val devices: Int = 1,
    val devicePrefix: String = "host-",
    val firstIndex: Int = 1,
    val interval: Duration = Duration.ofSeconds(10),
    val duration: Duration? = null,
    val seed: Long? = null,
    val backoffBase: Duration = Duration.ofSeconds(1),
    val backoffMax: Duration = Duration.ofSeconds(30),
) {
    init {
        require(devices in 1..10_000) { "devices must be within 1..10000" }
        require(firstIndex >= 0) { "first-index must be >= 0" }
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(duration == null || !duration.isNegative) { "duration must not be negative" }
        require(url.scheme == "ws" || url.scheme == "wss") { "url must use ws:// or wss://" }
        require(!backoffBase.isZero && backoffMax >= backoffBase) { "invalid backoff bounds" }
    }

    /** Stable, unique IDs: prefix + zero-padded index, e.g. host-0001. */
    fun deviceIds(): List<String> =
        (0 until devices).map { "%s%04d".format(devicePrefix, firstIndex + it) }

    companion object {
        const val DEFAULT_URL = "ws://localhost:8080/ws/telemetry"

        const val USAGE = """Options (all optional):
  --url=ws://localhost:8080/ws/telemetry   backend WebSocket URL
  --devices=1                              number of simulated devices
  --device-prefix=host-                    device ID prefix
  --first-index=1                          index of first device (host-0001)
  --interval-seconds=10                    send interval (decimals allowed)
  --duration-seconds=N                     stop after N seconds (default: run until interrupted)
  --seed=N                                 random seed for reproducible values"""

        private val known = setOf(
            "url", "devices", "device-prefix", "first-index",
            "interval-seconds", "duration-seconds", "seed",
        )

        /** Options come from env vars (SIMULATOR_URL, SIMULATOR_INTERVAL_SECONDS, ...); CLI args override them. */
        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): SimulatorConfig {
            val fromEnv = known.mapNotNull { k ->
                env["SIMULATOR_" + k.uppercase().replace('-', '_')]?.takeIf { it.isNotBlank() }?.let { k to it }
            }.toMap()
            val opts = fromEnv + args.associate {
                require(it.startsWith("--") && it.contains('=')) { "invalid argument '$it' (expected --key=value)" }
                it.removePrefix("--").substringBefore('=') to it.substringAfter('=')
            }
            val unknown = opts.keys - known
            require(unknown.isEmpty()) { "unknown option(s): $unknown" }
            val d = SimulatorConfig()
            return SimulatorConfig(
                url = opts["url"]?.let(URI::create) ?: d.url,
                devices = opts["devices"]?.toInt() ?: d.devices,
                devicePrefix = opts["device-prefix"] ?: d.devicePrefix,
                firstIndex = opts["first-index"]?.toInt() ?: d.firstIndex,
                interval = opts["interval-seconds"]?.let { seconds(it) } ?: d.interval,
                duration = opts["duration-seconds"]?.let { seconds(it) },
                seed = opts["seed"]?.toLong(),
            )
        }

        private fun seconds(v: String): Duration = Duration.ofMillis((v.toDouble() * 1000).toLong())
    }
}

