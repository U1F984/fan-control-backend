package cc.tietz.fancontrolbackend

import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Serializable
data class NightModeConfig(
    val maxDutyCycle: Int?,
    val startHour: Int?,
    val endHour: Int?,
)

@Serializable
data class PersistentConfig(
    val zipCode: String,
    val pollingRateWeb: Long,
    val pollingRateSensorInside: Long?,
    val pollingRateSensorOutside: Long?,
    val ignoreWindow: Boolean,
    val hysteresisOffset: Double,
    val nightModeConfig: NightModeConfig?,
) {
    companion object {
        val DEFAULT = PersistentConfig(
            "10117",
            5.seconds.toLong(DurationUnit.MILLISECONDS),
            5.seconds.toLong(DurationUnit.MILLISECONDS),
            5.minutes.toLong(DurationUnit.MILLISECONDS),
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