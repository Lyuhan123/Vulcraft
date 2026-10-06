#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

// Fog is measured in eye space, so the vertex has to be run through the
// modelview: length(modelview * Position) is the vertex-to-camera distance.
// Measuring length(Position) directly - what this shader used to do - measures
// from the mesh's own origin, and the cloud layer is not authored in camera
// space (it carries a scale and a snapped translate), so that origin is not the
// camera. Going through the modelview is correct for every draw that shares
// this pipeline.
layout(binding = 0) uniform ModelViewUBO {
    mat4 ModelViewMat;
};

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};


layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, 0);
    vertexColor = Color;
}
