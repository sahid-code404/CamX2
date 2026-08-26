package com.sahidcode404.camx.core.settings

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface SettingsPersistence {
    suspend fun persist(snapshot: SettingsSnapshot)
}

data class SettingsPersistenceFailure(
    val revision: Long,
    val reason: String,
)

class SettingsRepository(
    initial: SettingsSnapshot = SettingsSnapshot(),
    private val persistence: SettingsPersistence,
    persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val memory = AtomicReference(initial)
    private val mutableSettings = MutableStateFlow(initial)
    private val mutablePersistenceFailure = MutableStateFlow<SettingsPersistenceFailure?>(null)
    private val persistenceScope = CoroutineScope(SupervisorJob() + persistenceDispatcher)
    private val pendingWrites = Channel<SettingsSnapshot>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var closed = false

    val settings: StateFlow<SettingsSnapshot> = mutableSettings.asStateFlow()
    val persistenceFailure: StateFlow<SettingsPersistenceFailure?> =
        mutablePersistenceFailure.asStateFlow()

    init {
        persistenceScope.launch {
            for (snapshot in pendingWrites) {
                try {
                    persistence.persist(snapshot)
                    mutablePersistenceFailure.value = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: VirtualMachineError) {
                    throw fatal
                } catch (fatal: ThreadDeath) {
                    throw fatal
                } catch (error: Throwable) {
                    mutablePersistenceFailure.value = SettingsPersistenceFailure(
                        revision = snapshot.revision,
                        reason = error.message?.take(MAX_FAILURE_LENGTH)
                            ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun current(): SettingsSnapshot = memory.get()

    fun update(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot {
        return synchronized(this) {
            check(!closed) { "SettingsRepository is closed" }
            val current = memory.get()
            check(current.revision < Long.MAX_VALUE) { "Settings revision exhausted" }
            transform(current).copy(revision = current.revision + 1L).also {
                memory.set(it)
                mutableSettings.value = it
                check(pendingWrites.trySend(it).isSuccess) {
                    "Settings persistence queue is closed"
                }
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            pendingWrites.close()
        }
        persistenceScope.cancel()
    }

    private companion object {
        const val MAX_FAILURE_LENGTH = 160
    }
}
