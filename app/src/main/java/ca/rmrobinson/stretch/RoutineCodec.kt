package ca.rmrobinson.stretch

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Pure-Kotlin parse/validate/serialize/merge logic for routines. No Android [android.content.Context]
 * dependency so it's plain-JVM unit testable.
 */
class RoutineCodec {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun toJson(routines: List<Routine>): String =
        json.encodeToString(Library.serializer(), Library(routines))

    /**
     * Accepts {"routines":[...]}, a bare array of routines, or a single routine object.
     * Tolerates surrounding ```json fences and unknown keys. All-or-nothing: any invalid
     * routine in the payload throws and nothing is imported.
     */
    fun parse(raw: String): List<Routine> {
        val text = raw.trim()
            .replaceFirst(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val el = json.parseToJsonElement(text)
        val routines = when {
            el is JsonArray -> json.decodeFromJsonElement(ListSerializer(Routine.serializer()), el)
            el is JsonObject && "routines" in el -> json.decodeFromJsonElement(Library.serializer(), el).routines
            else -> listOf(json.decodeFromJsonElement(Routine.serializer(), el))
        }.map { r -> r.copy(name = r.name.trim(), steps = r.steps.map { it.copy(name = it.name.trim()) }) }
        routines.forEach(::validate)
        return routines
    }

    private fun validate(r: Routine) {
        require(r.name.isNotBlank()) { "routine name is blank" }
        require(r.steps.isNotEmpty()) { "'${r.name}' has no steps" }
        r.steps.forEach { s ->
            require(s.name.isNotBlank()) { "a step in '${r.name}' has a blank name" }
            require((s.seconds == null) != (s.reps == null)) { "'${s.name}' needs exactly one of seconds or reps" }
            require((s.seconds ?: s.reps ?: 0) > 0) { "'${s.name}' must be > 0" }
        }
    }

    /** Replaces by case-insensitive name, appends otherwise; preserves existing order. */
    fun upsert(existing: List<Routine>, incoming: List<Routine>): List<Routine> {
        val out = existing.toMutableList()
        incoming.forEach { r ->
            val idx = out.indexOfFirst { it.name.equals(r.name, ignoreCase = true) }
            if (idx >= 0) out[idx] = r else out.add(r)
        }
        return out
    }
}
