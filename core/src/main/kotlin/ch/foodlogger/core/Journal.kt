package ch.foodlogger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A portion this app wrote to the health store, kept locally so it can be listed and deleted. */
@Serializable
data class LoggedEntry(
    /** ID the health store assigned to the record. */
    val recordId: String,
    val name: String,
    val grams: Double,
    val meal: MealSlot,
    /** Nutrients of the eaten portion (not per 100 g). */
    val nutrients: Nutrients,
    val loggedAtMillis: Long,
)

/** Local list of logged entries, newest first, limited to the last [KEEP_DAYS] days. */
object Journal {
    const val KEEP_DAYS = 7
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    /** Adds [entry] and drops entries older than [KEEP_DAYS] before it. */
    fun add(list: List<LoggedEntry>, entry: LoggedEntry): List<LoggedEntry> {
        val cutoff = entry.loggedAtMillis - KEEP_DAYS * DAY_MILLIS
        return (list.filter { it.recordId != entry.recordId && it.loggedAtMillis >= cutoff } + entry)
            .sortedByDescending { it.loggedAtMillis }
    }

    fun remove(list: List<LoggedEntry>, recordId: String): List<LoggedEntry> = list.filter { it.recordId != recordId }

    /** Entries logged in [startMillis, endMillis), newest first. */
    fun between(list: List<LoggedEntry>, startMillis: Long, endMillis: Long): List<LoggedEntry> =
        list.filter { it.loggedAtMillis in startMillis until endMillis }.sortedByDescending { it.loggedAtMillis }

    fun total(entries: List<LoggedEntry>): Nutrients = entries.fold(Nutrients()) { sum, e -> sum + e.nutrients }

    fun encode(list: List<LoggedEntry>): String = json.encodeToString(list)

    /** Decodes [encode] output; returns an empty list for blank or corrupt input. */
    fun decode(text: String): List<LoggedEntry> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString<List<LoggedEntry>>(text) }.getOrDefault(emptyList())
}
