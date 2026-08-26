package com.sahidcode404.camx.core.camera.session

import android.util.Size

/** Nullable platform-array helpers for CameraCharacteristics/StreamConfigurationMap Java APIs. */
internal fun IntArray?.orEmpty(): IntArray = this ?: IntArray(0)

internal fun Array<Size>?.orEmpty(): Array<Size> = this ?: emptyArray()
