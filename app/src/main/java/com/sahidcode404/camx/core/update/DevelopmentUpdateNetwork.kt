package com.sahidcode404.camx.core.update

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal object DevOtaEndpoints {
    const val MANIFEST_URL =
        "https://github.com/sahid-code404/CamX2/releases/download/dev-latest/dev-manifest.json"
    const val APK_URL =
        "https://github.com/sahid-code404/CamX2/releases/download/dev-latest/CamX-dev.apk"
}

internal object DevelopmentNetworkPolicy {
    const val CONNECT_TIMEOUT_MILLIS = 10_000
    const val READ_TIMEOUT_MILLIS = 20_000
    const val MAX_REDIRECTS = 5

    fun resolveRedirect(current: URL, location: String): URL {
        val next = try {
            URL(current, location)
        } catch (error: IllegalArgumentException) {
            throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR, error)
        }
        requireAllowedHttps(next)
        return next
    }

    fun requireAllowedHttps(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true) || url.userInfo != null) {
            throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR)
        }
        val host = url.host.lowercase()
        if (host != "github.com" && !host.endsWith(".githubusercontent.com")) {
            throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR)
        }
    }
}

internal class DevelopmentNetworkException(
    val code: UpdateFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

internal class DevelopmentHttpResponse(
    val body: InputStream,
    val contentLength: Long?,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            closeAction()
        }
    }
}

internal interface DevelopmentHttpClient {
    fun open(url: String): DevelopmentHttpResponse
    fun cancelActive()
}

internal class HttpsDevelopmentHttpClient : DevelopmentHttpClient {
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val activeResponse = AtomicReference<DevelopmentHttpResponse?>(null)

    override fun open(url: String): DevelopmentHttpResponse {
        var current = try {
            URL(url)
        } catch (error: IllegalArgumentException) {
            throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR, error)
        }
        DevelopmentNetworkPolicy.requireAllowedHttps(current)

        repeat(DevelopmentNetworkPolicy.MAX_REDIRECTS + 1) { redirectCount ->
            val connection = try {
                current.openConnection() as HttpsURLConnection
            } catch (error: Exception) {
                throw DevelopmentNetworkException(UpdateFailureCode.NETWORK, error)
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = DevelopmentNetworkPolicy.CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = DevelopmentNetworkPolicy.READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/octet-stream, application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "CamX2-development-ota/1")
            activeConnection.set(connection)

            val status = try {
                connection.responseCode
            } catch (error: Exception) {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
                throw DevelopmentNetworkException(UpdateFailureCode.NETWORK, error)
            }

            if (status in REDIRECT_CODES) {
                if (redirectCount >= DevelopmentNetworkPolicy.MAX_REDIRECTS) {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                    throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR)
                }
                val location = connection.getHeaderField("Location")
                    ?: run {
                        activeConnection.compareAndSet(connection, null)
                        connection.disconnect()
                        throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR)
                    }
                val next = try {
                    DevelopmentNetworkPolicy.resolveRedirect(current, location)
                } catch (failure: DevelopmentNetworkException) {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                    throw failure
                }
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
                current = next
                return@repeat
            }

            if (status !in 200..299) {
                runCatching { connection.errorStream?.close() }
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
                throw DevelopmentNetworkException(UpdateFailureCode.HTTP_ERROR)
            }

            val length = connection.getHeaderField("Content-Length")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
            val stream = try {
                BufferedInputStream(connection.inputStream)
            } catch (error: Exception) {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
                throw DevelopmentNetworkException(UpdateFailureCode.NETWORK, error)
            }
            lateinit var response: DevelopmentHttpResponse
            response = DevelopmentHttpResponse(
                body = stream,
                contentLength = length,
                closeAction = {
                    activeResponse.compareAndSet(response, null)
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                },
            )
            activeResponse.set(response)
            return response
        }
        throw DevelopmentNetworkException(UpdateFailureCode.REDIRECT_ERROR)
    }

    override fun cancelActive() {
        val response = activeResponse.getAndSet(null)
        if (response != null) {
            response.close()
        } else {
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
