package cc.tietz.fancontrolbackend

import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::myApplicationModule).start(wait = true)
}

@Serializable
data class OutdoorSensorRequest(
    val temperature: Double,
    val relativeHumidity: Double,
    val battery: Double,
)

@Serializable
data class OutdoorSensorResponse(
    val sleepDurationMilliseconds: Int,
)

@Serializable
data class IndoorSensorRequest(
    val temperature: Double,
    val relativeHumidity: Double,
    val windowOpen: Boolean,
)

@Serializable
data class IndoorSensorResponse(
    val sleepDurationMilliseconds: Int,
    val fanDutyCycle: Int,
)

data class DateRange(
    val start: Instant,
    val end: Instant,
) {
    fun toRange(): ClosedRange<Instant> = start..end
}

data class FetchRequest(
    val limit: Int?,
    val dateRange: DateRange?,
) {
    companion object {
        fun fromParameters(parameters: Parameters): FetchRequest {
            val limit = parameters["limit"]?.toIntOrNull()
            val start = parameters["start"]?.let(Instant::parse)
            val end = parameters["end"]?.let(Instant::parse)

            val dateRange = if (start != null && end != null) DateRange(start, end) else null
            return FetchRequest(limit, dateRange)
        }
    }
}

@Serializable
data class IndoorFetchResponse(
    val data: List<Measurement>,
) {
    @Serializable
    data class Measurement(
        val date: String,
        val temperature: Double,
        val relativeHumidity: Double,
        val absoluteHumidity: Double,
        val windowOpen: Boolean,
    )
}

@Serializable
data class OutdoorFetchResponse(
    val data: List<Measurement>,
) {
    @Serializable
    data class Measurement(
        val date: String,
        val temperature: Double,
        val relativeHumidity: Double,
        val absoluteHumidity: Double,
        val battery: Double,
    )
}

@Serializable
data class City(
    val id: Int,
    val name: String,
    val coord: Coord,
    val country: String,
    val population: Int,
    val timezone: Int,
    val sunrise: Long,
    val sunset: Long
)

@Serializable
data class Clouds(
    val all: Int,
)

@Serializable
data class Coord(
    val lon: Double,
    val lat: Double,
)

@Serializable
data class ForecastData(
    val cod: String,
    val message: Int,
    val cnt: Int,
    val list: List<WeatherItem>,
    val city: City
)

@Serializable
data class ForecastSys(
    val pod: String
)

@Serializable
data class Rain(
    val `1h`: Double,
)

@Serializable
data class Weather(
    val id: Int,
    val main: String,
    val description: String,
    val icon: String,
)

@Serializable
data class WeatherData(
    val coord: Coord,
    val weather: List<Weather>,
    val base: String,
    val main: WeatherMain,
    val visibility: Int,
    val wind: Wind,
    val rain: Rain?,
    val clouds: Clouds,
    val dt: Long,
    val sys: WeatherSys,
    val timezone: Int,
    val id: Int,
    val name: String,
    val cod: Int,
)

@Serializable
data class WeatherItem(
    val dt: Long,
    val main: WeatherMain,
    val weather: List<Weather>,
    val clouds: Clouds,
    val wind: Wind,
    val visibility: Int,
    val pop: Double,
    val rain: Rain?,
    val sys: ForecastSys,
    val dt_txt: String
)

@Serializable
data class WeatherMain(
    val temp: Double,
    val feels_like: Double,
    val temp_min: Double,
    val temp_max: Double,
    val pressure: Int,
    val humidity: Int,
    val sea_level: Int,
    val grnd_level: Int,
)

@Serializable
data class WeatherSys(
    val type: Int,
    val id: Int,
    val country: String,
    val sunrise: Long,
    val sunset: Long,
)

@Serializable
data class Wind(
    val speed: Double,
    val deg: Int,
    val gust: Double,
)

@Serializable
data class WeatherResponse(
    val current: WeatherData,
    val forecast: ForecastData,
)

@Serializable
data class SwitchStateResponse(
    val state: Boolean,
)

private const val defaultLimit = 1000

