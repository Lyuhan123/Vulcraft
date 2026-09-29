
#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
// ChunkOffset is the WORLD-SPACE model translation the CPU applies for this
// draw, and it is the only thing that makes the fog distance correct in both
// terrain paths:
//   * vanilla per-section: translate(pos - viewEntity), vertices chunk-local
//   * batched:             translate(-viewEntity),      vertices baked world
// Adding it to the vertex position therefore yields (worldPos - eye) in BOTH
// paths, which is the distance fog has to measure. Measuring length(Position)
// directly measured from the world ORIGIN instead: with a 72-high eye against
// a 144 fog start almost every terrain vertex fell below the fog start and the
// distance fog silently vanished.
//
// It must be a push constant, not the binding-1 UBO the fragment shader uses:
// that UBO is created with VK_SHADER_STAGE_FRAGMENT_BIT only, so declaring it
// in the vertex stage is invalid usage (it took the device down with
// VK_ERROR_DEVICE_LOST). It also has to be per-draw, which a UBO cannot be
// here - the descriptor cache compares buffer ids, not contents.
//
// The CPU resets it to zero outside terrain, so every non-terrain draw that
// shares this shader (tile entities, block breaking, ...) keeps exactly the
// fog behaviour it had before.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
    vec4 ChunkOffset;
};

layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position.xyz + ChunkOffset.xyz, 0);
    texCoord0 = UV0;
    // 1.12.2 lightmap: UV2 is in [0, 240], lightmap is a 16x16 texture.
    // The vanilla texture matrix scales by 1/256 and translates by 8 pixels,
    // so the sample coordinate is (UV2 + 8) / 256.
    vec2 lmCoord = (vec2(UV2) + 8.0) / 256.0;
    vertexColor = Color;

    // Lightmap fetched per vertex instead of per fragment. 1.12.2 bakes
    // lighting into the vertex colour, so this is what vanilla does, and on
    // terrain the rasteriser produces roughly an order of magnitude more
    // fragments than the vertex stage consumes - moving the fetch off the
    // fragment shader removes one texture sample from every one of them.
    // Only rgb is modulated: folding the lightmap into alpha would weaken the
    // alpha test (see the note that used to live in block.fsh).
    vertexColor.rgb *= texture(Sampler2, lmCoord).rgb;
}