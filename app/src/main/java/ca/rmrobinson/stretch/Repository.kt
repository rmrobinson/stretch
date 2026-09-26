package ca.rmrobinson.stretch

import android.content.Context
import java.io.File

class RoutineRepository(private val context: Context) {
    private val codec = RoutineCodec()
    private val file get() = File(context.filesDir, "routines.json")

    /** First run: seed from the bundled asset. Corrupt routines.json: fall back to seeds
     * and preserve the bad file as routines.bad.json. */
    fun load(): List<Routine> {
        if (!file.exists()) return seedRoutines()
        return try {
            codec.parse(file.readText())
        } catch (e: Exception) {
            file.copyTo(File(context.filesDir, "routines.bad.json"), overwrite = true)
            seedRoutines()
        }
    }

    fun save(routines: List<Routine>) = file.writeText(codec.toJson(routines))

    fun toJson(routines: List<Routine>): String = codec.toJson(routines)

    fun parse(raw: String): List<Routine> = codec.parse(raw)

    fun upsert(existing: List<Routine>, incoming: List<Routine>): List<Routine> =
        codec.upsert(existing, incoming)

    private fun seedRoutines(): List<Routine> = try {
        val text = context.assets.open("default_routines.json").bufferedReader().use { it.readText() }
        codec.parse(text)
    } catch (e: Exception) {
        defaultRoutines()
    }
}