fun Application.myApplicationModule() {
    Database.initSchema()
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/frontend/config") {
            call.respond(Database.loadConfig())
        }
        post("/frontend/config") {
            Database.saveConfig(call.receive<PersistentConfig>())
            call.respond(Database.loadConfig())
        }
        get("/outdoor") {
            val req = call.parameters.let(FetchRequest::fromParameters)
            val data = Database.loadOutdoor(req.dateRange?.toRange(), req.limit ?: defaultLimit)
            call.respond(OutdoorFetchResponse(data.map {
                OutdoorFetchResponse.Measurement(
                    it.time.toString(),
                    it.temperature,
                    it.relativeHumidity,
                    it.battery,
                    calculateAbsoluteHumidity(it.temperature, it.relativeHumidity),
                )
            }))
        }
        post("/outdoor") {
            val req = call.receive<OutdoorSensorRequest>()
            Database.saveOutdoor(
                Database.OutdoorMeasurement(
                    Instant.now(),
                    req.temperature,
                    req.relativeHumidity,
                    req.battery
                )
            )
            // todo: compute delay based on outdoor weather information
            val delay = Database.loadConfig().pollingRateSensorOutside ?: 5.seconds
            call.respond(OutdoorSensorResponse(delay.inWholeMilliseconds.toInt()))
        }
        get("/indoor") {
            val req = call.parameters.let(FetchRequest::fromParameters)
            val data = Database.loadIndoor(req.dateRange?.toRange(), req.limit ?: defaultLimit)
            call.respond(IndoorFetchResponse(data.map {
                IndoorFetchResponse.Measurement(
                    it.time.toString(),
                    it.temperature,
                    it.relativeHumidity,
                    calculateAbsoluteHumidity(it.temperature, it.relativeHumidity),
                    it.windowOpen,
                )
            }))
        }
        get("/weather") {
            val key = System.getenv("weather_key")
            if (key.isNullOrEmpty()) {
                call.respond(500)
            } else {
                val zipCode = Database.loadConfig().zipCode

                val reqCurrentWeather = Request.Builder()
                    .url("https://api.openweathermap.org/data/2.5/weather?zip=${zipCode},DE&appid=${key}&units=metric")
                    .build()
                val reqForecast = Request.Builder()
                    .url("https://api.openweathermap.org/data/2.5/forecast?zip=${zipCode},DE&appid=${key}&cnt=3&units=metric")
                    .build()

                val client = OkHttpClient()

                val resCurrentWeather = client.newCall(reqCurrentWeather).execute()
                val resForecast = client.newCall(reqForecast).execute()

                val gson = Gson()
                val current = gson.fromJson(resCurrentWeather.body?.string(), WeatherData::class.java)
                val forecast = gson.fromJson(resForecast.body?.string(), ForecastData::class.java)

                call.respond(WeatherResponse(current, forecast))
            }
        }
        val lastSwitchValue = AtomicBoolean()
        post("/indoor") {
            val req = call.receive<IndoorSensorRequest>()
            Database.saveIndoor(
                Database.IndoorMeasurement(
                    Instant.now(),
                    req.temperature,
                    req.relativeHumidity,
                    req.windowOpen
                )
            )
            val config = Database.loadConfig()
            val delay = config.pollingRateSensorInside
            val lastOurdoorMeasurement = Database.loadOutdoor(timeRange = null, limit = 1).lastOrNull()
            val fanDutyCycle = when {
                req.windowOpen && !config.ignoreWindow -> 0
                lastOurdoorMeasurement == null -> 0
                shouldEnable(
                    config,
                    lastSwitchValue.get(),
                    calculateAbsoluteHumidity(req.temperature, req.relativeHumidity),
                    calculateAbsoluteHumidity(
                        lastOurdoorMeasurement.temperature,
                        lastOurdoorMeasurement.relativeHumidity
                    )
                ) -> 100

                else -> 0
            }
            val maxFanDutyCycle = getMaxDutyCycleIfNight(config.nightModeConfig)
            val finalFanDutyCycle = maxFanDutyCycle?.let { fanDutyCycle.coerceAtMost(it) } ?: fanDutyCycle
            lastSwitchValue.set(finalFanDutyCycle > 0)
            call.respond(IndoorSensorResponse(delay.inWholeMilliseconds.toInt(), finalFanDutyCycle))
        }
        get("/switchState") {
            call.respond(SwitchStateResponse(lastSwitchValue.get()))
        }
    }
}

private fun calculateAbsoluteHumidity(temperature: Double, relativeHumidity: Double): Double {
    val saturationVaporPressure = 6.112 * exp((17.67 * temperature) / (temperature + 243.5))
    val actualVaporPressure = relativeHumidity * saturationVaporPressure / 100.0
    return 217 * actualVaporPressure / (temperature + 273.15)
}

private fun shouldEnable(
    config: PersistentConfig,
    lastSwitchValue: Boolean,
    insideAbsoluteHumidity: Double,
    outsideAbsoluteHumidity: Double
): Boolean {
    val offset = (insideAbsoluteHumidity - outsideAbsoluteHumidity).absoluteValue
    if (offset < config.hysteresisOffset) return lastSwitchValue
    return insideAbsoluteHumidity > outsideAbsoluteHumidity
}

fun getMaxDutyCycleIfNight(config: NightModeConfig): Int? {
    val currentHour = LocalTime.now().hour
    val startHour = config.startHour ?: return null
    val endHour = config.endHour ?: return null

    return config.maxDutyCycle?.takeIf {
        if (startHour <= endHour) currentHour in startHour..endHour
        else currentHour >= startHour || currentHour <= endHour
    }
}