package ca.rmrobinson.stretch

import kotlinx.serialization.Serializable

/** Exactly one of [seconds] or [reps] must be set. If [perSide] is true, the player runs
 * this step twice in a row (Left, then Right) instead of once. */
@Serializable
data class Step(
    val name: String,
    val seconds: Int? = null,
    val reps: Int? = null,
    val cueAtSeconds: Int = 3,
    val perSide: Boolean = false,
) {
    val isTimed: Boolean get() = seconds != null
}

@Serializable
data class Routine(val name: String, val steps: List<Step>)

@Serializable
data class Library(val routines: List<Routine>)

/** Last-resort fallback if the bundled default_routines.json asset can't be read/parsed. */
fun defaultRoutines() = listOf(
    Routine(
        "Morning stretch",
        listOf(
            Step("Neck rolls", seconds = 30),
            Step("Cat-cow", reps = 10),
            Step("Standing hamstring stretch", seconds = 45),
            Step("Hip flexor stretch", seconds = 45, perSide = true),
            Step("Arm circles", reps = 15),
            Step("Child's pose", seconds = 60),
        )
    )
)
