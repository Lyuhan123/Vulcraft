#version 450

#include "fog.glsl"

// Per-section offsets, indexed by gl_InstanceIndex (the draw's baseInstance).
// One ivec4 packs four sections' packed offsets (y<<16 | z<<8 | x, each & 127).
// The buffer is a shared per-frame ring (see DrawBuffers), so it holds
// GLOBAL_SECTION_SLOTS entries, not just one area's. Binding 4
// (StorageBuffers[0] in terrain.json). The fade factors that used to follow
// here were unused by the vertex stage and have been dropped.
layout(binding = 4) buffer SectionData {
    ivec4 SectionOffsets[256];
};

// Shared camera matrix (projection * camera-modelview, section translate
// already stripped). Pushed once per area, not once per section.
layout(push_constant) uniform PushConstants {
    mat4 MVPcam;
    vec3 ModelOffset;
};

// Lightmap (binding 1: Sampler2 in terrain.json). The atlas lives at binding 0
// and is sampled in the fragment stage.
layout(binding = 1) uniform sampler2D Sampler2;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;

void main() {
    // Recover this section's world-relative (render-origin) position from the
    // per-instance packed offset, then add the area origin (ModelOffset) and
    // the chunk-local vertex. Final pos = localPos + secOffset, identical to
    // what the per-section path feeds MVP (which here is MVPcam).
    const int encOffset = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    const vec3 baseOffset = bitfieldExtract(ivec3(encOffset) >> ivec3(0, 16, 8), 0, 8);

    const vec3 pos = Position + ModelOffset + baseOffset;
    gl_Position = MVPcam * vec4(pos, 1.0);

    // NOTE: this shader is NOT registered (PipelineManager never calls
    // createPipeline("terrain", ...)), so it is dead code. The live terrain
    // shader is block.vsh, which fog-measures from the CAMERA position published
    // in ChunkOffset - not from the world origin. Do not resurrect the
    // origin-based form here without fixing the same bug there.
    vertexDistance = fog_distance(Position.xyz, 0);
    texCoord0 = UV0;

    // 1.12.2 lightmap folded per-vertex (see block.vsh note).
    vec2 lmCoord = (vec2(UV2) + 8.0) / 256.0;
    vertexColor = Color;
    vertexColor.rgb *= texture(Sampler2, lmCoord).rgb;
}
