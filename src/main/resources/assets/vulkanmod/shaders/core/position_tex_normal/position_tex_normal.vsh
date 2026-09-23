#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec3 Normal;

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0;
    // Fog is computed from the MODEL-space vertex and the modelview's z, not
    // from gl_Position: the CPU already published FogStart/FogEnd in the same
    // units the block shader uses, so sharing fog.glsl keeps entities, terrain
    // and particles fading identically. Matching block.vsh's call site exactly
    // is the point - an entity that does not fog simply floats on top of the
    // fogged terrain it stands in.
    vertexDistance = fog_distance(Position.xyz, 0);
}
