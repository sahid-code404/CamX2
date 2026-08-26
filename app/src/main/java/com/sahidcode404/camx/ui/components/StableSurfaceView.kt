package com.sahidcode404.camx.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentityAllocator

@Composable
fun StableSurfaceView(
    modifier: Modifier = Modifier,
    bufferSize: IntSize?,
    geometry: PreviewGeometry?,
    onSurfaceAvailable: (PreviewSurfaceBinding) -> Unit,
    onSurfaceDestroyed: (PreviewSurfaceIdentity) -> Unit,
) {
    val context = LocalContext.current
    val host = remember(context) {
        FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }
    }
    val surfaceView = remember(host) {
        SurfaceView(context).also { view ->
            view.setZOrderOnTop(false)
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }
    val presentation = remember(surfaceView) { StableSurfacePresentationState() }
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val callback = remember(surfaceView, host) {
        object : SurfaceHolder.Callback {
            private var activeIdentity: PreviewSurfaceIdentity? = null

            override fun surfaceCreated(holder: SurfaceHolder) {
                ensureIdentity()
                publishCurrent(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                ensureIdentity()
                publishCurrent(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                destroyCurrent()
            }

            fun destroyCurrent() {
                val destroyedIdentity = activeIdentity ?: return
                activeIdentity = null
                currentDestroyed(destroyedIdentity)
            }

            fun publishCurrent(
                holder: SurfaceHolder,
                reportedBufferWidth: Int = holder.surfaceFrame.width(),
                reportedBufferHeight: Int = holder.surfaceFrame.height(),
            ) {
                ensureIdentity()
                val viewWidth = host.width
                val viewHeight = host.height
                if (
                    holder.surface.isValid &&
                    viewWidth > 0 && viewHeight > 0 &&
                    reportedBufferWidth > 0 && reportedBufferHeight > 0
                ) {
                    currentAvailable(
                        PreviewSurfaceBinding(
                            surface = holder.surface,
                            viewSize = IntSize(viewWidth, viewHeight),
                            identity = checkNotNull(activeIdentity),
                            bufferSize = IntSize(reportedBufferWidth, reportedBufferHeight),
                        ),
                    )
                }
            }

            private fun ensureIdentity() {
                if (activeIdentity != null) return
                activeIdentity = PreviewSurfaceIdentityAllocator.next()
            }
        }
    }
    val layoutListener = remember(callback, surfaceView) {
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (
                right - left != oldRight - oldLeft ||
                bottom - top != oldBottom - oldTop
            ) {
                callback.publishCurrent(surfaceView.holder)
            }
        }
    }

    DisposableEffect(surfaceView, callback, host, layoutListener) {
        surfaceView.holder.addCallback(callback)
        host.addOnLayoutChangeListener(layoutListener)
        if (surfaceView.holder.surface.isValid) callback.publishCurrent(surfaceView.holder)
        onDispose {
            callback.destroyCurrent()
            host.removeOnLayoutChangeListener(layoutListener)
            surfaceView.holder.removeCallback(callback)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { host },
        update = {
            presentation.apply(
                surfaceView = surfaceView,
                bufferSize = bufferSize,
                geometry = geometry,
            )
        },
    )
}

private class StableSurfacePresentationState {
    private var fixedBufferSize: IntSize? = null

    fun apply(
        surfaceView: SurfaceView,
        bufferSize: IntSize?,
        geometry: PreviewGeometry?,
    ) {
        if (bufferSize == null || geometry == null) {
            if (fixedBufferSize != null) {
                surfaceView.holder.setSizeFromLayout()
                fixedBufferSize = null
            }
            updateLayout(surfaceView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            surfaceView.pivotX = surfaceView.width / 2f
            surfaceView.pivotY = surfaceView.height / 2f
            surfaceView.rotation = 0f
            surfaceView.scaleX = 1f
            surfaceView.scaleY = 1f
            surfaceView.translationX = 0f
            surfaceView.translationY = 0f
            return
        }

        if (fixedBufferSize != bufferSize) {
            surfaceView.holder.setFixedSize(bufferSize.width, bufferSize.height)
            fixedBufferSize = bufferSize
        }
        val transform = calculateStableSurfaceTransform(bufferSize, geometry)
        updateLayout(surfaceView, transform.layoutSize.width, transform.layoutSize.height)
        surfaceView.pivotX = transform.layoutSize.width / 2f
        surfaceView.pivotY = transform.layoutSize.height / 2f
        surfaceView.rotation = transform.clockwiseRotationDegrees.toFloat()
        surfaceView.scaleX = transform.scaleX
        surfaceView.scaleY = transform.scaleY
        surfaceView.translationX = transform.translationX
        surfaceView.translationY = transform.translationY
    }

    private fun updateLayout(surfaceView: SurfaceView, width: Int, height: Int) {
        val params = surfaceView.layoutParams
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        surfaceView.layoutParams = params
    }
}
