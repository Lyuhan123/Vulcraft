#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};

layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position.xyz, 0);
    texCoord0 = UV0;
    vertexColor = Color;

    // 1.12.2 particles carry their lightmap coordinate PER-VERTEX in UV2
    // (Particle.renderParticle -> BufferBuilder.lightmap(j,k)), NOT through the
    // global LightmapCoord uniform (particles never call setLightmapTextureCoords).
    // Fold the lightmap into the vertex color here, mirroring block.vsh /
    // terrain.vsh, so each particle is shaded by its own brightness instead of
    // the stale global (which sat at the full-bright (240,240) corner).
    vec2 lmCoord = (vec2(UV2) + 8.0) / 256.0;
    vertexColor.rgb *= texture(Sampler2, lmCoord).rgb;
}