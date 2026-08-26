package com.sahidcode404.camx.core.settings

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun memoryChangesBeforeAsynchronousPersistenceCompletes() {
        val persisted = AtomicReference<SettingsSnapshot?>(null)
        val latch = CountDownLatch(1)
        val repository = SettingsRepository(
            persistence = SettingsPersistence { snapshot ->
                persisted.set(snapshot)
                latch.countDown()
            },
        )
        try {
            val updated = repository.update { current ->
                current.copy(highResolutionViewfinder = true)
            }
            assertTrue(repository.current().highResolutionViewfinder)
            assertEquals(1L, updated.revision)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(updated, persisted.get())
        } finally {
            repository.close()
        }
    }
}
