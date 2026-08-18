#version 450
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float vertexDistance;

layout(location = 0) out vec4 fragColor;
void main() {
    // TEMP TEST: constant red to check item-icon geometry/placement
    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
