package com.example.modelviewer

import android.opengl.Matrix
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.*

class RenderTarget(
    private val runtime: RenderRuntime,
    texture: TextureView,
    val spec: ModelSpec,
    private val overlay: LabelOverlayView,
    private val status: (String?) -> Unit
) {
    private val engine = runtime.engine
    private val transforms = engine.transformManager
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera = engine.createCamera(cameraEntity)
    private val pivotEntity = EntityManager.get().create()
    private val pivot = transforms.create(pivotEntity)
    private var swapChain: SwapChain? = null
    private var disposed = false
    private var loaded = false
    var requested = false
    var asset: FilamentAsset? = null
    private var renderables = IntArray(0)
    private var anchors = emptyArray<GlbMetadataParser.Anchor>()
    private var yaw = spec.frontYaw
    private var pitch = spec.frontPitch
    private var dirty = true
    private var normalizationScale = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var centerZ = 0f
    private var radius = 1f
    private var baseDistance = 4f
    private var distance = 4f
    private var aspect = 1.0
    private var labelsEnabled = false
    // Reused scratch arrays: JNI fills existing storage rather than allocating matrices per label/frame.
    private val rotationY = FloatArray(16)
    private val rotationX = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val world = FloatArray(16)
    private val projection = DoubleArray(16)
    private val cameraView = DoubleArray(16)
    private val viewProjection = DoubleArray(16)
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    init {
        scene.skybox = runtime.skybox
        scene.indirectLight = runtime.indirect
        scene.addEntity(runtime.light)
        view.scene = scene
        view.camera = camera
        view.isPostProcessingEnabled = false
        view.antiAliasing = com.google.android.filament.View.AntiAliasing.NONE
        view.multiSampleAntiAliasingOptions = com.google.android.filament.View.MultiSampleAntiAliasingOptions().apply { enabled = false }
        camera.setExposure(16f, 1f / 125f, 100f)
        // Fixed low-resolution framebuffer. TextureView scales it with the 2D card;
        // pinch never reallocates GPU render targets on every touch sample.
        ui.setDesiredSize(384, 384)
        ui.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                if (disposed) return
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                dirty = true
            }
            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it) }; swapChain = null
                // Surface must stay alive until queued native commands no longer reference it.
                engine.flushAndWait()
            }
            override fun onResized(width: Int, height: Int) {
                if (disposed) return
                // UiHelper's initial callback can report UI dimensions even with desiredSize set.
                view.viewport = Viewport(0, 0, 384, 384)
                aspect = 1.0 // Containers and render buffers are always square.
                updateCamera()
            }
        }
        updateCamera()
        ui.attachTo(texture)
        runtime.add(this)
    }

    fun prepare(newAsset: FilamentAsset) {
        anchors = GlbMetadataParser.readLabels(newAsset, transforms)
        overlay.install(anchors)
        val box = newAsset.boundingBox
        val center = box.center
        val half = box.halfExtent
        val longest = maxOf(half[0], half[1], half[2]) * 2f
        require(longest.isFinite() && longest > 0.000001f) { "Model has invalid or empty bounds" }
        normalizationScale = 2f / longest
        centerX = center[0]; centerY = center[1]; centerZ = center[2]
        radius = sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]) * normalizationScale
        baseDistance = radius / sin(Math.toRadians(22.5)).toFloat() * 1.1f
        distance = baseDistance
        // A synthetic parent keeps ALL authored node matrices/TRS intact, including the root.
        transforms.setParent(transforms.getInstance(newAsset.root), pivot)
        updateModelTransform()
        updateCamera()
    }

    fun ready(newAsset: FilamentAsset) {
        renderables = newAsset.renderableEntities // Cache once; this getter allocates an array.
        scene.addEntities(renderables)
        loaded = true
        dirty = true
        status(null)
    }

    fun failed(error: Exception) {
        loaded = false
        scene.removeEntities(renderables)
        renderables = IntArray(0)
        anchors = emptyArray()
        overlay.install(anchors)
        status(error.message ?: "Unable to load model")
    }

    fun rotate(dx: Float, dy: Float) {
        if (!loaded || disposed) return
        yaw = (yaw + dx * 0.35f) % 360f
        pitch = (pitch + dy * 0.35f).coerceIn(-85f, 85f)
        updateModelTransform()
    }

    fun zoom(factor: Float) {
        if (!loaded || disposed || !factor.isFinite() || factor <= 0f) return
        // Dolly the camera; never overwrite model normalization or label transforms.
        distance = (distance / factor).coerceIn(radius * 1.05f, baseDistance * 4f)
        updateCamera()
    }

    fun setLabels(visible: Boolean) {
        labelsEnabled = visible
        overlay.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun updateModelTransform() {
        Matrix.setRotateM(rotationY, 0, yaw, 0f, 1f, 0f)
        Matrix.setRotateM(rotationX, 0, pitch, 1f, 0f, 0f)
        Matrix.multiplyMM(modelMatrix, 0, rotationY, 0, rotationX, 0)
        Matrix.scaleM(modelMatrix, 0, normalizationScale, normalizationScale, normalizationScale)
        Matrix.translateM(modelMatrix, 0, -centerX, -centerY, -centerZ)
        transforms.setTransform(pivot, modelMatrix)
        dirty = true
    }

    private fun updateCamera() {
        dirty = true
        camera.setProjection(45.0, aspect, 0.01, 100.0, Camera.Fov.VERTICAL)
        // +Z camera looks towards the origin with +Y up; glTF-authored front yaw is configurable.
        camera.lookAt(0.0, 0.0, distance.toDouble(), 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        camera.getProjectionMatrix(projection)
        camera.getViewMatrix(cameraView)
        for (column in 0..3) for (row in 0..3) {
            var sum = 0.0
            for (k in 0..3) sum += projection[k * 4 + row] * cameraView[column * 4 + k]
            viewProjection[column * 4 + row] = sum
        }
    }

    fun render(frameTimeNanos: Long) {
        if (disposed || !loaded || !ui.isReadyToRender) return
        if (labelsEnabled) projectLabels() // Anchors are updated each scheduled frame, even for static cards.
        if (!dirty) return // Reuse static card pixels; moving the Android container needs no GPU rerender.
        val chain = swapChain ?: return
        if (!runtime.renderer.beginFrame(chain, frameTimeNanos)) return
        try {
            runtime.renderer.render(view)
        } finally { runtime.renderer.endFrame() }
        dirty = false
    }

    private fun projectLabels() {
        for (i in anchors.indices) {
            val anchor = anchors[i]
            transforms.getWorldTransform(anchor.transformInstance, world)
            val x = world[12].toDouble(); val y = world[13].toDouble(); val z = world[14].toDouble()
            val p = viewProjection
            val w = p[3] * x + p[7] * y + p[11] * z + p[15]
            anchor.visible = false
            if (w <= 0.000001 || !w.isFinite()) continue
            val nx = (p[0] * x + p[4] * y + p[8] * z + p[12]) / w
            val ny = (p[1] * x + p[5] * y + p[9] * z + p[13]) / w
            val nz = (p[2] * x + p[6] * y + p[10] * z + p[14]) / w
            // Clip-space test hides anchors behind the eye or outside the camera frustum.
            if (!nx.isFinite() || !ny.isFinite() || !nz.isFinite() ||
                nx < -1.0 || nx > 1.0 || ny < -1.0 || ny > 1.0 || nz < -1.0 || nz > 1.0) continue
            anchor.x = ((nx * 0.5 + 0.5) * overlay.width).toFloat()
            anchor.y = ((0.5 - ny * 0.5) * overlay.height).toFloat()
            anchor.visible = true
        }
        overlay.invalidate()
    }

    fun close() {
        if (disposed) return
        disposed = true
        loaded = false
        ui.detach()
        scene.removeEntities(renderables)
        runtime.remove(this) // Cancels decoding before destroying the asset and its node transforms.
        anchors = emptyArray(); renderables = IntArray(0)
        overlay.install(anchors)
        view.scene = null; view.camera = null
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
        engine.destroyEntity(pivotEntity); EntityManager.get().destroy(pivotEntity)
    }
}
