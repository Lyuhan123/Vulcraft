#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

// Fog is measured in eye space - see position.vsh for the full rationale.
// The sky's black horizon box and the dome-adjacent POSITION_COLOR draws are
// fogged exactly as vanilla fogs them.
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
layout(location = 1) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, 0);
    vertexColor = Color;
}
