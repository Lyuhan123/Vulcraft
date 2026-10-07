#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;

// Fog is measured in eye space, so the vertex has to be run through the
// modelview: length(modelview * Position) is the vertex-to-camera distance.
// The sky dome is a POSITION-only mesh (no per-vertex colour - vanilla draws
// it flat in the sky colour and gets the horizon gradient purely from fog),
// so the only source of the gradient is this fog blend toward FogColor.
layout(binding = 0) uniform ModelViewUBO {
    mat4 ModelViewMat;
};

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};

layout(location = 0) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, 0);
}
