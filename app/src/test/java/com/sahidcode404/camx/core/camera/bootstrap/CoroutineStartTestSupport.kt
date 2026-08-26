package com.sahidcode404.camx.core.camera.bootstrap

import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine as startStandardCoroutine

/** Keeps the deterministic no-sleep test harness independent of kotlinx-coroutines-test. */
internal fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>) {
    startStandardCoroutine(completion)
}
