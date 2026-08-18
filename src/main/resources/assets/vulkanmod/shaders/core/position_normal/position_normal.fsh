#version 450

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
};

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = ColorModulator;
}
