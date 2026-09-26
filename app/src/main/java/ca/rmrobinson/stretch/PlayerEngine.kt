package ca.rmrobinson.stretch

/** A single beat in the player: one occurrence of a [Step], expanded to Left/Right when
 * [Step.perSide] is set. [name] already carries the "..., Left" / "..., Right" suffix. */
data class PlayStep(
    val name: String,
    val seconds: Int?,
    val reps: Int?,
    val cueAtSeconds: Int,
) {
    val isTimed: Boolean get() = seconds != null
}

private fun expand(steps: List<Step>): List<PlayStep> = steps.flatMap { s ->
    if (s.perSide) {
        listOf("Left", "Right").map { side ->
            PlayStep("${s.name}, $side", s.seconds, s.reps, s.cueAtSeconds)
        }
    } else {
        listOf(PlayStep(s.name, s.seconds, s.reps, s.cueAtSeconds))
    }
}

data class PlayerState(
    val routineName: String,
    val steps: List<PlayStep>,
    val index: Int = 0,
    val remainingMs: Long = 0,
    val paused: Boolean = false,
    val finished: Boolean = false,
) {
    val step: PlayStep get() = steps[index]

    /** Seconds remaining, rounded up so the display never shows 0 while time is still left. */
    val secsLeft: Int get() = ((remainingMs + 999) / 1000).toInt()
}

enum class CueEvent { NONE, TICK, DONE }

/**
 * Pure Kotlin timer/state machine for a running routine. Driven entirely by caller-supplied
 * timestamps (no [android.os.SystemClock], no coroutines/delay), so it's synchronously
 * unit-testable with hand-picked `nowMs` values.
 *
 * [routine] must have at least one step. A `perSide` step is expanded into two consecutive
 * beats (Left, then Right) up front, so the rest of the engine just sees a flat step list.
 */
class PlayerEngine(routine: Routine, nowMs: Long) {
    var state: PlayerState = PlayerState(routine.name, expand(routine.steps))
        private set

    private var lastTickMs: Long = nowMs
    private var lastShownSecs: Int = 0

    init { beginStep(0, nowMs) }

    private fun beginStep(index: Int, nowMs: Long) {
        val step = state.steps[index]
        state = state.copy(
            index = index,
            remainingMs = (step.seconds ?: 0) * 1000L,
            paused = false,
            finished = false,
        )
        lastTickMs = nowMs
        lastShownSecs = state.secsLeft
    }

    /** Advances the clock to [nowMs]. No-op (and returns [CueEvent.NONE]) while paused,
     * finished, or on a rep-based step. Auto-advances to the next step at 0. */
    fun tick(nowMs: Long): CueEvent {
        if (state.finished || state.paused || !state.step.isTimed) return CueEvent.NONE
        val elapsed = nowMs - lastTickMs
        lastTickMs = nowMs
        val remaining = (state.remainingMs - elapsed).coerceAtLeast(0)
        state = state.copy(remainingMs = remaining)

        val shown = state.secsLeft
        var event = CueEvent.NONE
        if (shown != lastShownSecs) {
            lastShownSecs = shown
            if (shown in 1..state.step.cueAtSeconds) event = CueEvent.TICK
        }
        if (remaining == 0L) {
            event = CueEvent.DONE
            next(nowMs)
        }
        return event
    }

    /** Manual advance (or auto-advance from [tick]). Returns [CueEvent.DONE] only for the
     * case tick() doesn't already cover: finishing on a rep-based last step. */
    fun next(nowMs: Long): CueEvent {
        val s = state
        return if (s.index + 1 < s.steps.size) {
            beginStep(s.index + 1, nowMs)
            CueEvent.NONE
        } else {
            val wasTimed = s.step.isTimed
            state = s.copy(finished = true)
            if (!wasTimed) CueEvent.DONE else CueEvent.NONE
        }
    }

    fun previous(nowMs: Long) {
        if (state.index > 0) beginStep(state.index - 1, nowMs)
    }

    fun togglePause(nowMs: Long) {
        if (!state.step.isTimed) return
        state = if (state.paused) {
            lastTickMs = nowMs
            state.copy(paused = false)
        } else {
            state.copy(paused = true)
        }
    }
}
