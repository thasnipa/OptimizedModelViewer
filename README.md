# 1. Audit Report (Actual assets)

The supplied GLBs confirm four incompatibilities with the previous generic admission limits: Bulb's images require 20 MiB of RGBA pixels, Fiagena has 148,250 triangles, Microscope has 123 draw primitives, and solarsystem contains a 4096x2048 image and 66.625 MiB of RGBA pixels. Simply increasing the limits would increase memory and rendering cost. This project instead bundles optimized copies of all five assets.

| Asset | Triangles original → bundled | Draw primitives original → bundled | Image pixels as RGBA MiB original → bundled | Labels |
|---|---:|---:|---:|---:|
| Bulb | 4,372 → 4,372 | 2 → 2 | 20 → 5 | 6 |
| Fiagena | 148,250 → 43,424 | 9 → 9 | 16 → 4 | 7 |
| Lungs | 27,578 → 27,578 | 3 → 3 | 12 → 3 | 5 |
| Microscope | 43,863 → 43,863 | 123 → 21 | 0 → 0 | 12 |
| Solar system | 19,800 → 19,800 | 26 → 26 | 66.625 → 3.625 | 9 |
| Total | 243,863 → 139,037 | 163 → 61 | 114.625 → 15.625 | 39 |

These image-memory estimates count RGBA pixels before mipmaps and driver overhead, not measured GPU/PSS residency. All 39 label nodes are retained. The solar-system marker accidentally named `Bronchial tree` is corrected to `Sun` and anchored at the sun's origin in the bundled copy. Uploaded originals are unchanged.

The assets already contain important root rotations/scales. Applying a generic extra 90-degree correction would break their authored orientation and labels. Desktop reference renders confirmed yaw 0 for the four educational diagrams and a 55-degree pitch for the solar-system overview.

Bulb uses transparent and metallic materials. The earlier diffuse-only SH environment lacked specular reflections, so metallic surfaces could appear too dark. A tiny shared studio reflection probe is now included.

# 2. Architecture & Optimization Strategy

- One Activity-scoped Filament Engine, Renderer, material provider, ResourceLoader, light and environment; each card owns its scene, view, camera, swap chain, asset and normalization pivot.
- One Choreographer scheduler targets approximately 30 FPS. Each card renders at 384x384 pixels. Static cards reuse their rendered pixels, so normal dragging/resizing does not require rerendering geometry. Rotation, camera zoom and surface changes mark a card dirty. Label anchors are still projected every scheduled frame while visible.
- TextureViews compose correctly with Android controls. During normal pinch the entire container is scaled with a composition transform; layout is committed once at gesture end. Drag updates translation without repeated layout traversal.
- One gesture layer routes by mode and pointer count. Interaction drag rotates the normalized model; pinch moves the camera. The container is locked in interaction mode. Pointer handoff rebases drag; mode changes invalidate the old gesture stream.
- gltfio's per-node extras JSON supplies label text and actual TransformManager instances. World transforms retain the authored hierarchy and common normalization pivot. Cached camera matrices and scratch arrays avoid application-created matrix/vector allocations per label/frame.
- A single Canvas overlay draws all labels, anchor dots and connector lines. Visible labels are balanced between left/right edge columns and ordered vertically. Cached arrays support allocation-free sorting/placement. Complete label strings fit their boxes; text becomes smaller on small containers.
- IO runs on one worker with bounded direct-buffer pooling. Loads are serialized and textures load asynchronously. Native asset creation remains on the Engine owner thread and can still cause a transient spawn hitch.
- Source data is released after loading. Close cancels active reads/decoding, removes renderables, destroys the asset and card-owned objects, detaches the surface and clears references. Shared GPU resources are destroyed at Activity shutdown. Native/GPU destruction follows Filament/driver processing; Java/direct memory is runtime-managed. No forced garbage collection.

Offline asset preparation uses glTF Transform and Meshoptimizer. Textures are resized to at most 512 pixels per dimension, preserving aspect ratios. Fiagena is simplified with a bounded error tolerance; Microscope's static meshes are batched by compatible material. Empty label nodes and their world positions are explicitly retained and checked. The pipeline and pinned package lock are included in `tools/`.

# 3. Corrected Code (Complete project)

All five optimized GLBs are already bundled at:

```
src/main/assets/models/bulb.glb
src/main/assets/models/Fiagena.glb
src/main/assets/models/Lungs.glb
src/main/assets/models/Microscope.glb
src/main/assets/models/solarsystem.glb
```

