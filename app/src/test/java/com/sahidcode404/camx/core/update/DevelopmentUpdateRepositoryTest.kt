package com.sahidcode404.camx.core.update

import com.sahidcode404.camx.core.update.verification.DevOtaTrust
import com.sahidcode404.camx.core.update.verification.InstalledAppIdentity
import com.sahidcode404.camx.core.update.verification.VerifiedApk
import com.sahidcode404.camx.core.update.verification.VerifiedApkPromotion
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DevelopmentUpdateRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun automaticRepositoryCheckCannotRunBeforeFirstVerifiedPreview() = runTest {
        val apkBytes = "unused".toByteArray()
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(Plan.Data(manifestBytes(manifest)))
        val store = UpdateFileStore(temporaryFolder.newFolder())
        val repository = DevelopmentUpdateRepository(
            firstPreviewGate = FirstPreviewGate(),
            installedIdentitySource = InstalledIdentitySource {
                InstalledAppIdentity(
                    applicationId = DevOtaTrust.APPLICATION_ID,
                    versionCode = 100L,
                    signingCertSha256 = DevOtaTrust.CERT_SHA256,
                    sdkInt = 35,
                )
            },
            httpClient = client,
            fileStore = store,
            candidateVerifier = DownloadedCandidateVerifier { _, _ ->
                error("APK verification must not run during manifest check")
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = runCatching { repository.checkAfterFirstFrame() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, client.openCalls)
        assertTrue(repository.state.value is UpdateState.Idle)
    }

    @Test
    fun validManifestThenKnownLengthDownloadBecomesReadyToInstall() = runTest {
        val apkBytes = "known-length-apk".toByteArray()
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest), contentLength = manifestBytes(manifest).size.toLong()),
            Plan.Data(apkBytes, contentLength = apkBytes.size.toLong()),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        assertTrue(fixture.repository.state.value is UpdateState.Available)
        fixture.repository.downloadAvailable()

        val ready = fixture.repository.state.value as UpdateState.ReadyToInstall
        assertEquals(manifest.versionCode, ready.manifest.versionCode)
        assertEquals(2, client.openCalls)
        assertFalse(fixture.store.partFile.exists())
        assertTrue(fixture.store.verifiedFile.isFile)
    }

    @Test
    fun unknownContentLengthStillStreamsAndVerifies() = runTest {
        val apkBytes = "unknown-length-apk".toByteArray()
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(apkBytes, contentLength = null),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertTrue(fixture.repository.state.value is UpdateState.ReadyToInstall)
        assertEquals(apkBytes.size.toLong(), fixture.store.verifiedFile.length())
    }

    @Test
    fun knownLengthPublishesBoundedProgressBeforeFinalVerification() = runTest {
        val apkBytes = ByteArray(600_000) { 9 }
        val manifest = manifest(apkBytes)
        val verifierGate = CompletableDeferred<Unit>()
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(apkBytes, contentLength = apkBytes.size.toLong()),
        )
        val fixture = fixture(client, verifierGate = verifierGate)
        fixture.repository.checkManually()

        val download = launch { fixture.repository.downloadAvailable() }
        testScheduler.runCurrent()

        val downloading = fixture.repository.state.value as UpdateState.Downloading
        assertEquals(apkBytes.size.toLong(), downloading.downloadedBytes)
        assertEquals(apkBytes.size.toLong(), downloading.totalBytes)
        verifierGate.complete(Unit)
        download.join()
        assertTrue(fixture.repository.state.value is UpdateState.ReadyToInstall)
    }

    @Test
    fun declaredTooLargeIsRejectedBeforeStreamingAndPartIsDeleted() = runTest {
        val apkBytes = ByteArray(16) { 7 }
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(apkBytes, contentLength = 1_025L),
        )
        val fixture = fixture(client, maxApkBytes = 1_024L)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertFailure(fixture.repository, UpdateFailureCode.DOWNLOAD_TOO_LARGE)
        assertFalse(fixture.store.partFile.exists())
    }

    @Test
    fun actualBytesOverBoundAreRejectedEvenWithoutContentLength() = runTest {
        val apkBytes = ByteArray(1_025) { 3 }
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(apkBytes, contentLength = null),
        )
        val fixture = fixture(client, maxApkBytes = 1_024L)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertFailure(fixture.repository, UpdateFailureCode.DOWNLOAD_TOO_LARGE)
        assertFalse(fixture.store.partFile.exists())
    }

    @Test
    fun truncatedNetworkStreamFailsClosed() = runTest {
        val apkBytes = "truncated".toByteArray()
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(apkBytes, contentLength = apkBytes.size.toLong() + 20L),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertFailure(fixture.repository, UpdateFailureCode.TRUNCATED_RESPONSE)
        assertFalse(fixture.store.partFile.exists())
    }

    @Test
    fun networkReadExceptionIsTypedAndDoesNotExposeCandidate() = runTest {
        val expectedApk = "expected".toByteArray()
        val manifest = manifest(expectedApk)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Stream(ThrowingInputStream(), contentLength = null),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertFailure(fixture.repository, UpdateFailureCode.NETWORK)
        assertFalse(fixture.store.partFile.exists())
        assertFalse(fixture.store.verifiedFile.exists())
    }

    @Test
    fun httpFailureUsesStableFailureCode() = runTest {
        val expectedApk = "expected".toByteArray()
        val manifest = manifest(expectedApk)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Failure(UpdateFailureCode.HTTP_ERROR),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertFailure(fixture.repository, UpdateFailureCode.HTTP_ERROR)
    }

    @Test
    fun mixedRollingGenerationFailsDigestVerification() = runTest {
        val manifestApk = "generation-a".toByteArray()
        val rollingApk = "generation-b".toByteArray()
        val manifest = manifest(manifestApk)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(rollingApk),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()

        assertFailure(fixture.repository, UpdateFailureCode.SHA256_MISMATCH)
        assertFalse(fixture.store.verifiedFile.exists())
    }

    @Test
    fun cancellationClosesStreamDeletesPartAndProducesCancelledState() = runTest {
        val expectedApk = "cancel-target".toByteArray()
        val manifest = manifest(expectedApk)
        val blocking = BlockingInputStream()
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Stream(blocking, contentLength = null),
            Plan.Data(expectedApk, contentLength = expectedApk.size.toLong()),
        )
        val fixture = fixture(
            client = client,
            ioDispatcher = Dispatchers.IO,
        )
        fixture.repository.checkManually()

        val download = launch { fixture.repository.downloadAvailable() }
        withTimeout(5_000L) {
            while (!blocking.readStarted()) yield()
        }
        fixture.repository.cancel()
        download.join()

        assertFailure(fixture.repository, UpdateFailureCode.CANCELLED)
        assertTrue(blocking.closed())
        assertFalse(fixture.store.partFile.exists())

        fixture.repository.downloadAvailable()
        assertTrue(fixture.repository.state.value is UpdateState.ReadyToInstall)
    }

    @Test
    fun secondDownloadCannotStartWhileFirstIsActive() = runTest {
        val expectedApk = "concurrency-target".toByteArray()
        val manifest = manifest(expectedApk)
        val blocking = BlockingInputStream()
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Stream(blocking, contentLength = null),
        )
        val fixture = fixture(
            client = client,
            ioDispatcher = Dispatchers.IO,
        )
        fixture.repository.checkManually()

        val first = launch { fixture.repository.downloadAvailable() }
        withTimeout(5_000L) {
            while (!blocking.readStarted()) yield()
        }
        fixture.repository.downloadAvailable()
        fixture.repository.checkManually()
        assertEquals(2, client.openCalls)
        fixture.repository.cancel()
        first.join()
    }

    @Test
    fun malformedAndOversizedManifestNeverStartApkDownload() = runTest {
        val malformedClient = FakeHttpClient(
            Plan.Data("{not-json".toByteArray()),
        )
        val malformed = fixture(malformedClient)
        malformed.repository.checkManually()
        assertFailure(malformed.repository, UpdateFailureCode.MALFORMED_MANIFEST)
        assertEquals(1, malformedClient.openCalls)

        val oversized = ByteArray(DevelopmentManifestValidator.MAX_MANIFEST_BYTES + 1) { 'x'.code.toByte() }
        val oversizedClient = FakeHttpClient(Plan.Data(oversized, contentLength = null))
        val tooLarge = fixture(oversizedClient)
        tooLarge.repository.checkManually()
        assertFailure(tooLarge.repository, UpdateFailureCode.MANIFEST_TOO_LARGE)
        assertEquals(1, oversizedClient.openCalls)
    }

    @Test
    fun missingRequiredManifestFieldsAreMalformed() = runTest {
        val client = FakeHttpClient(
            Plan.Data("""{"schema":1,"channel":"development"}""".toByteArray()),
        )
        val fixture = fixture(client)
        fixture.repository.checkManually()
        assertFailure(fixture.repository, UpdateFailureCode.MALFORMED_MANIFEST)
    }

    @Test
    fun declaredOversizedManifestFailsBeforeBodyRead() = runTest {
        val client = FakeHttpClient(
            Plan.Data(
                "{}".toByteArray(),
                contentLength = DevelopmentManifestValidator.MAX_MANIFEST_BYTES.toLong() + 1L,
            ),
        )
        val fixture = fixture(client)
        fixture.repository.checkManually()
        assertFailure(fixture.repository, UpdateFailureCode.MANIFEST_TOO_LARGE)
    }

    @Test
    fun normalSameOrOlderVersionCheckIsUpToDateNotFailure() = runTest {
        for (version in listOf(100L, 99L)) {
            val apkBytes = "unused".toByteArray()
            val manifest = manifest(apkBytes).copy(
                versionCode = version,
                versionName = "0.1.0-dev.$version",
            )
            val client = FakeHttpClient(Plan.Data(manifestBytes(manifest)))
            val fixture = fixture(client)
            fixture.repository.checkManually()
            assertTrue(fixture.repository.state.value is UpdateState.UpToDate)
        }
    }

    @Test
    fun failedDownloadCanRetryCleanlyAndReadyStateWillNotRedownloadSameVersion() = runTest {
        val apkBytes = "retry-apk".toByteArray()
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Failure(UpdateFailureCode.NETWORK),
            Plan.Data(apkBytes),
        )
        val fixture = fixture(client)

        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()
        assertFailure(fixture.repository, UpdateFailureCode.NETWORK)
        fixture.repository.downloadAvailable()
        assertTrue(fixture.repository.state.value is UpdateState.ReadyToInstall)

        val opens = client.openCalls
        fixture.repository.downloadAvailable()
        assertEquals(opens, client.openCalls)
    }

    @Test
    fun staleCancelledOperationCannotOverwriteNewerSuccessfulCheck() = runTest {
        val apkBytes = "unused".toByteArray()
        val newer = manifest(apkBytes)
        val blocking = BlockingInputStream()
        val client = FakeHttpClient(
            Plan.Stream(blocking, contentLength = null),
            Plan.Data(manifestBytes(newer)),
        )
        val fixture = fixture(client, ioDispatcher = Dispatchers.IO)

        val first = launch { fixture.repository.checkManually() }
        withTimeout(5_000L) {
            while (!blocking.readStarted()) yield()
        }
        fixture.repository.cancel()
        first.join()
        assertFailure(fixture.repository, UpdateFailureCode.CANCELLED)

        fixture.repository.checkManually()
        assertTrue(fixture.repository.state.value is UpdateState.Available)
    }

    @Test
    fun candidateVerifierFailureRemovesPromotedFile() = runTest {
        val apkBytes = "verification-failure".toByteArray()
        val manifest = manifest(apkBytes)
        val client = FakeHttpClient(
            Plan.Data(manifestBytes(manifest)),
            Plan.Data(apkBytes),
        )
        val fixture = fixture(
            client = client,
            verifierCode = UpdateFailureCode.SIGNATURE_MISMATCH,
        )
        fixture.repository.checkManually()
        fixture.repository.downloadAvailable()
        assertFailure(fixture.repository, UpdateFailureCode.SIGNATURE_MISMATCH)
        assertFalse(fixture.store.verifiedFile.exists())
    }

    private fun fixture(
        client: FakeHttpClient,
        maxApkBytes: Long = DevOtaTrust.MAX_APK_BYTES,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        verifierCode: UpdateFailureCode? = null,
        verifierGate: CompletableDeferred<Unit>? = null,
    ): Fixture {
        val store = UpdateFileStore(temporaryFolder.newFolder())
        val gate = FirstPreviewGate().apply { markVerified() }
        val verifier = DownloadedCandidateVerifier { candidate, manifest ->
            verifierGate?.await()
            if (verifierCode != null) {
                VerifiedApkPromotion.Rejected(verifierCode)
            } else {
                VerifiedApkPromotion.Ready(fakeVerifiedApk(candidate, store.verifiedDirectory, manifest.sha256))
            }
        }
        val repository = DevelopmentUpdateRepository(
            firstPreviewGate = gate,
            installedIdentitySource = InstalledIdentitySource {
                InstalledAppIdentity(
                    applicationId = DevOtaTrust.APPLICATION_ID,
                    versionCode = 100L,
                    signingCertSha256 = DevOtaTrust.CERT_SHA256,
                    sdkInt = 35,
                )
            },
            httpClient = client,
            fileStore = store,
            candidateVerifier = verifier,
            ioDispatcher = ioDispatcher,
            maxApkBytes = maxApkBytes,
        )
        return Fixture(repository, store)
    }

    private fun manifest(apkBytes: ByteArray): DevOtaManifest = DevOtaManifest(
        schema = DevOtaTrust.SCHEMA,
        channel = DevOtaTrust.CHANNEL,
        applicationId = DevOtaTrust.APPLICATION_ID,
        versionCode = 101L,
        versionName = "0.1.0-dev.101",
        minSdk = DevOtaTrust.APPLICATION_MIN_SDK,
        apkAssetName = DevOtaTrust.APK_ASSET_NAME,
        sha256 = apkBytes.digest(),
        signingCertSha256 = DevOtaTrust.CERT_SHA256,
        gitSha = "a".repeat(40),
        buildTimestamp = "2026-08-25T00:00:00Z",
        changelog = "CAMX-111",
        mandatory = false,
    )

    private fun manifestBytes(manifest: DevOtaManifest): ByteArray =
        JSON.encodeToString(manifest).toByteArray()

    private fun assertFailure(repository: DevelopmentUpdateRepository, code: UpdateFailureCode) {
        val state = repository.state.value as UpdateState.Failed
        assertEquals(code, state.code)
    }

    private fun fakeVerifiedApk(
        file: File,
        directory: File,
        digest: String,
    ): VerifiedApk {
        val constructor = VerifiedApk::class.java.declaredConstructors
            .single { it.parameterTypes.size == 5 }
        constructor.isAccessible = true
        return constructor.newInstance(
            file.canonicalFile,
            directory.canonicalFile,
            digest,
            file.length(),
            file.lastModified(),
        ) as VerifiedApk
    }

    private data class Fixture(
        val repository: DevelopmentUpdateRepository,
        val store: UpdateFileStore,
    )

    private sealed interface Plan {
        data class Data(
            val bytes: ByteArray,
            val contentLength: Long? = bytes.size.toLong(),
        ) : Plan
        data class Stream(
            val input: InputStream,
            val contentLength: Long?,
        ) : Plan
        data class Failure(val code: UpdateFailureCode) : Plan
    }

    private class FakeHttpClient(vararg initial: Plan) : DevelopmentHttpClient {
        private val plans = ArrayDeque(initial.toList())
        private var active: DevelopmentHttpResponse? = null
        var openCalls = 0
            private set

        override fun open(url: String): DevelopmentHttpResponse {
            openCalls += 1
            return when (val plan = plans.removeFirst()) {
                is Plan.Failure -> throw DevelopmentNetworkException(plan.code)
                is Plan.Data -> response(ByteArrayInputStream(plan.bytes), plan.contentLength)
                is Plan.Stream -> response(plan.input, plan.contentLength)
            }
        }

        override fun cancelActive() {
            val response = active
            active = null
            response?.close()
        }

        private fun response(input: InputStream, contentLength: Long?): DevelopmentHttpResponse {
            lateinit var response: DevelopmentHttpResponse
            response = DevelopmentHttpResponse(input, contentLength) {
                if (active === response) active = null
            }
            active = response
            return response
        }
    }

    private class ThrowingInputStream : InputStream() {
        override fun read(): Int = throw IOException("network failure")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("network failure")
    }

    private class BlockingInputStream : InputStream() {
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile private var isClosed = false

        override fun read(): Int {
            started.countDown()
            released.await()
            throw IOException("closed")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            isClosed = true
            released.countDown()
        }

        fun readStarted(): Boolean = started.count == 0L
        fun closed(): Boolean = isClosed
    }

    private fun ByteArray.digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val JSON = Json
    }
}
