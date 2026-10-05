package com.example.modelviewer

data class ModelSpec(
    val title: String,
    val path: String,
    val frontYaw: Float = 0f,
    val frontPitch: Float = 0f
)

object ModelCatalog {
    val models = arrayOf(
        ModelSpec("Bulb", "models/bulb.glb"),
        ModelSpec("Fiagena", "models/Fiagena.glb"),
        ModelSpec("Lungs", "models/Lungs.glb"),
        ModelSpec("Microscope", "models/Microscope.glb"),
        // Planets occupy the X/Z plane. A tilted overview separates the planets and Saturn's ring.
        ModelSpec("Solar system", "models/solarsystem.glb", frontPitch = 55f)
    )
}
