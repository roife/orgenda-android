package com.roifewu.orgenda.ui

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object PreviewImageDecoder {
    private val tiffDecode = Mutex()
    private const val MAX_INPUT = 20 * 1024 * 1024
    private const val MAX_SIDE = 2048

    private fun isTiff(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val little =
            bytes[0] == 73.toByte() &&
                bytes[1] == 73.toByte() &&
                bytes[3] == 0.toByte() &&
                bytes[2].toInt() in 42..43
        val big =
            bytes[0] == 77.toByte() &&
                bytes[1] == 77.toByte() &&
                bytes[2] == 0.toByte() &&
                bytes[3].toInt() in 42..43
        return little || big
    }

    suspend fun prepare(bytes: ByteArray): Any {
        require(bytes.size <= MAX_INPUT) { "Image exceeds 20 MiB" }
        if (!isTiff(bytes)) return bytes
        // The decoder's memory allowance is per call. Serialize TIFF work so several
        // inline attachments cannot each reserve the full native allocation budget.
        return tiffDecode.withLock {
            runInterruptible(Dispatchers.IO) {
                val pixels = NativeTiff.decode(bytes, MAX_SIDE)
                Bitmap.createBitmap(
                    pixels,
                    2,
                    pixels[0],
                    pixels[0],
                    pixels[1],
                    Bitmap.Config.ARGB_8888,
                )
            }
        }
    }
}

private object NativeTiff {
    init {
        System.loadLibrary("orgenda_images")
    }

    external fun decode(bytes: ByteArray, maxSide: Int): IntArray
}
