package cc.tietz.fancontrolbackend

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class NightModeConfig(
    val maxDutyCycle: Int?,
    val startHour: Int?,
    val endHour: Int?,
)

@Serializable
data class PersistentConfig(
    val zipCode: String,
    val darkMode: Boolean,
    val pollingRateWeb: Duration,
    val pollingRateSensorInside: Duration?,
    val pollingRateSensorOutside: Duration?,
    val ignoreWindow: Boolean,
    val hysteresisOffset: Double,
    val nightModeConfig: NightModeConfig?,
) {
    companion object {
        val DEFAULT = PersistentConfig(
            "10117",
            false,
            5.seconds,
            5.seconds,
            5.minutes,
            false,
            2.0,
            NightModeConfig(
                70,
                22,
                6,
            )
        )
    }
}