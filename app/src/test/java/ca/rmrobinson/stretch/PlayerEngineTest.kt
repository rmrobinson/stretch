package ca.rmrobinson.stretch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEngineTest {

    /** Drives [engine] in 50ms steps from [fromMs] up to (and including) [toMs], collecting
     * every non-[CueEvent.NONE] event in order. Mirrors AppViewModel's real 50ms tick cadence.
     * Callers driving the same engine across multiple calls must chain `fromMs`/`toMs` so the
     * engine's internal clock (which never resets) keeps advancing monotonically. */
    private fun drive(engine: PlayerEngine, fromMs: Long, toMs: Long, stepMs: Long = 50): List<CueEvent> {
        val events = mutableListOf<CueEvent>()
        var t = fromMs
        while (t < toMs) {
            t += stepMs
            val e = engine.tick(t)
            if (e != CueEvent.NONE) events.add(e)
        }
        return events
    }

    private fun drive(engine: PlayerEngine, totalMs: Long, stepMs: Long = 50): List<CueEvent> =
        drive(engine, fromMs = 0, toMs = totalMs, stepMs = stepMs)

    @Test
    fun `cues fire once each at cueAtSeconds down to 1, then done at zero`() {
        val routine = Routine("R", listOf(Step("A", seconds = 5, cueAtSeconds = 3)))
        val engine = PlayerEngine(routine, nowMs = 0)

        val events = drive(engine, totalMs = 5000)

        assertEquals(listOf(CueEvent.TICK, CueEvent.TICK, CueEvent.TICK, CueEvent.DONE), events)
    }

    @Test
    fun `custom cueAtSeconds changes tick count`() {
        val routine = Routine("R", listOf(Step("A", seconds = 5, cueAtSeconds = 1)))
        val engine = PlayerEngine(routine, nowMs = 0)

        val events = drive(engine, totalMs = 5000)

        assertEquals(listOf(CueEvent.TICK, CueEvent.DONE), events)
    }

    @Test
    fun `timed step auto-advances to next step at zero`() {
        val routine = Routine(
            "R",
            listOf(Step("A", seconds = 2, cueAtSeconds = 1), Step("B", seconds = 3, cueAtSeconds = 1))
        )
        val engine = PlayerEngine(routine, nowMs = 0)

        drive(engine, totalMs = 2000)

        assertEquals(1, engine.state.index)
        assertEquals(3000L, engine.state.remainingMs)
        assertFalse(engine.state.finished)
    }

    @Test
    fun `rep step never auto-advances`() {
        val routine = Routine("R", listOf(Step("A", reps = 10)))
        val engine = PlayerEngine(routine, nowMs = 0)

        val events = drive(engine, totalMs = 60_000)

        assertEquals(emptyList<CueEvent>(), events)
        assertEquals(0, engine.state.index)
        assertFalse(engine.state.finished)
    }

    @Test
    fun `pause freezes remaining time and resume continues from it`() {
        val routine = Routine("R", listOf(Step("A", seconds = 10, cueAtSeconds = 1)))
        val engine = PlayerEngine(routine, nowMs = 0)

        engine.tick(2000) // 8000ms remaining
        val remainingAtPause = engine.state.remainingMs
        engine.togglePause(2000)
        assertTrue(engine.state.paused)

        // Time passes while paused; ticking must not change remaining time.
        engine.tick(5000)
        engine.tick(9000)
        assertEquals(remainingAtPause, engine.state.remainingMs)

        engine.togglePause(9000)
        assertFalse(engine.state.paused)
        engine.tick(10000) // 1000ms of active time since resume
        assertEquals(remainingAtPause - 1000, engine.state.remainingMs)
    }

    @Test
    fun `previous is a no-op at the first step`() {
        val routine = Routine("R", listOf(Step("A", seconds = 5), Step("B", seconds = 5)))
        val engine = PlayerEngine(routine, nowMs = 0)

        engine.previous(0)

        assertEquals(0, engine.state.index)
    }

    @Test
    fun `previous returns to the prior step`() {
        val routine = Routine("R", listOf(Step("A", seconds = 5), Step("B", seconds = 7)))
        val engine = PlayerEngine(routine, nowMs = 0)

        engine.next(0)
        assertEquals(1, engine.state.index)
        engine.previous(0)

        assertEquals(0, engine.state.index)
        assertEquals(5000L, engine.state.remainingMs)
    }

    @Test
    fun `next on last step finishes the routine`() {
        val routine = Routine("R", listOf(Step("A", seconds = 5)))
        val engine = PlayerEngine(routine, nowMs = 0)

        val event = engine.next(0)

        assertTrue(engine.state.finished)
        assertEquals(CueEvent.NONE, event) // timed step: DONE already fired via tick at 0, not here
    }

    @Test
    fun `manually finishing a rep-based last step emits done`() {
        val routine = Routine("R", listOf(Step("A", reps = 10)))
        val engine = PlayerEngine(routine, nowMs = 0)

        val event = engine.next(0)

        assertTrue(engine.state.finished)
        assertEquals(CueEvent.DONE, event)
    }

    // ---- perSide ----

    @Test
    fun `perSide step is expanded into two beats named Left and Right`() {
        val routine = Routine("R", listOf(Step("Hip flexor", seconds = 10, perSide = true)))
        val engine = PlayerEngine(routine, nowMs = 0)

        assertEquals(2, engine.state.steps.size)
        assertEquals("Hip flexor, Left", engine.state.steps[0].name)
        assertEquals("Hip flexor, Right", engine.state.steps[1].name)
    }

    @Test
    fun `only the perSide step doubles, others stay single`() {
        val routine = Routine(
            "R",
            listOf(Step("Neck rolls", seconds = 10), Step("Hip flexor", seconds = 10, perSide = true), Step("Cat-cow", reps = 10))
        )
        val engine = PlayerEngine(routine, nowMs = 0)

        assertEquals(4, engine.state.steps.size)
        assertEquals(
            listOf("Neck rolls", "Hip flexor, Left", "Hip flexor, Right", "Cat-cow"),
            engine.state.steps.map { it.name }
        )
    }

    @Test
    fun `timed perSide step auto-advances from Left to Right, then to the next step`() {
        val routine = Routine(
            "R",
            listOf(Step("Hip flexor", seconds = 2, cueAtSeconds = 1, perSide = true), Step("Cat-cow", reps = 10))
        )
        val engine = PlayerEngine(routine, nowMs = 0)
        assertEquals("Hip flexor, Left", engine.state.step.name)

        drive(engine, fromMs = 0, toMs = 2000)
        assertEquals(1, engine.state.index)
        assertEquals("Hip flexor, Right", engine.state.step.name)
        assertEquals(2000L, engine.state.remainingMs)
        assertFalse(engine.state.finished)

        drive(engine, fromMs = 2000, toMs = 4000)
        assertEquals(2, engine.state.index)
        assertEquals("Cat-cow", engine.state.step.name)
        assertFalse(engine.state.finished)
    }

    @Test
    fun `previous from Right side of a perSide step returns to Left, not the prior step`() {
        val routine = Routine(
            "R",
            listOf(Step("Neck rolls", seconds = 5), Step("Hip flexor", seconds = 10, perSide = true))
        )
        val engine = PlayerEngine(routine, nowMs = 0)

        engine.next(0) // Neck rolls -> Hip flexor, Left
        engine.next(0) // Hip flexor, Left -> Hip flexor, Right
        assertEquals("Hip flexor, Right", engine.state.step.name)

        engine.previous(0)

        assertEquals("Hip flexor, Left", engine.state.step.name)
    }

    @Test
    fun `perSide reps step waits for manual Next on each side`() {
        val routine = Routine("R", listOf(Step("Wall push", reps = 10, perSide = true)))
        val engine = PlayerEngine(routine, nowMs = 0)

        val events = drive(engine, totalMs = 60_000)
        assertEquals(emptyList<CueEvent>(), events)
        assertEquals("Wall push, Left", engine.state.step.name)

        engine.next(0)
        assertEquals("Wall push, Right", engine.state.step.name)
        assertFalse(engine.state.finished)

        val event = engine.next(0)
        assertTrue(engine.state.finished)
        assertEquals(CueEvent.DONE, event)
    }
}
