package com.sahidcode404.camx.core.update

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DevelopmentNetworkPolicyTest {
    @Test
    fun endpointsAreFixedHttpsDevLatestAssets() {
        assertEquals(
            "https://github.com/sahid-code404/CamX/releases/download/dev-latest/dev-manifest.json",
            DevOtaEndpoints.MANIFEST_URL,
        )
        assertEquals(
            "https://github.com/sahid-code404/CamX/releases/download/dev-latest/CamX-dev.apk",
            DevOtaEndpoints.APK_URL,
        )
        DevelopmentNetworkPolicy.requireAllowedHttps(URL(DevOtaEndpoints.MANIFEST_URL))
        DevelopmentNetworkPolicy.requireAllowedHttps(URL(DevOtaEndpoints.APK_URL))
    }

    @Test
    fun normalGithubHttpsReleaseRedirectIsAccepted() {
        val current = URL(DevOtaEndpoints.APK_URL)
        val resolved = DevelopmentNetworkPolicy.resolveRedirect(
            current,
            "https://release-assets.githubusercontent.com/github-production-release-asset/test",
        )
        assertEquals("https", resolved.protocol)
        assertEquals("release-assets.githubusercontent.com", resolved.host)
    }

    @Test
    fun httpDowngradeRedirectIsRejected() {
        val failure = assertThrows(DevelopmentNetworkException::class.java) {
            DevelopmentNetworkPolicy.resolveRedirect(
                URL(DevOtaEndpoints.APK_URL),
                "http://release-assets.githubusercontent.com/unsafe",
            )
        }
        assertEquals(UpdateFailureCode.REDIRECT_ERROR, failure.code)
    }

    @Test
    fun nonGithubRedirectHostIsRejected() {
        val failure = assertThrows(DevelopmentNetworkException::class.java) {
            DevelopmentNetworkPolicy.resolveRedirect(
                URL(DevOtaEndpoints.APK_URL),
                "https://example.com/foreign.apk",
            )
        }
        assertEquals(UpdateFailureCode.REDIRECT_ERROR, failure.code)
    }
}
