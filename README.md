# Stretch

A small Android app for running guided stretching routines. Pick a routine, get a short
"get ready" countdown, then step through each stretch with an on-screen timer and audio
beeps. Routines are stored as JSON and can be edited in the app or imported/exported via
the clipboard.

## Features

- **Timed and rep-based steps.** A step either runs for `seconds` (with a countdown) or shows
  a rep count you advance manually.
- **Per-side steps.** A step marked `perSide` runs twice in a row: "…, Left" then "…, Right".
- **Audio cues.** Beeps during the last few seconds of each timed step, a "go" tone when the
  timer starts, and a completion tone. Other audio (music, podcasts) is ducked rather than paused.
- **Grace period.** Each timed step after the first gets 3 seconds to get into position before
  its timer starts.
- **Editor.** Create, edit, reorder (drag), and delete routines and steps in the app.
- **Clipboard import/export.** "Copy all" exports every routine as JSON; "Paste" imports JSON
  from the clipboard — handy for writing routines on a computer or having an LLM generate them.

## Installing

### Requirements

- JDK 17
- Android SDK with platform 35 (Android Studio installs this for you)
- A device or emulator running Android 8.0 (API 26) or newer

### Build and install from the command line

With a device connected via USB (with USB debugging enabled) or an emulator running:

```sh
./gradlew installDebug
```

To just build the APK:

```sh
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

You can then sideload it with `adb install app/build/outputs/apk/debug/app-debug.apk`.

### Android Studio

Open the repository root as a project, let Gradle sync, and run the `app` configuration.

## How it works

### Using the app

1. **Home** lists your routines. Tap one to see its summary, tap the pencil icon to edit it,
   or tap **+** to create a new one.
2. **Summary** shows the steps. Tap **Start** to begin a 3-second "Get ready" countdown.
3. **Player** shows the current step. Timed steps count down and auto-advance; rep-based steps
   wait for you to tap **Next**. You can pause, go back a step, or exit.

On first launch the app seeds its routine list from
[`app/src/main/assets/default_routines.json`](app/src/main/assets/default_routines.json).
After that, routines live in the app's private storage (`files/routines.json`). If that file
ever fails to parse, it is preserved as `routines.bad.json` and the defaults are reloaded.

### Routine JSON format

```json
{
  "routines": [
    {
      "name": "Morning stretch",
      "steps": [
        { "name": "Chin tucks", "reps": 10 },
        { "name": "Upper trap stretch", "seconds": 30, "perSide": true },
        { "name": "Child's pose", "seconds": 60, "cueAtSeconds": 5 }
      ]
    }
  ]
}
```

| Field          | Type    | Notes                                                              |
|----------------|---------|--------------------------------------------------------------------|
| `name`         | string  | Required, non-blank.                                               |
| `seconds`      | int     | Duration of a timed step. Exactly one of `seconds`/`reps` is set.  |
| `reps`         | int     | Rep count for a manual step. Must be > 0.                          |
| `perSide`      | boolean | Optional (default `false`). Run once for Left, then once for Right.|
| `cueAtSeconds` | int     | Optional (default `3`). Beep each second for the final N seconds.  |

Import is lenient about shape: it accepts the `{"routines": [...]}` wrapper, a bare array of
routines, or a single routine object, and tolerates surrounding ` ```json ` fences and unknown
keys. Import is all-or-nothing — if any routine is invalid, nothing is imported. Imported
routines replace existing ones with the same name (case-insensitive); others are appended.

### Code layout

All source is in `app/src/main/java/ca/rmrobinson/stretch/`:

| File               | Responsibility                                                                 |
|--------------------|---------------------------------------------------------------------------------|
| `Model.kt`         | `Step`, `Routine`, `Library` data classes (kotlinx.serialization).             |
| `RoutineCodec.kt`  | Pure-Kotlin JSON parse/validate/serialize and the import merge (`upsert`).     |
| `Repository.kt`    | Loads/saves `routines.json`, seeds from the bundled asset.                     |
| `PlayerEngine.kt`  | Pure-Kotlin timer state machine; driven by caller-supplied timestamps.         |
| `Cues.kt`          | Audio beeps via `ToneGenerator` and audio focus (ducking).                     |
| `AppViewModel.kt`  | App state, screen navigation, the timer loop, editor draft state, clipboard.  |
| `Screens.kt`       | Jetpack Compose UI for each screen.                                            |
| `DragReorder.kt`   | Drag-to-reorder support for the step list in the editor.                       |
| `MainActivity.kt`  | Entry point; sets up the Material 3 theme and hosts `App`.                     |

The design keeps logic out of Android-dependent classes: `PlayerEngine` and `RoutineCodec`
have no `Context` dependency and no coroutines, so they are tested as plain JVM unit tests.
`AppViewModel` runs a 50 ms loop that calls `PlayerEngine.tick(now)` and maps the returned
`CueEvent` (`TICK`, `GO`, `DONE`) to a sound.

## Extending

### Changing the default routines

Edit `app/src/main/assets/default_routines.json`. This only affects fresh installs (or after
clearing app data); existing users keep their saved routines. To push new routines to an
existing install, copy the JSON to the device's clipboard and tap **Paste**.

### Adding a field to a step

1. Add the property to `Step` in `Model.kt` with a default value, so older JSON still parses.
2. Add any validation in `RoutineCodec.validate`.
3. If the player needs it, carry it through `PlayStep` / `expand` in `PlayerEngine.kt`.
4. Expose it in the editor: add it to `EditStep` and to `openEditor`/`saveDraft` in
   `AppViewModel.kt`, then add a control in `EditorScreen` in `Screens.kt`.
5. Add tests in `RoutineCodecTest` / `PlayerEngineTest`.

### Changing timing behaviour

`GET_READY_SECONDS` and `STEP_GRACE_SECONDS` at the top of `AppViewModel.kt` control the
lead-in countdown and per-step grace period. Timer logic itself lives in `PlayerEngine`; since
it takes `nowMs` explicitly, new behaviour can be tested by stepping through hand-picked
timestamps (see `PlayerEngineTest`).

### Changing sounds

Edit `Cues.kt`. Each `CueEvent` maps to one method (`tick`, `go`, `done`). To add a new kind of
cue, add a value to `CueEvent`, emit it from `PlayerEngine.tick`, and handle it in
`AppViewModel.runTimerLoop`.

### Adding a screen

Add an object to the `Screen` sealed interface in `AppViewModel.kt`, handle it in `back()`,
add a composable in `Screens.kt`, and route to it from `App`.

## Testing

```sh
./gradlew test
```

## License

MIT — see [LICENSE](LICENSE).
