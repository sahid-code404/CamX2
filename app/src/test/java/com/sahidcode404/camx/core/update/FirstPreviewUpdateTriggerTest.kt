package com.sahidcode404.camx.core.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstPreviewUpdateTriggerTest {
    @Test
    fun automaticCheckRunsExactlyOnceAfterFirstVerifiedFrame() = runTest {
        val repository = RecordingRepository()
        val gate = FirstPreviewGate()
        val trigger = FirstPreviewUpdateTrigger(
            gate = gate,
            repository = repository,
            scope = this,
        )

        assertEquals(0, repository.automaticChecks)
        trigger.onFirstVerifiedFrame()
        trigger.onFirstVerifiedFrame()
        trigger.onFirstVerifiedFrame()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.automaticChecks)
        gate.requireVerified()
    }

    @Test
    fun lensSwitchLikeRepeatedVerifiedEventsDoNotRepeatAutomaticCheck() = runTest {
        val repository = RecordingRepository()
        val trigger = FirstPreviewUpdateTrigger(
            gate = FirstPreviewGate(),
            repository = repository,
            scope = this,
        )
        repeat(20) { trigger.onFirstVerifiedFrame() }
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.automaticChecks)
    }

    @Test
    fun manualCheckRemainsIndependentAfterAutomaticCheck() = runTest {
        val repository = RecordingRepository()
        val trigger = FirstPreviewUpdateTrigger(
            gate = FirstPreviewGate(),
            repository = repository,
            scope = this,
        )
        trigger.onFirstVerifiedFrame()
        testScheduler.advanceUntilIdle()
        repository.checkManually()
        repository.checkManually()

        assertEquals(1, repository.automaticChecks)
        assertEquals(2, repository.manualChecks)
    }

    private class RecordingRepository : UpdateRepository {
        private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val state: StateFlow<UpdateState> = mutableState
        var automaticChecks = 0
        var manualChecks = 0

        override suspend fun checkAfterFirstFrame() {
            automaticChecks += 1
        }

        override suspend fun checkManually() {
            manualChecks += 1
        }

        override suspend fun downloadAvailable() = Unit
        override fun cancel() = Unit
        override fun reportInstallFailure(code: UpdateFailureCode) = Unit
    }
}
