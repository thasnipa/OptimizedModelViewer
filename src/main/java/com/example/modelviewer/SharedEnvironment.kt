package com.example.modelviewer

import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

object SharedEnvironment {
    fun createReflections(engine: Engine): Texture {
        val size = 16
        val faceBytes = size * size * 3 * 4
        val pixels = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (face in 0..5) for (row in 0 until size) for (column in 0 until size) {
            val u = (2f * (column + 0.5f) / size) - 1f
            val v = (2f * (row + 0.5f) / size) - 1f
            var x: Float; var y: Float; var z: Float
            when (face) {
                0 -> { x = 1f; y = -v; z = -u }
                1 -> { x = -1f; y = -v; z = u }
                2 -> { x = u; y = 1f; z = v }
                3 -> { x = u; y = -1f; z = -v }
                4 -> { x = u; y = -v; z = 1f }
                else -> { x = -u; y = -v; z = -1f }
            }
            val inverseLength = 1f / sqrt(x * x + y * y + z * z)
            x *= inverseLength; y *= inverseLength; z *= inverseLength
            val roof = (y + 1f) * 0.5f
            val panel = if (abs(x) < 0.35f && y > 0.15f && abs(z) > 0.4f) 1.5f else 0f
            pixels.put(0.18f + roof * 0.65f + panel)
            pixels.put(0.20f + roof * 0.68f + panel)
            pixels.put(0.24f + roof * 0.72f + panel)
        }
        pixels.flip()
        val texture = Texture.Builder().width(size).height(size).levels(5)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.R11F_G11F_B10F).build(engine)
        try {
            // Once at startup, not in the frame loop; a 16px probe keeps prefilter work tiny.
            texture.generatePrefilterMipmap(engine,
                Texture.PixelBufferDescriptor(pixels, Texture.Format.RGB, Texture.Type.FLOAT),
                IntArray(6) { it * faceBytes }, Texture.PrefilterOptions().apply {
                    sampleCount = 8; mirror = false
                })
            return texture
        } catch (error: Exception) {
            engine.destroyTexture(texture)
            throw error
        }
    }
}
