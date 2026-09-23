#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 normal;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position.xyz, 0);
    normal = MVP * vec4(Normal, 0.0);
}
