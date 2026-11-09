package dev.kigawa.klestra

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
sealed class ElectionPolicy {
    /** All durations are milliseconds; each election draws a fresh timeout. */
    @Serializable
    @SerialName("Automatic")
    data class Automatic(
        val minTimeoutMillis: Long = 300,
        val maxTimeoutMillis: Long = 600,
        val heartbeatIntervalMillis: Long = 100,
    ) : ElectionPolicy() {
        init {
            require(minTimeoutMillis > 0) { "Election timeout must be positive" }
            require(maxTimeoutMillis >= minTimeoutMillis && maxTimeoutMillis < Long.MAX_VALUE)
            require(heartbeatIntervalMillis in 1 until minTimeoutMillis) {
                "Heartbeat interval must be shorter than the minimum election timeout"
            }
        }

        fun nextTimeoutMillis(random: Random = Random.Default): Long =
            random.nextLong(minTimeoutMillis, maxTimeoutMillis + 1)
    }
}
