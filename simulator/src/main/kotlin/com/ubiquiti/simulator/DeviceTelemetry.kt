package com.ubiquiti.simulator

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Random

/**
 * Per-device telemetry generator: mean-reverting random walks with occasional decaying spikes,
 * so consecutive samples are correlated instead of independent.
 */
class DeviceTelemetry(val deviceId: String, seed: Long) {
    private val rnd = Random(seed)

    private val baseCpu = 20 + rnd.nextDouble() * 30
    private val baseMem = 35 + rnd.nextDouble() * 35
    private val baseRx = 1e8 + rnd.nextDouble() * 8e8
    private val baseTx = 5e7 + rnd.nextDouble() * 4e8

    private var cpu = baseCpu
    private var mem = baseMem
    private var rx = baseRx
    private var tx = baseTx
    private var temp = 40.0
    private var spike = 0.0

    fun next(): Map<String, Number> {
        spike *= 0.5
        if (rnd.nextDouble() < SPIKE_PROBABILITY) spike = 20 + rnd.nextDouble() * 40

        cpu = walk(cpu, baseCpu, 0.15, 3.0)
        mem = walk(mem, baseMem, 0.05, 0.8)
        rx = walk(rx, baseRx, 0.2, baseRx * 0.08)
        tx = walk(tx, baseTx, 0.2, baseTx * 0.08)

        val cpuOut = (cpu + spike).coerceIn(0.0, 100.0)
        val memOut = (mem + spike * 0.1).coerceIn(0.0, 100.0)
        val rxOut = (rx * (1 + spike / 40)).coerceAtLeast(0.0)
        val txOut = (tx * (1 + spike / 60)).coerceAtLeast(0.0)

        val tempTarget = 35 + cpuOut * 0.3
        temp = walk(temp, tempTarget, 0.2, 0.4).coerceIn(15.0, 95.0)
        val power = (120 + cpuOut * 2.2 + rnd.nextGaussian() * 3).coerceAtLeast(0.0)

        return linkedMapOf(
            "cpuUtilPct" to round1(cpuOut),
            "memUtilPct" to round1(memOut),
            "rxBps" to rxOut.toLong(),
            "txBps" to txOut.toLong(),
            "powerW" to round1(power),
            "temperatureC" to round1(temp),
        )
    }

    private fun walk(value: Double, mean: Double, reversion: Double, sigma: Double) =
        value + (mean - value) * reversion + rnd.nextGaussian() * sigma

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        const val SPIKE_PROBABILITY = 0.03

        /** Derives a distinct, reproducible per-device seed from the global seed. */
        fun seedFor(deviceId: String, globalSeed: Long?): Long =
            if (globalSeed == null) System.nanoTime() xor deviceId.hashCode().toLong()
            else globalSeed * 31 + deviceId.hashCode()

        /** Serializes a message in the format HoTeAgg expects; timestamp is ISO-8601 UTC. */
        fun toJson(mapper: JsonMapper, deviceId: String, timestamp: Instant, stats: Map<String, Number>): String =
            mapper.writeValueAsString(
                linkedMapOf(
                    "type" to "stats",
                    "deviceId" to deviceId,
                    "timestamp" to timestamp.toString(),
                    "stats" to stats,
                ),
            )
    }
}

