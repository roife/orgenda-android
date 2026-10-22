package com.roifewu.orgenda.ui

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewImageDecoderTest {
    private val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)

    private fun tiff(
        payload: ByteArray = rgb,
        compression: Int = 1,
        orientation: Int = 1,
        big: Boolean = false,
        width: Int = 2,
        height: Int = 2,
    ): ByteArray {
        val bitsOffset = 8 + 2 + 10 * 12 + 4
        val dataOffset = bitsOffset + 6
        val buffer =
            ByteBuffer.allocate(dataOffset + payload.size)
                .order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        buffer
            .put(if (big) 'M'.code.toByte() else 'I'.code.toByte())
            .put(if (big) 'M'.code.toByte() else 'I'.code.toByte())
            .putShort(42)
            .putInt(8)
            .putShort(10)
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            buffer.putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            if (type == 3 && count == 1) buffer.putShort(value.toShort()).putShort(0)
            else buffer.putInt(value)
        }
        entry(256, 4, 1, width)
        entry(257, 4, 1, height)
        entry(258, 3, 3, bitsOffset)
        entry(259, 3, 1, compression)
        entry(262, 3, 1, if (compression == 7) 6 else 2)
        entry(273, 4, 1, dataOffset)
        entry(274, 3, 1, orientation)
        entry(277, 3, 1, 3)
        entry(278, 4, 1, height)
        entry(279, 4, 1, payload.size)
        buffer.putInt(0).putShort(8).putShort(8).putShort(8).put(payload)
        return buffer.array()
    }

    private suspend fun decode(bytes: ByteArray) = PreviewImageDecoder.prepare(bytes) as Bitmap

    private fun pixels(bitmap: Bitmap): List<Int> =
        (0 until bitmap.height).flatMap { y ->
            (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) }
        }

    @Test
    fun decodesBothByteOrdersAndEveryOrientation() = runBlocking {
        val orders =
            listOf(
                listOf(0, 1, 2, 3),
                listOf(1, 0, 3, 2),
                listOf(3, 2, 1, 0),
                listOf(2, 3, 0, 1),
                listOf(0, 2, 1, 3),
                listOf(2, 0, 3, 1),
                listOf(3, 1, 2, 0),
                listOf(1, 3, 0, 2),
            )
        for (big in listOf(false, true)) for (orientation in 1..8) {
            val bitmap = decode(tiff(orientation = orientation, big = big))
            assertEquals(
                "orientation $orientation, big endian $big",
                orders[orientation - 1].map { colors[it] },
                pixels(bitmap),
            )
            bitmap.recycle()
        }
        val portrait = decode(tiff(rgb.copyOf(6), orientation = 6, width = 2, height = 1))
        assertEquals(1, portrait.width)
        assertEquals(2, portrait.height)
        assertEquals(listOf(Color.RED, Color.GREEN), pixels(portrait))
        portrait.recycle()
    }

    @Test
    fun decodesDeflatePackBitsLzwAndJpeg() = runBlocking {
        val deflate =
            ByteArrayOutputStream()
                .also { output -> DeflaterOutputStream(output).use { it.write(rgb) } }
                .toByteArray()
        val codes = listOf(256) + rgb.map { it.toInt() and 255 } + 257
        val lzw = ByteArray((codes.size * 9 + 7) / 8)
        codes.forEachIndexed { index, code ->
            for (bit in 0..8) if (code and (1 shl (8 - bit)) != 0) {
                val offset = index * 9 + bit
                lzw[offset / 8] = (lzw[offset / 8].toInt() or (1 shl (7 - offset % 8))).toByte()
            }
        }
        for ((compression, data) in
            listOf(8 to deflate, 32773 to (byteArrayOf(11) + rgb), 5 to lzw)) {
            val bitmap = decode(tiff(data, compression))
            assertEquals("compression $compression", colors, pixels(bitmap))
            bitmap.recycle()
        }
        val original =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(220, 30, 40))
            }
        val jpeg =
            ByteArrayOutputStream()
                .also { assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                .toByteArray()
        val decoded = decode(tiff(jpeg, 7, width = 16, height = 16))
        assertEquals(16, decoded.width)
        assertEquals(16, decoded.height)
        val color = decoded.getPixel(8, 8)
        assertTrue(kotlin.math.abs(Color.red(color) - 220) <= 5)
        assertTrue(kotlin.math.abs(Color.green(color) - 30) <= 5)
        original.recycle()
        decoded.recycle()
    }

    @Test
    fun boundsDecodeAndRejectsMalformedOrOversizedImages() = runBlocking {
        val wide = decode(tiff(ByteArray(4096 * 3) { -1 }, width = 4096, height = 1))
        assertEquals(2048, wide.width)
        assertEquals(1, wide.height)
        wide.recycle()
        for (data in listOf(tiff().copyOf(12), tiff(width = 100_000, height = 100_000))) {
            assertTrue(runCatching { decode(data) }.exceptionOrNull() is IllegalArgumentException)
        }
        val other = byteArrayOf(1, 2, 3)
        assertSame(other, PreviewImageDecoder.prepare(other))
    }
}
