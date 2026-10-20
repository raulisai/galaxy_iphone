package dev.wristbridge.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Runs [block] only while the activity is STARTED (screen on and visible) and cancels it when the screen turns off.
 * A plain LaunchedEffect keeps its timers alive while the activity is stopped, waking the CPU for nothing.
 */
@Composable
internal fun RepeatWhileStarted(key: Any?, block: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { block() }
    }
}
