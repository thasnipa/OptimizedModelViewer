package com.example.modelviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.Utils
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RenderRuntime(context: Context) : Choreographer.FrameCallback {
    companion object {
        private const val MAX_GLB = 16 * 1024 * 1024
        private const val FRAME_NS = 33_333_333L
        init { Utils.init(); Gltfio.init() }
    }
    private val assets = context.applicationContext.assets
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val buffers = BufferPool()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val renderer = engine.createRenderer()
    private val materials = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, materials, EntityManager.get())
    private val resources = ResourceLoader(engine, true)
    private val choreographer = Choreographer.getInstance()
    // Fixed slots: no snapshot lists, iterators or queue objects in the frame loop.
    private val slots = arrayOfNulls<RenderTarget>(5)
    private var loading: Load? = null
    private var running = false
    private var closed = false
    private var lastFrame = 0L
    val skybox = Skybox.Builder().color(0.08f, 0.10f, 0.14f, 1f).build(engine)
    private val reflections = SharedEnvironment.createReflections(engine)
    val indirect = IndirectLight.Builder().reflections(reflections).irradiance(3, FloatArray(27).apply {
        this[0] = 0.8f; this[1] = 0.8f; this[2] = 0.8f
    }).intensity(20_000f).build(engine)
    val light: Int = EntityManager.get().create().also {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.97f, 0.92f).intensity(60_000f)
            .direction(-0.5f, -0.8f, -1f).castShadows(false).build(engine, it)
    }

    init {
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true; clearColor = floatArrayOf(0.08f, 0.10f, 0.14f, 1f)
        }
    }

    fun add(target: RenderTarget) {
        checkMain()
        check(!closed)
        val index = slots.indexOfFirst { it == null }
        check(index >= 0) { "Maximum 5 render targets" }
        slots[index] = target
        startNext()
    }

    fun remove(target: RenderTarget) {
        checkMain()
        for (i in slots.indices) if (slots[i] === target) slots[i] = null
        val job = loading
        if (job?.target === target) {
            job.cancelled = true
            job.future?.cancel(true)
            if (job.nativeStarted) resources.asyncCancelLoad()
            job.buffer?.let { buffers.recycle(it) }; job.buffer = null
            loading = null
        }
        target.asset?.let { loader.destroyAsset(it) }; target.asset = null
        if (!closed) startNext()
        if (slots.all { it == null }) buffers.trim()
    }

    fun resume() {
        checkMain()
        if (running || closed) return
        running = true; lastFrame = 0L
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        checkMain()
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || closed) return
        choreographer.postFrameCallback(this)
        // Tolerance avoids accidentally halving the rate on 60 Hz displays due to vsync jitter.
        if (lastFrame != 0L && frameTimeNanos - lastFrame < FRAME_NS - 1_000_000L) return
        lastFrame = frameTimeNanos
        val job = loading
        if (job != null && job.nativeStarted) {
            try {
                resources.asyncUpdateLoad()
                if (resources.asyncGetLoadProgress() >= 1f) {
                    val asset = checkNotNull(job.target.asset)
                    job.target.ready(asset)
                    resources.asyncCancelLoad() // Clear the loader's active asset before it can be closed.
                    job.nativeStarted = false
                    asset.releaseSourceData()
                    resources.evictResourceData()
                    job.buffer?.let { buffers.recycle(it) }; job.buffer = null
                    loading = null
                    startNext()
                }
            } catch (error: Exception) { fail(job, error) }
        }
        for (i in slots.indices) slots[i]?.render(frameTimeNanos)
    }

    private fun startNext() {
        if (loading != null || closed) return
        var next: RenderTarget? = null
        for (i in slots.indices) {
            val target = slots[i]
            if (target != null && !target.requested) { next = target; break }
        }
        val target = next ?: return
        target.requested = true
        val job = Load(target)
        loading = job
        job.future = io.submit {
            var buffer: ByteBuffer? = null
            try {
                buffer = readGlb(target.spec.path, job)
                val loaded = buffer
                handler.post {
                    if (closed || job.cancelled || loading !== job) {
                        buffers.recycle(loaded)
                        if (slots.all { it == null }) buffers.trim()
                    }
                    else beginNative(job, loaded)
                }
            } catch (error: Exception) {
                buffer?.let { buffers.recycle(it) }
                handler.post { if (!closed && !job.cancelled && loading === job) fail(job, error) }
            }
        }
    }

    private fun beginNative(job: Load, buffer: ByteBuffer) {
        job.buffer = buffer
        try {
            val asset = loader.createAsset(buffer) ?: error("Invalid or unsupported GLB")
            job.target.asset = asset
            // This viewer intentionally requires monolithic, bundled GLBs.
            require(asset.resourceUris.isEmpty()) { "GLB contains external resources; embed them first" }
            job.target.prepare(asset)
            job.nativeStarted = true
            check(resources.asyncBeginLoad(asset)) { "Resource loading could not start" }
        } catch (error: Exception) { fail(job, error) }
    }

    private fun fail(job: Load, error: Exception) {
        if (loading !== job) return
        if (job.nativeStarted) resources.asyncCancelLoad()
        job.target.asset?.let { loader.destroyAsset(it) }; job.target.asset = null
        job.target.failed(error)
        job.buffer?.let { buffers.recycle(it) }; job.buffer = null
        resources.evictResourceData()
        loading = null
        startNext()
    }

    private fun readGlb(path: String, job: Load): ByteBuffer {
        assets.open(path).use { input ->
            val header = ByteArray(12)
            var have = 0
            while (have < header.size) {
                val count = input.read(header, have, header.size - have)
                require(count > 0) { "Truncated GLB header" }; have += count
            }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(h.int == 0x46546c67 && h.int == 2) { "Expected GLB version 2" }
            val length = h.int
            require(length in 20..MAX_GLB) { "GLB must be <= 16 MiB; optimize this asset" }
            val buffer = buffers.obtain(length)
            try {
                buffer.put(header)
                val scratch = ByteArray(64 * 1024) // One IO scratch block, never a full heap GLB copy.
                while (buffer.hasRemaining()) {
                    if (job.cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    val count = input.read(scratch, 0, minOf(scratch.size, buffer.remaining()))
                    require(count > 0) { "Truncated GLB payload" }
                    buffer.put(scratch, 0, count)
                }
                require(input.read() == -1) { "GLB length does not match header" }
                buffer.flip()
                GlbAssetGate.validate(buffer)
                return buffer
            } catch (error: Exception) { buffers.recycle(buffer); throw error }
        }
    }

    fun close() {
        checkMain()
        if (closed) return
        closed = true
        pause()
        // Caller normally closes cards first. This fallback still releases every registered target.
        for (i in slots.indices) slots[i]?.close()
        io.shutdownNow()
        buffers.close()
        resources.asyncCancelLoad()
        resources.evictResourceData()
        resources.destroy()
        loader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyEntity(light); EntityManager.get().destroy(light)
        engine.destroyIndirectLight(indirect); engine.destroyTexture(reflections); engine.destroySkybox(skybox)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper())
    private class Load(val target: RenderTarget) {
        @Volatile var cancelled = false
        var future: Future<*>? = null
        var buffer: ByteBuffer? = null
        var nativeStarted = false
    }

    private class BufferPool {
        private var cached: ByteBuffer? = null
        private var closed = false
        @Synchronized fun obtain(size: Int): ByteBuffer {
            val old = cached; cached = null
            return (if (old != null && old.capacity() >= size) old else ByteBuffer.allocateDirect(size))
                .apply { clear(); limit(size); order(ByteOrder.LITTLE_ENDIAN) }
        }
        @Synchronized fun recycle(buffer: ByteBuffer) {
            if (!closed && (cached == null || cached!!.capacity() < buffer.capacity())) cached = buffer
        }
        @Synchronized fun trim() { cached = null }
        @Synchronized fun close() { closed = true; cached = null }
    }
}
