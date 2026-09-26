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
    object Summary : Screen
    object Countdown : Screen
    object Play : Screen
    object Edit : Screen
}

private const val GET_READY_SECONDS = 3

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
    private var pendingRoutineIndex: Int? = null
    private var isCountingDown = false

    var routines by mutableStateOf<List<Routine>>(emptyList())
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var player by mutableStateOf<PlayerState?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    var summaryIndex by mutableStateOf<Int?>(null)
        private set

    var editingIndex by mutableStateOf<Int?>(null)
        private set
    var draftName by mutableStateOf("")
    val draftSteps = mutableStateListOf<EditStep>()

    init {
        viewModelScope.launch { routines = repo.load() }
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ---- navigation ----
    fun back() {
        when (screen) {
            Screen.Play -> exitPlayer()
            Screen.Countdown -> cancelCountdown()
            Screen.Summary -> { summaryIndex = null; screen = Screen.Home }
            Screen.Edit -> screen = Screen.Home
            Screen.Home -> Unit
        }
    }

    // ---- routine summary / pre-start countdown ----
    fun openSummary(i: Int) {
        summaryIndex = i
        screen = Screen.Summary
    }

    /** Starts a "Get ready" lead-in with the same countdown/cue treatment as a timed step,
     * then automatically begins actual playback of the previewed routine. */
    fun startCountdown() {
        val i = summaryIndex ?: return
        if (routines[i].steps.isEmpty()) return
        pendingRoutineIndex = i
        isCountingDown = true
        cues.start()
        val getReady = Routine("Get ready", listOf(Step("Get ready", seconds = GET_READY_SECONDS, cueAtSeconds = GET_READY_SECONDS)))
        val e = PlayerEngine(getReady, now())
        engine = e
        player = e.state
        screen = Screen.Countdown
        runTimerLoop()
    }

    fun cancelCountdown() {
        timerJob?.cancel()
        cues.stop()
        engine = null
        player = null
        isCountingDown = false
        pendingRoutineIndex = null
        screen = Screen.Summary
    }

    // ---- player ----
    /** Begins actual routine playback; reached once [startCountdown]'s lead-in finishes. */
    private fun startRoutine(i: Int) {
        val r = routines[i]
        if (r.steps.isEmpty()) return
        isCountingDown = false
        pendingRoutineIndex = null
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
                if (isCountingDown && e.state.finished) {
                    pendingRoutineIndex?.let { startRoutine(it) }
                    return@launch
                }
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
        isCountingDown = false
        pendingRoutineIndex = null
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
        screen = Screen.Home
        viewModelScope.launch { repo.save(routines) }
    }

    fun deleteEditing() {
        val i = editingIndex ?: return
        routines = routines.toMutableList().also { it.removeAt(i) }
        screen = Screen.Home
        viewModelScope.launch { repo.save(routines) }
    }

    // ---- import / export (this is how new routines get in) ----
    fun importFromClipboard() {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) { message = "Clipboard is empty"; return }
        try {
            val incoming = repo.parse(text)
            routines = repo.upsert(routines, incoming)
            message = "Imported: " + incoming.joinToString { it.name }
            viewModelScope.launch { repo.save(routines) }
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
