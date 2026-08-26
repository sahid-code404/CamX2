package com.sahidcode404.camx.core.camera.preview

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceIdentityAllocatorTest {
    @Test
    fun separateViewLifetimesCannotReuseAnIdentityWithinOneProcess() {
        val oldViewIdentity = PreviewSurfaceIdentityAllocator.next()
        val recreatedViewIdentity = PreviewSurfaceIdentityAllocator.next()
        assertNotEquals(oldViewIdentity, recreatedViewIdentity)
        assertTrue(recreatedViewIdentity.value > oldViewIdentity.value)
    }
}
