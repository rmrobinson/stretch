package ca.rmrobinson.stretch

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    object Home : Screen
    object Play : Screen
    object Edit : Screen
}

/** Editor row; [value] is text so the field can be empty while typing. */
data class EditStep(
    val name: String,
    val timed: Boolean,
    val value: String,
    val cueAt: Int = 3,
    val perSide: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RoutineRepository(app)
    private val cues = Cues(app)
    private val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var timerJob: Job? = null
    private var engine: PlayerEngine? = null

    var routines by mutableStateOf(repo.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var player by mutableStateOf<PlayerState?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var editingIndex by mutableStateOf<Int?>(null)
        private set
    var draftName by mutableStateOf("")
    val draftSteps = mutableStateListOf<EditStep>()

    private fun now() = SystemClock.elapsedRealtime()

    // ---- navigation ----
    fun back() {
        when (screen) {
            Screen.Play -> exitPlayer()
            Screen.Edit -> screen = Screen.Home
            Screen.Home -> Unit
        }
    }

    // ---- player ----
    fun startRoutine(i: Int) {
        val r = routines[i]
        if (r.steps.isEmpty()) return
        cues.start()
        val e = PlayerEngine(r, now())
        engine = e
        player = e.state
        screen = Screen.Play
        runTimerLoop()
    }

    private fun runTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(50)
                val e = engine ?: return@launch
                when (e.tick(now())) {
                    CueEvent.TICK -> cues.tick()
                    CueEvent.DONE -> cues.done()
                    CueEvent.NONE -> Unit
                }
                player = e.state
            }
        }
    }

    fun next() {
        val e = engine ?: return
        if (e.next(now()) == CueEvent.DONE) cues.done()
        player = e.state
    }

    fun previous() {
        val e = engine ?: return
        e.previous(now())
        player = e.state
    }

    fun togglePause() {
        val e = engine ?: return
        e.togglePause(now())
        player = e.state
    }

    fun exitPlayer() {
        timerJob?.cancel()
        cues.stop()
        engine = null
        player = null
        screen = Screen.Home
    }

    // ---- editor ----
    fun openEditor(i: Int?) {
        editingIndex = i
        val r = i?.let { routines[it] }
        draftName = r?.name ?: ""
        draftSteps.clear()
        draftSteps.addAll(
            r?.steps?.map { EditStep(it.name, it.isTimed, (it.seconds ?: it.reps ?: 0).toString(), it.cueAtSeconds, it.perSide) }
                ?: listOf(EditStep("", true, "30"))
        )
        screen = Screen.Edit
    }

    fun addDraftStep() { draftSteps.add(EditStep("", true, "30")) }

    fun moveDraftStep(i: Int, delta: Int) {
        val j = i + delta
        if (j !in draftSteps.indices) return
        val tmp = draftSteps[i]; draftSteps[i] = draftSteps[j]; draftSteps[j] = tmp
    }

    fun saveDraft() {
        val name = draftName.trim()
        if (name.isEmpty()) { message = "Routine needs a name"; return }
        if (draftSteps.isEmpty()) { message = "Add at least one step"; return }
        val steps = draftSteps.map { e ->
            val n = e.value.toIntOrNull()
            if (e.name.isBlank() || n == null || n <= 0) { message = "Fix step: '${e.name}'"; return }
            Step(e.name.trim(), seconds = n.takeIf { e.timed }, reps = n.takeIf { !e.timed }, cueAtSeconds = e.cueAt, perSide = e.perSide)
        }
        val r = Routine(name, steps)
        val i = editingIndex
        routines = if (i != null) routines.toMutableList().also { it[i] = r } else repo.upsert(routines, listOf(r))
        repo.save(routines)
        screen = Screen.Home
    }

    fun deleteEditing() {
        val i = editingIndex ?: return
        routines = routines.toMutableList().also { it.removeAt(i) }
        repo.save(routines)
        screen = Screen.Home
    }

    // ---- import / export (this is how new routines get in) ----
    fun importFromClipboard() {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) { message = "Clipboard is empty"; return }
        try {
            val incoming = repo.parse(text)
            routines = repo.upsert(routines, incoming)
            repo.save(routines)
            message = "Imported: " + incoming.joinToString { it.name }
        } catch (e: Exception) {
            message = "Import failed: ${e.message}"
        }
    }

    fun copyAllToClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("routines", repo.toJson(routines)))
        message = "Copied all routines as JSON"
    }

    override fun onCleared() { cues.stop() }
}
