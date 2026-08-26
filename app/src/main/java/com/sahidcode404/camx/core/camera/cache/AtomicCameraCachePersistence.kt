package com.sahidcode404.camx.core.camera.cache

import android.system.Os
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/** API-23-safe two-file persistence. Hot and topology records never depend on each other's decode. */
class AtomicCameraCachePersistence internal constructor(
    private val directory: File,
    private val fileSystem: CacheFileSystem = RealCacheFileSystem,
) : CameraCachePersistence {
    constructor(directory: File) : this(directory, RealCacheFileSystem)

    override suspend fun readHot(environment: CameraEnvironmentFingerprint): CacheRead<HotStartSnapshot> =
        readBounded(hotFile, CacheBounds.HOT_FILE_BYTES) { HotStartCacheCodec.decode(it, environment) }

    override suspend fun readTopology(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<CameraTopologySnapshot> =
        readBounded(topologyFile, CacheBounds.TOPOLOGY_FILE_BYTES) {
            TopologyCacheCodec.decode(it, environment)
        }

    override suspend fun writeHot(snapshot: HotStartSnapshot): CacheWrite =
        encodeAndWrite(hotFile, hotTempFile) { HotStartCacheCodec.encode(snapshot) }

    override suspend fun writeTopology(snapshot: CameraTopologySnapshot): CacheWrite =
        encodeAndWrite(topologyFile, topologyTempFile) { TopologyCacheCodec.encode(snapshot) }

    private val hotFile: File get() = File(directory, HOT_FILE_NAME)
    private val topologyFile: File get() = File(directory, TOPOLOGY_FILE_NAME)
    private val hotTempFile: File get() = File(directory, "$HOT_FILE_NAME.tmp")
    private val topologyTempFile: File get() = File(directory, "$TOPOLOGY_FILE_NAME.tmp")

    private fun <T> readBounded(
        file: File,
        maximumBytes: Int,
        decode: (ByteArray) -> CacheRead<T>,
    ): CacheRead<T> {
        return try {
            if (!fileSystem.exists(file)) return CacheRead.Miss
            val length = fileSystem.length(file)
            if (length <= 0L || length > maximumBytes.toLong()) {
                return CacheRead.Corrupt("Cache file size is outside bounds")
            }
            val bytes = ByteArray(length.toInt())
            fileSystem.openInput(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) return CacheRead.Corrupt("Cache file is truncated")
                    if (read == 0) continue
                    offset += read
                }
                if (input.read() != -1) return CacheRead.Corrupt("Cache file changed while being read")
            }
            decode(bytes)
        } catch (error: Exception) {
            CacheRead.IoFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun encodeAndWrite(
        authoritative: File,
        temporary: File,
        encode: () -> ByteArray,
    ): CacheWrite {
        val bytes = try {
            encode()
        } catch (error: IllegalArgumentException) {
            return CacheWrite.Rejected(error.message ?: "Snapshot exceeds cache format")
        }
        return writeAtomically(authoritative, temporary, bytes)
    }

    private fun writeAtomically(authoritative: File, temporary: File, bytes: ByteArray): CacheWrite {
        var sink: CacheOutputSink? = null
        var closeAttempted = false
        return try {
            fileSystem.ensureDirectory(directory)
            if (fileSystem.exists(temporary) && !fileSystem.delete(temporary)) {
                return CacheWrite.IoFailure("Cannot remove abandoned cache temp file")
            }
            sink = fileSystem.openOutput(temporary)
            sink.write(bytes)
            sink.flush()
            sink.sync()
            closeAttempted = true
            sink.close()
            sink = null
            fileSystem.rename(temporary, authoritative)
            CacheWrite.Success
        } catch (error: Exception) {
            try {
                if (!closeAttempted) sink?.close()
            } catch (_: Exception) {
                // The write already failed; cleanup below remains best-effort and never touches authority.
            }
            try {
                if (fileSystem.exists(temporary)) fileSystem.delete(temporary)
            } catch (_: Exception) {
                // Preserve the original failure. A later write will reject or remove the abandoned temp.
            }
            CacheWrite.IoFailure(error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        const val HOT_FILE_NAME = "camx-hot.cache"
        const val TOPOLOGY_FILE_NAME = "camx-topology.cache"
    }
}

internal interface CacheOutputSink {
    fun write(bytes: ByteArray)
    fun flush()
    fun sync()
    fun close()
}

internal interface CacheFileSystem {
    fun ensureDirectory(directory: File)
    fun exists(file: File): Boolean
    fun length(file: File): Long
    fun openInput(file: File): InputStream
    fun openOutput(file: File): CacheOutputSink
    fun rename(source: File, destination: File)
    fun delete(file: File): Boolean
}

internal object RealCacheFileSystem : CacheFileSystem {
    override fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) {
            throw java.io.IOException("Cannot create cache directory")
        }
    }

    override fun exists(file: File): Boolean = file.exists()

    override fun length(file: File): Long = file.length()

    override fun openInput(file: File): InputStream = FileInputStream(file)

    override fun openOutput(file: File): CacheOutputSink = FileCacheOutputSink(FileOutputStream(file, false))

    override fun rename(source: File, destination: File) {
        Os.rename(source.absolutePath, destination.absolutePath)
    }

    override fun delete(file: File): Boolean = !file.exists() || file.delete()
}

private class FileCacheOutputSink(private val output: FileOutputStream) : CacheOutputSink {
    override fun write(bytes: ByteArray) = output.write(bytes)
    override fun flush() = output.flush()
    override fun sync() = output.fd.sync()
    override fun close() = output.close()
}
