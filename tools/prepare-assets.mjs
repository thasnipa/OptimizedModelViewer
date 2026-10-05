import { NodeIO, PropertyType } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { weld, simplify, flatten, join, prune, dedup } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';
import sharp from 'sharp';
import { validateBytes } from 'gltf-validator';
import fs from 'node:fs/promises';
import path from 'node:path';

const input = process.argv[2];
const output = process.argv[3];
if (!input || !output) throw new Error('Usage: node prepare-assets.mjs ORIGINAL_GLB_FOLDER OUTPUT_MODELS_FOLDER');
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
await MeshoptSimplifier.ready;
await fs.mkdir(output, { recursive: true });
const report = [];
const names = ['Bulb.glb', 'Fiagena.glb', 'Lungs.glb', 'Microscope.glb', 'solarsystem.glb'];
for (const name of names) {
    const document = await io.read(path.join(input, name));
    const root = document.getRoot();
    const markers = root.listNodes().filter(n => typeof n.getExtras().prop === 'string');
    const markerPositions = new Map(markers.map(n => [n, [...n.getWorldTranslation()]]));
    if (name === 'solarsystem.glb') {
        const wrong = markers.find(n => n.getExtras().prop === 'Bronchial tree');
        if (wrong) {
            wrong.setExtras({ ...wrong.getExtras(), prop: 'Sun' });
            wrong.setTranslation([0, 0, 0]);
            markerPositions.set(wrong, [0, 0, 0]);
        }
    }
    for (const texture of root.listTextures()) {
        const original = texture.getImage();
        if (!original) continue;
        let pipeline = sharp(original).resize({ width: 512, height: 512, fit: 'inside', withoutEnlargement: true });
        pipeline = texture.getMimeType() === 'image/jpeg'
            ? pipeline.jpeg({ quality: 85, chromaSubsampling: '4:4:4' })
            : pipeline.png({ compressionLevel: 9 });
        texture.setImage(new Uint8Array(await pipeline.toBuffer()));
    }
    if (name === 'Fiagena.glb') {
        // Simplify geometry only; preserve authored node transforms, marker extras and animations.
        await document.transform(weld(), simplify({ simplifier: MeshoptSimplifier, ratio: 0.28, error: 0.005, cleanup: false }));
    }
    if (name === 'Microscope.glb') {
        // Bake static mesh transforms and batch compatible materials. Label-only nodes stay intact.
        await document.transform(flatten({ cleanup: false }), join({ keepNamed: false, cleanup: false,
            filter: n => typeof n.getExtras().prop !== 'string' }));
    }
    // Cleanup resources only; default prune can delete label-only leaf nodes.
    await document.transform(dedup({ propertyTypes: [PropertyType.ACCESSOR, PropertyType.TEXTURE, PropertyType.MATERIAL] }),
        prune({ propertyTypes: [PropertyType.ACCESSOR, PropertyType.TEXTURE, PropertyType.MATERIAL, PropertyType.MESH, PropertyType.BUFFER], keepExtras: true }));
    for (const marker of markers) {
        if (!root.listNodes().includes(marker)) throw new Error(`${name}: label node was removed`);
        const expected = markerPositions.get(marker), actual = marker.getWorldTranslation();
        if (actual.some((v, i) => Math.abs(v - expected[i]) > 0.00001)) throw new Error(`${name}: label anchor moved`);
    }
    const bytes = await io.writeBinary(document);
    const validation = await validateBytes(bytes, { maxIssues: 200 });
    if (validation.issues.numErrors) throw new Error(`${name}: invalid output GLB: ${JSON.stringify(validation.issues)}`);
    const outputName = name === 'Bulb.glb' ? 'bulb.glb' : name;
    await fs.writeFile(path.join(output, outputName), bytes);
    const entry = { source: name, output: outputName, bytes: bytes.length,
        labels: markers.map(n => ({ text: n.getExtras().prop, world: n.getWorldTranslation() })),
        validatorErrors: validation.issues.numErrors, validatorWarnings: validation.issues.numWarnings,
        warnings: validation.issues.messages.filter(m => m.severity === 1) };
    report.push(entry);
    console.log(`${name}: ${bytes.length} bytes, ${markers.length} labels, ${validation.issues.numErrors} validator errors`);
}
await fs.writeFile(path.join(output, 'asset-validation.json'), JSON.stringify(report, null, 2));