The app is a standalone single-module Android project, using Kotlin/XML-free programmatic Android Views. Open the project root in Android Studio with JDK 17, install Android SDK 35, and build with `./gradlew assembleDebug`. Android does not need npm or Python to run or build these bundled assets.

The project pins AGP 8.7.3, Gradle 8.9, Kotlin 1.9.24 and all Filament artifacts to 1.56.0. Minimum Android API is 26 and GLES 3.0 is required. All ten Kotlin classes belong together. For integration into an existing app module, put the classes in its source directory, copy assets into `app/src/main/assets/models/`, and use the included dependency/manifest settings. SceneView/Sceneform are replaced by direct Filament ownership.

| Kotlin file | Responsibility |
|---|---|
| MainActivity.kt | Five-model catalog UI, card limit, Activity lifecycle and bar insets |
| ModelCatalog.kt | Exact bundled paths and selected initial yaw/pitch |
| ModelCardView.kt | Three controls and card ownership |
| CardGestureView.kt | Exclusive normal/interaction gesture routing |
| RenderRuntime.kt | Shared engine, serialized loading, buffers and frame scheduler |
| RenderTarget.kt | Camera, normalization/rotation, dirty rendering, projection and disposal |
| LabelOverlayView.kt | Balanced labels, connector lines and cached layout storage |
| GlbMetadataParser.kt | Extract extras.prop from actual node entities |
| GlbAssetGate.kt | Bound file, JSON, texture and geometry admission |
| SharedEnvironment.kt | Tiny shared prefiltered reflection probe for metallic materials |

## Verification

- All five bundled files pass Khronos glTF Validator with **zero errors**. Bulb retains two warnings about absent tangents, which the loader can generate; the other four have zero warnings.
- All five pass the implemented geometry/file/image-budget envelope, checked independently from their actual binary payloads: <=50,000 triangles, <=40 draw primitives, <=16 MiB file size, <=16 MiB estimated decoded image pixels. The admission gate accepts textures up to 1024px; all bundled textures are <=512px.
- All 39 label strings are retained, including the corrected Sun label. Every marker's world position is checked against its original position, except the deliberate Sun correction.
- All ten Kotlin sources compile without diagnostics against Kotlin 1.9.24, Android API class signatures and the official Filament 1.56.0 AAR APIs.
- Eight executable admission tests pass: ordinary geometry, exact triangle boundary, triangle overflow, draw-count overflow, repeated-node geometry accounting, negative counts, unsupported required KTX texture format and truncated JSON.
- Original-versus-optimized offscreen desktop renders were inspected. Microscope renders identically in that comparison; other changes reflect image resizing and Fiagena simplification. Fiagena's geometry bounds changed by less than 0.001 model units. The reference renderer is not Filament and does not certify Android lighting/transparency.

An Android APK build, native rendering/gestures on Android, runtime bitmap-header decoding and five-model FPS/PSS measurements were not run: this environment has no Android SDK/device. The implementation and bundled assets are complete, but a 30 FPS claim still requires a release-build test on the actual 2–3 GB target. Test all five models, labels enabled, rotation/zoom, close during load, repeated add/remove, background/foreground, and thermal stability.

## Intentional trade-offs

- 512px textures, bounded simplification and 384px render buffers trade detail for lower cost. Use the supplied originals as input if preparing a higher-detail profile; raising limits alone does not optimize them.
- Fiagena's 10 authored animations remain in the GLB but are not autoplayed. Label markers are authored static empties and do not track animated/deforming surfaces.
- Labels use authored node origins and frustum clipping; there is no mesh depth-occlusion test. Connector lines can cross, and text gets small when 12 labels are displayed on a small card.
- Cards are square and can overlap. Touch brings a card to the front. Activity recreation restores model selections, not positions, rotations, zoom or toggle states.

## Reproduce optimized GLBs

From `tools/`, run `npm ci`, then:

```
node prepare-assets.mjs /absolute/path/to/original-five-glbs ../src/main/assets/models
```

The source folder must contain `Bulb.glb`, `Fiagena.glb`, `Lungs.glb`, `Microscope.glb` and `solarsystem.glb`. Original asset bytes are not duplicated in the app. Statistics, validator results, marker positions and desktop comparisons are included under `docs/` and `assets/models/asset-validation.json`.

References: [glTF Transform](https://gltf-transform.dev/), [Filament](https://github.com/google/filament), and [glTF Validator](https://github.com/KhronosGroup/glTF-Validator).
#   O p t i m i z e d M o d e l V i e w e r  
 #   O p t i m i z e d M o d e l V i e w e r  
 