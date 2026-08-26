package com.sahidcode404.camx.core.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sahidcode404.camx.BuildConfig
import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DevelopmentUpdateViewModel(
    context: Context,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val firstPreviewGate = FirstPreviewGate()
    private val repository: UpdateRepository
    private val closeableRepository: AutoCloseable?
    private val installer = ApkInstaller(applicationContext)
    private val installLaunchGate = InstallLaunchGate()
    private val installPermissionReturnGate = InstallPermissionReturnGate()

    val enabled: Boolean = BuildConfig.OTA_CHANNEL == DevOtaTrust.CHANNEL
    val installedVersion = InstalledUpdateVersion(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )
    val state: StateFlow<UpdateState>

    private val firstPreviewTrigger: FirstPreviewUpdateTrigger

    init {
        if (enabled) {
            val developmentRepository = DevelopmentUpdateRepository.create(
                applicationContext,
                firstPreviewGate,
            )
            repository = developmentRepository
            closeableRepository = developmentRepository
            state = developmentRepository.state
        } else {
            val disabledState = MutableStateFlow<UpdateState>(UpdateState.Idle)
            repository = object : UpdateRepository {
                override val state: StateFlow<UpdateState> = disabledState
                override suspend fun checkAfterFirstFrame() = Unit
                override suspend fun checkManually() = Unit
                override suspend fun downloadAvailable() = Unit
                override fun cancel() = Unit
                override fun reportInstallFailure(code: UpdateFailureCode) = Unit
            }
            closeableRepository = null
            state = disabledState
        }
        firstPreviewTrigger = FirstPreviewUpdateTrigger(
            gate = firstPreviewGate,
            repository = repository,
            scope = scope,
        )
    }

    fun onFirstVerifiedFrame() {
        if (enabled) firstPreviewTrigger.onFirstVerifiedFrame()
    }

    fun checkManually() {
        if (!enabled) return
        scope.launch { repository.checkManually() }
    }

    fun downloadAvailable() {
        if (!enabled) return
        scope.launch { repository.downloadAvailable() }
    }

    fun cancel() {
        if (enabled) repository.cancel()
    }

    fun installReadyUpdate() {
        if (!enabled) return
        val ready = state.value as? UpdateState.ReadyToInstall ?: return
        if (!installLaunchGate.tryStart()) return
        scope.launch {
            try {
                when (installer.requestInstall(ready.apk)) {
                    ApkInstallRequestResult.UNKNOWN_SOURCE_PERMISSION_REQUIRED -> {
                        installPermissionReturnGate.markAwaiting()
                        installLaunchGate.reset()
                    }
                    ApkInstallRequestResult.INSTALLER_LAUNCHED -> {
                        installPermissionReturnGate.clear()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                installLaunchGate.reset()
                repository.reportInstallFailure(UpdateFailureCode.APK_INSPECTION_FAILED)
            } catch (_: IllegalStateException) {
                installLaunchGate.reset()
                repository.reportInstallFailure(UpdateFailureCode.APK_INSPECTION_FAILED)
            } catch (_: IllegalArgumentException) {
                installLaunchGate.reset()
                repository.reportInstallFailure(UpdateFailureCode.APK_INSPECTION_FAILED)
            } catch (_: SecurityException) {
                installLaunchGate.reset()
                repository.reportInstallFailure(UpdateFailureCode.INSTALLER_UNAVAILABLE)
            } catch (_: android.content.ActivityNotFoundException) {
                installLaunchGate.reset()
                repository.reportInstallFailure(UpdateFailureCode.INSTALLER_UNAVAILABLE)
            }
        }
    }

    fun onHostResumed() {
        if (!enabled) return
        when (installPermissionReturnGate.onHostResume(installer.canRequestPackageInstalls())) {
            InstallPermissionResumeResult.STILL_WAITING -> return
            InstallPermissionResumeResult.PERMISSION_GRANTED,
            InstallPermissionResumeResult.NOT_WAITING,
            -> {
                // Resume never mutates UpdateState and never auto-launches installation. In the
                // permission case ReadyToInstall stays bound to the same VerifiedApk until the
                // user explicitly taps Install again.
                installLaunchGate.reset()
            }
        }
    }

    override fun onCleared() {
        closeableRepository?.close()
        scope.cancel()
        super.onCleared()
    }
}

internal enum class InstallPermissionResumeResult {
    NOT_WAITING,
    STILL_WAITING,
    PERMISSION_GRANTED,
}

internal class InstallPermissionReturnGate {
    private var awaitingPermission = false

    fun markAwaiting() {
        awaitingPermission = true
    }

    fun clear() {
        awaitingPermission = false
    }

    fun onHostResume(canRequestPackageInstalls: Boolean): InstallPermissionResumeResult {
        if (!awaitingPermission) return InstallPermissionResumeResult.NOT_WAITING
        if (!canRequestPackageInstalls) return InstallPermissionResumeResult.STILL_WAITING
        awaitingPermission = false
        return InstallPermissionResumeResult.PERMISSION_GRANTED
    }
}

internal class InstallLaunchGate {
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryStart(): Boolean = inFlight.compareAndSet(false, true)

    fun reset() {
        inFlight.set(false)
    }
}

class DevelopmentUpdateViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DevelopmentUpdateViewModel::class.java)) {
            "Unsupported ViewModel ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return DevelopmentUpdateViewModel(context.applicationContext) as T
    }
}
