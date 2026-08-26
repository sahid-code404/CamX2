package com.sahidcode404.camx.core.update

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

interface UpdateRepository {
    val state: StateFlow<UpdateState>
    suspend fun checkAfterFirstFrame()
    suspend fun checkManually()
    suspend fun downloadAvailable()
    fun cancel()
}

class FirstPreviewGate {
    private val verified = AtomicBoolean(false)

    fun markVerified() {
        verified.set(true)
    }

    fun requireVerified() {
        check(verified.get()) { "OTA cannot start before the first verified preview frame" }
    }
}
