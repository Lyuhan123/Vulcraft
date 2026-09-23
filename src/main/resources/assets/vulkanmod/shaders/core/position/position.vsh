#version 450

layout(location = 0) in vec3 Position;

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
}
