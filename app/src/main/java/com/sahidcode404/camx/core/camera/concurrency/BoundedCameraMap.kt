package com.sahidcode404.camx.core.camera.concurrency

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal const val HARD_MAX_BOUNDED_CAMERA_TASKS = 4

/**
 * Executes one explicitly bounded camera-metadata batch concurrently.
 * Callers must pre-chunk work; this helper refuses to create more than four child tasks.
 */
internal suspend fun <T, R> boundedCameraMap(
    values: List<T>,
    maximumTasks: Int,
    transform: suspend (T) -> R,
): List<R> {
    require(maximumTasks in 1..HARD_MAX_BOUNDED_CAMERA_TASKS) {
        "Camera task bound exceeds the reviewed hard maximum"
    }
    require(values.size <= maximumTasks) {
        "Camera batch must be pre-chunked before concurrent execution"
    }
    return coroutineScope {
        values.map { value -> async { transform(value) } }.awaitAll()
    }
}
