package cc.tietz.fancontrolbackend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.impl.DSL
import java.time.Instant

object Database {
    suspend fun <T> transaction(fn: (DSLContext) -> T): T = withContext(Dispatchers.IO) {
        DSL.using("jdbc:sqlite:db.sqlite").use { ctx ->
            ctx.transactionResult(TransactionalCallable { fn(ctx) })
        }
    }

    fun initSchema() {
        runBlocking {
            transaction { ctx ->
                ctx.execute(
                    """
                        CREATE TABLE IF NOT EXISTS config (
                            config TEXT NOT NULL
                        )
                    """.trimIndent()
                )
                ctx.execute(
                    """
                        CREATE TABLE IF NOT EXISTS outdoor_measurement (
                            time INT NOT NULL,
                            temperature REAL NOT NULL,
                            rel_humidity INT NOT NULL,
                            PRIMARY KEY (time)
                        );
                    """.trimIndent()
                )
            }
        }
    }

    suspend fun loadConfig(): PersistentConfig = transaction { ctx ->
        ctx
            .fetchOne("SELECT config FROM config")
            ?.get("config", String::class.java)
            ?.let(Json.Default::decodeFromString)
            ?: PersistentConfig.DEFAULT
    }

    suspend fun saveConfig(persistentConfig: PersistentConfig) {
        transaction { ctx ->
            @Suppress("SqlWithoutWhere") // deliberate: there should only be one entry
            ctx.execute("DELETE FROM config")
            ctx.execute("INSERT INTO config(config) VALUES(?)", Json.Default.encodeToString(persistentConfig))
        }
    }

    data class OutdoorMeasurement(
        val time: Instant,
        val temperature: Double,
        val relativeHumidity: Int,
    )

    suspend fun saveOutdoor(outdoorMeasurement: OutdoorMeasurement) {
        transaction { ctx ->
            ctx.execute(
                "INSERT INTO outdoor_measurement(time, temperature, rel_humidity) VALUES (?, ?, ?)",
                outdoorMeasurement.time.toEpochMilli(),
                outdoorMeasurement.temperature,
                outdoorMeasurement.relativeHumidity,
            )
        }
    }

    suspend fun loadOutdoor(timeRange: ClosedRange<Instant>?, limit: Int) {
        transaction { ctx ->
            val records = if (timeRange != null) {
                ctx.fetch(
                    "SELECT time, temperature, rel_humidity FROM outdoor_measurement WHERE time >= ? AND time <= ? ORDER BY time DESC LIMIT ?",
                    timeRange.start.toEpochMilli(),
                    timeRange.endInclusive.toEpochMilli(),
                    limit,
                )
            } else {
                ctx.fetch(
                    "SELECT time, temperature, rel_humidity FROM outdoor_measurement ORDER BY time DESC LIMIT ?",
                    limit
                )
            }
            records.map {
                OutdoorMeasurement(
                    it.get("time", Long::class.java).let(Instant::ofEpochMilli),
                    it.get("temperature", Double::class.java),
                    it.get("rel_humidity", Int::class.java),
                )
            }
        }
    }
}