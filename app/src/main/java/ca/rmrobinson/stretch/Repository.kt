package ca.rmrobinson.stretch

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoutineRepository(private val context: Context) {
    private val codec = RoutineCodec()
    private val file get() = File(context.filesDir, "routines.json")

    /** First run: seed from the bundled asset. Corrupt routines.json: fall back to seeds
     * and preserve the bad file as routines.bad.json. */
    suspend fun load(): List<Routine> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext seedRoutines()
        try {
            codec.parse(file.readText())
        } catch (e: Exception) {
            file.copyTo(File(context.filesDir, "routines.bad.json"), overwrite = true)
            seedRoutines()
        }
    }

    suspend fun save(routines: List<Routine>) = withContext(Dispatchers.IO) {
        file.writeText(codec.toJson(routines))
    }

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
