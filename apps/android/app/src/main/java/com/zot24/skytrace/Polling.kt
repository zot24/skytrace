package com.zot24.skytrace

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Fetches at once, then after each returned delay, only while [lifecycle] is STARTED; cancelled on STOPPED. */
suspend fun pollWhileStarted(lifecycle: Lifecycle, fetchOnce: suspend () -> Long) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) delay(fetchOnce())
    }
}
