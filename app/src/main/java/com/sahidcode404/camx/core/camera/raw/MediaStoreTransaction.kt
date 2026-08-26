package com.sahidcode404.camx.core.camera.raw

import kotlinx.coroutines.CancellationException

/** Generic insert/write/publish/delete transaction used by the Android MediaStore adapter. */
class MediaStoreTransaction<Row : Any>(
    private val insertPending: () -> Row?,
    private val write: (Row) -> Unit,
    private val publish: (Row) -> Unit,
    private val delete: (Row) -> Unit,
) {
    fun execute(): Result<Row> {
        val row = try {
            checkNotNull(insertPending()) { "MediaStore insert returned no row" }
        } catch (failure: Throwable) {
            failure.rethrowIfNonRecoverable()
            return Result.failure(failure)
        }
        try {
            write(row)
            publish(row)
            return Result.success(row)
        } catch (operationFailure: Throwable) {
            try {
                delete(row)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure.isNonRecoverable() && !operationFailure.isNonRecoverable()) {
                    cleanupFailure.addSuppressed(operationFailure)
                    throw cleanupFailure
                }
                operationFailure.addSuppressed(cleanupFailure)
            }
            operationFailure.rethrowIfNonRecoverable()
            return Result.failure(operationFailure)
        }
    }

    private fun Throwable.rethrowIfNonRecoverable() {
        if (isNonRecoverable()) throw this
    }

    private fun Throwable.isNonRecoverable(): Boolean =
        this is CancellationException || this is VirtualMachineError || this is ThreadDeath
}
