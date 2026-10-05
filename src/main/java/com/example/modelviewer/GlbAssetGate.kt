package com.example.modelviewer

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GlbAssetGate {
    private const val MAX_JSON = 2 * 1024 * 1024
    private const val MAX_TEXTURE_BYTES = 16L * 1024 * 1024

    fun validate(source: ByteBuffer) {
        val bytes = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(12) in 4..MAX_JSON && bytes.getInt(16) == 0x4e4f534a) {
            "Expected a bounded GLB JSON chunk"
        }
        val jsonSize = bytes.getInt(12)
        require(20L + jsonSize <= bytes.limit()) { "Truncated JSON chunk" }
        val jsonBytes = ByteArray(jsonSize)
        bytes.position(20); bytes.get(jsonBytes)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8).trimEnd(' ', '\u0000'))
        // Reject required features unsupported by this deliberately small viewer.
        val required = json.optJSONArray("extensionsRequired")
        if (required != null) for (i in 0 until required.length()) {
            val extension = required.getString(i)
            require(extension != "KHR_texture_basisu") { "Export PNG/JPEG textures for this build" }
        }
        validateGeometry(json)
        val images = json.optJSONArray("images") ?: return
        val views = json.optJSONArray("bufferViews") ?: error("Images must be embedded in the GLB")
        val binHeader = 20L + jsonSize
        require(binHeader + 8 <= bytes.limit()) { "Missing BIN chunk" }
        val header = binHeader.toInt()
        val binLength = bytes.getInt(header)
        require(binLength >= 0 && bytes.getInt(header + 4) == 0x004e4942 &&
            binHeader + 8 + binLength <= bytes.limit()) { "Invalid BIN chunk" }
        val binStart = header + 8
        var decoded = 0L
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            require(!image.has("uri")) { "Images must use embedded bufferViews" }
            val mime = image.optString("mimeType")
            require(mime == "image/png" || mime == "image/jpeg") { "Only PNG/JPEG textures supported" }
            val view = views.getJSONObject(image.getInt("bufferView"))
            require(view.getInt("buffer") == 0) { "Only the GLB BIN buffer is supported" }
            val offset = view.optLong("byteOffset", 0)
            val length = view.getLong("byteLength")
            require(offset >= 0 && length > 0 && offset <= binLength.toLong() && length <= binLength.toLong() - offset) {
                "Invalid image buffer range"
            }
            val imageBytes = source.duplicate()
            imageBytes.position(binStart + offset.toInt())
            imageBytes.limit(binStart + (offset + length).toInt())
            options.outWidth = 0; options.outHeight = 0
            // Bounds-only decode reads the header; no full pixel bitmap is allocated.
            BitmapFactory.decodeStream(BufferStream(imageBytes), null, options)
            require(options.outWidth in 1..1024 && options.outHeight in 1..1024) {
                "Texture exceeds 1024 pixels or has invalid dimensions"
            }
            decoded += options.outWidth.toLong() * options.outHeight * 4L
            require(decoded <= MAX_TEXTURE_BYTES) { "Decoded textures exceed 16 MiB per model" }
        }
    }

    private fun validateGeometry(json: JSONObject) {
        val nodes = json.optJSONArray("nodes") ?: return
        val meshes = json.optJSONArray("meshes") ?: return
        val accessors = json.getJSONArray("accessors")
        var triangles = 0L
        var draws = 0
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (!node.has("mesh")) continue
            val primitives = meshes.getJSONObject(node.getInt("mesh")).getJSONArray("primitives")
            for (j in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(j)
                val accessorIndex = if (primitive.has("indices")) primitive.getInt("indices")
                    else primitive.getJSONObject("attributes").getInt("POSITION")
                val count = accessors.getJSONObject(accessorIndex).getLong("count")
                require(count >= 0) { "Invalid accessor count" }
                val mode = primitive.optInt("mode", 4)
                triangles += when (mode) {
                    4 -> count / 3
                    5, 6 -> (count - 2).coerceAtLeast(0)
                    else -> count // Conservative work estimate for points/lines.
                }
                draws++
                require(triangles <= 50_000 && draws <= 40) {
                    "Asset exceeds 50,000 triangles or 40 primitives; simplify it first"
                }
            }
        }
    }

    private class BufferStream(private val bytes: ByteBuffer) : InputStream() {
        override fun read(): Int = if (bytes.hasRemaining()) bytes.get().toInt() and 255 else -1
        override fun read(out: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (!bytes.hasRemaining()) return -1
            val n = minOf(count, bytes.remaining())
            bytes.get(out, offset, n)
            return n
        }
        override fun skip(count: Long): Long {
            val n = count.coerceIn(0, bytes.remaining().toLong()).toInt()
            bytes.position(bytes.position() + n)
            return n.toLong()
        }
        override fun available(): Int = bytes.remaining()
    }
}
