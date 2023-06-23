package cc.tietz.fancontrolbackend

import kotlinx.serialization.Serializable


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
