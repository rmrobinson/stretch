package ca.rmrobinson.stretch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineCodecTest {
    private val codec = RoutineCodec()

    // ---- parse: accepted shapes ----

    @Test
    fun `parse accepts wrapped routines object`() {
        val routines = codec.parse("""{"routines":[{"name":"A","steps":[{"name":"S","seconds":10}]}]}""")
        assertEquals(1, routines.size)
        assertEquals("A", routines[0].name)
    }

    @Test
    fun `parse accepts bare array of routines`() {
        val routines = codec.parse("""[{"name":"A","steps":[{"name":"S","reps":5}]}]""")
        assertEquals(1, routines.size)
        assertEquals("A", routines[0].name)
    }

    @Test
    fun `parse accepts single routine object`() {
        val routines = codec.parse("""{"name":"A","steps":[{"name":"S","reps":5}]}""")
        assertEquals(1, routines.size)
        assertEquals("A", routines[0].name)
    }

    @Test
    fun `parse strips surrounding code fences`() {
        val raw = "```json\n{\"routines\":[{\"name\":\"A\",\"steps\":[{\"name\":\"S\",\"seconds\":10}]}]}\n```"
        val routines = codec.parse(raw)
        assertEquals(1, routines.size)
    }

    @Test
    fun `parse ignores unknown keys`() {
        val routines = codec.parse(
            """{"routines":[{"name":"A","extra":"ignored","steps":[{"name":"S","seconds":10,"unused":1}]}]}"""
        )
        assertEquals(1, routines.size)
    }

    @Test
    fun `parse defaults cueAtSeconds to 3`() {
        val routines = codec.parse("""{"name":"A","steps":[{"name":"S","seconds":10}]}""")
        assertEquals(3, routines[0].steps[0].cueAtSeconds)
    }

    @Test
    fun `parse defaults perSide to false`() {
        val routines = codec.parse("""{"name":"A","steps":[{"name":"S","seconds":10}]}""")
        assertFalse(routines[0].steps[0].perSide)
    }

    @Test
    fun `parse reads perSide true`() {
        val routines = codec.parse("""{"name":"A","steps":[{"name":"S","seconds":10,"perSide":true}]}""")
        assertTrue(routines[0].steps[0].perSide)
    }

    // ---- parse: validation failures (all-or-nothing) ----

    @Test
    fun `parse rejects step with both seconds and reps`() {
        assertThrows { codec.parse("""{"name":"A","steps":[{"name":"S","seconds":10,"reps":5}]}""") }
    }

    @Test
    fun `parse rejects step with neither seconds nor reps`() {
        assertThrows { codec.parse("""{"name":"A","steps":[{"name":"S"}]}""") }
    }

    @Test
    fun `parse rejects zero seconds`() {
        assertThrows { codec.parse("""{"name":"A","steps":[{"name":"S","seconds":0}]}""") }
    }

    @Test
    fun `parse rejects negative reps`() {
        assertThrows { codec.parse("""{"name":"A","steps":[{"name":"S","reps":-1}]}""") }
    }

    @Test
    fun `parse rejects blank routine name`() {
        assertThrows { codec.parse("""{"name":"  ","steps":[{"name":"S","seconds":10}]}""") }
    }

    @Test
    fun `parse rejects blank step name`() {
        assertThrows { codec.parse("""{"name":"A","steps":[{"name":"","seconds":10}]}""") }
    }

    @Test
    fun `parse rejects empty steps list`() {
        assertThrows { codec.parse("""{"name":"A","steps":[]}""") }
    }

    @Test
    fun `parse is all-or-nothing across multiple routines`() {
        // Second routine is invalid (zero seconds) -> whole parse must throw, nothing returned.
        assertThrows {
            codec.parse(
                """{"routines":[
                    {"name":"Good","steps":[{"name":"S","seconds":10}]},
                    {"name":"Bad","steps":[{"name":"S","seconds":0}]}
                ]}"""
            )
        }
    }

    // ---- upsert ----

    @Test
    fun `upsert replaces existing routine by case-insensitive name`() {
        val existing = listOf(routine("Morning"), routine("Evening"))
        val incoming = listOf(routine("morning", stepCount = 3))
        val result = codec.upsert(existing, incoming)
        assertEquals(2, result.size)
        assertEquals("morning", result[0].name)
        assertEquals(3, result[0].steps.size)
        assertEquals("Evening", result[1].name)
    }

    @Test
    fun `upsert appends new routine and preserves order`() {
        val existing = listOf(routine("Morning"), routine("Evening"))
        val incoming = listOf(routine("Midday"))
        val result = codec.upsert(existing, incoming)
        assertEquals(listOf("Morning", "Evening", "Midday"), result.map { it.name })
    }

    // ---- round trip ----

    @Test
    fun `toJson then parse round trips equal routines`() {
        val original = listOf(
            Routine(
                "Morning",
                listOf(
                    Step("Neck rolls", seconds = 30),
                    Step("Cat-cow", reps = 10, cueAtSeconds = 5),
                    Step("Hip flexor stretch", seconds = 45, perSide = true),
                )
            )
        )
        val roundTripped = codec.parse(codec.toJson(original))
        assertEquals(original, roundTripped)
    }

    private fun routine(name: String, stepCount: Int = 1) =
        Routine(name, (1..stepCount).map { Step("Step $it", seconds = 10) })

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected an exception to be thrown", threw)
    }
}
