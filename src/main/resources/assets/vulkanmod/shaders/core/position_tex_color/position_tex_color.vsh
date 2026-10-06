#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 2) in vec4 Color;
layout(location = 1) in vec2 UV0;

// Fog is measured in eye space, so the vertex has to be run through the
// modelview: length(modelview * Position) is the vertex-to-camera distance.
// Measuring length(Position) directly - what this shader would otherwise do -
// measures from whatever origin the mesh happens to be authored around, and the
// cloud layer is NOT authored in camera space: it carries its own scale and a
// snapped translate (see the TextureMat note below), so its local origin is not
// the camera. Going through the modelview is the only basis that is correct for
// every draw that shares this pipeline.
layout(binding = 0) uniform ModelViewUBO {
    mat4 ModelViewMat;
};

// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
    mat4 TextureMat;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, 0);
    vertexColor = Color;
    // GL semantics: the active texture-unit matrix transforms the sampled
    // coordinates. Forge's CloudRenderer keeps the cloud mesh UVs STATIC and
    // instead translates the TEXTURE matrix every frame - the modelview
    // translate only steps the layer in whole-cloud increments, the texture
    // translate supplies the fractional remainder - and that is the whole
    // mechanism that makes the layer world-fixed. VulkanMod dropped that
    // matrix, so the cloud pattern stayed glued to the camera and travelled
    // with the player. The matrix is identity for every draw that does not
    // set it, so this multiply is a no-op everywhere else.
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}
