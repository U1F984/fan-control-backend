package cc.tietz.fancontrolbackend

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant

object WeatherApi {

    private var weatherResponseCached: WeatherResponse? = null
    private var lastFetch: Instant? = null

    private suspend fun readImpl(): WeatherResponse? = runCatching {
        val key = System.getenv("weather_key") ?: return null
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

        return WeatherResponse(current, forecast)
    }.onFailure { it.printStackTrace() }.getOrNull()

    suspend fun read(): WeatherResponse? {
        val cached = weatherResponseCached
        if (cached != null && lastFetch?.let { Duration.between(it, Instant.now()) < Duration.ofMinutes(10) } == true) {
            return cached
        }
        lastFetch = Instant.now()
        weatherResponseCached = readImpl()
        return weatherResponseCached
    }
}