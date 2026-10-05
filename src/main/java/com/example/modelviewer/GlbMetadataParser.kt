package com.example.modelviewer

import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.FilamentAsset
import org.json.JSONObject


object GlbMetadataParser {
    class Anchor(val text: String, val transformInstance: Int) {
        var x = 0f
        var y = 0f
        var visible = false
        var textWidth = 0f
    }

    fun readLabels(asset: FilamentAsset, transforms: TransformManager): Array<Anchor> {
        val result = ArrayList<Anchor>()
        for (entity in asset.entities) {
            val extras = asset.getExtras(entity) ?: continue
            val prop = JSONObject(extras).opt("prop")
            // Accept only actual JSON strings; do not display arbitrary objects as labels.
            if (prop !is String || prop.isBlank()) continue
            val instance = transforms.getInstance(entity)
            require(prop.length <= 160) { "Part label exceeds 160 characters" }
            if (instance != 0) result.add(Anchor(prop.trim(), instance))
        }
        require(result.size <= 64) { "More than 64 part labels; optimize the asset first" }
        return result.toTypedArray()
    }
}
