package ca.rmrobinson.stretch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Steps counted with `perSide` doubled, and total timed seconds likewise doubled. */
private fun stepSummary(r: Routine): Pair<Int, Int> {
    val stepCount: Int = r.steps.map { s -> if (s.perSide) 2 else 1 }.sum()
    val timedSeconds: Int = r.steps.map { s -> (s.seconds ?: 0) * (if (s.perSide) 2 else 1) }.sum()
    return stepCount to timedSeconds
}

private fun stepValueLabel(s: Step): String {
    val value = if (s.isTimed) "${s.seconds}s" else "${s.reps} reps"
    return if (s.perSide) "$value · each side" else value
}

@Composable
fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    // Editor and Summary exit straight back on back-press; Player and Countdown handle their
    // own back-press (see PlayerScreen/CountdownScreen) since they need custom confirm/cancel behavior.
    BackHandler(enabled = vm.screen == Screen.Edit || vm.screen == Screen.Summary) { vm.back() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Summary -> SummaryScreen(vm)
                Screen.Countdown -> CountdownScreen(vm)
                Screen.Play -> PlayerScreen(vm)
                Screen.Edit -> EditorScreen(vm)
            }
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Routines", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::importFromClipboard) { Text("Paste") }
                TextButton(onClick = vm::copyAllToClipboard) { Text("Copy all") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(vm.routines) { i, r ->
                    val (stepCount, timed) = stepSummary(r)
                    Card(onClick = { vm.openSummary(i) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "$stepCount steps · ${timed / 60}:${"%02d".format(timed % 60)} timed",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            IconButton(onClick = { vm.openEditor(i) }) { Icon(Icons.Default.Edit, "Edit") }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { vm.openEditor(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, "New routine") }
    }
}

@Composable
fun SummaryScreen(vm: AppViewModel) {
    val i = vm.summaryIndex ?: return
    val r = vm.routines.getOrNull(i) ?: return
    val (stepCount, timed) = stepSummary(r)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(r.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "$stepCount steps · ${timed / 60}:${"%02d".format(timed % 60)} timed",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(r.steps) { _, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(stepValueLabel(s), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = vm::back) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::startCountdown, modifier = Modifier.height(64.dp)) { Text("Start") }
        }
    }
}

@Composable
fun CountdownScreen(vm: AppViewModel) {
    val p = vm.player
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = true) { vm.cancelCountdown() }
    if (p == null) return
    val routineName = vm.summaryIndex?.let { vm.routines.getOrNull(it)?.name } ?: ""
    val secs = p.secsLeft
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Get ready", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(routineName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp))
        Text("$secs", fontSize = 180.sp, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = vm::cancelCountdown, modifier = Modifier.height(56.dp)) { Text("Cancel") }
    }
}

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val p = vm.player
    val view = LocalView.current
    var showExitConfirm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = true) { showExitConfirm = true }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit routine?") },
            confirmButton = {
                TextButton(onClick = { showExitConfirm = false; vm.exitPlayer() }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
            }
        )
    }
    if (p == null) return
    if (p.finished) {
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Done", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = vm::exitPlayer) { Text("Close") }
        }
        return
    }
    val step = p.step
    val last = p.index == p.steps.lastIndex
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${p.index + 1} / ${p.steps.size}  ·  ${p.routineName}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { p.index / p.steps.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Text(step.name, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (step.isTimed) {
            val secs = p.secsLeft
            val warn = secs in 1..step.cueAtSeconds
            Text(
                "$secs",
                fontSize = 144.sp,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text("${step.reps} reps", fontSize = 72.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::previous, modifier = Modifier.height(64.dp)) { Text("Back") }
            if (step.isTimed) {
                OutlinedButton(onClick = vm::togglePause, modifier = Modifier.height(64.dp)) {
                    Text(if (p.paused) "Resume" else "Pause")
                }
            }
            Button(onClick = vm::next, modifier = Modifier.weight(1f).height(64.dp)) {
                Text(if (last) "Finish" else "Next")
            }
        }
    }
}

@Composable
fun EditorScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = vm.draftName,
            onValueChange = { vm.draftName = it },
            label = { Text("Routine name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.draftSteps) { i, s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = s.name,
                            onValueChange = { vm.draftSteps[i] = s.copy(name = it) },
                            label = { Text("Stretch name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = s.timed, onClick = { vm.draftSteps[i] = s.copy(timed = true) }, label = { Text("Timed") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected = !s.timed, onClick = { vm.draftSteps[i] = s.copy(timed = false) }, label = { Text("Reps") })
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = s.value,
                                onValueChange = { vm.draftSteps[i] = s.copy(value = it.filter(Char::isDigit)) },
                                label = { Text(if (s.timed) "Seconds" else "Reps") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = s.perSide,
                                onClick = { vm.draftSteps[i] = s.copy(perSide = !s.perSide) },
                                label = { Text("Per side (left, then right)") }
                            )
                        }
                        Row {
                            TextButton(onClick = { vm.moveDraftStep(i, -1) }) { Text("↑") }
                            TextButton(onClick = { vm.moveDraftStep(i, 1) }) { Text("↓") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.draftSteps.removeAt(i) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::addDraftStep) { Text("+ Step") }
            if (vm.editingIndex != null) TextButton(onClick = vm::deleteEditing) { Text("Delete routine") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::back) { Text("Cancel") }
            Button(onClick = vm::saveDraft) { Text("Save") }
        }
    }
}
