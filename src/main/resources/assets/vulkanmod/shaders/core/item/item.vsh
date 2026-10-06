#version 450

#include "fog.glsl"
#include "light.glsl"

// GUI item lighting, reproduced from the fixed-function GL lighting that
// RenderHelper.enableGUIStandardItemLighting() sets up: ambient 0.4 plus two
// directional lights of diffuse 0.6 each, shaded with
// min(1.0, 0.4 + 0.6 * (max(0, N.L0) + max(0, N.L1))).
//
// Both operands have to be in the same space, and that space is EYE space, not
// model space:
//
//  - The light directions come from VRenderSystem.lightDirection0/1, which the
//    GlStateManagerMixin.glLight overwrite fills by running the position through
//    the current modelview. That is what carries the
//    rotate(-30, 0,1,0) + rotate(165, 1,0,0) pair vanilla applies around the
//    glLight call, so the -30/165 GUI tilt lands in the light. Feeding the raw
//    untransformed positions here instead left both lights pointing almost
//    straight up, which pinned the top face at the min(1.0, ...) clamp and
//    squeezed the four sides into a narrow band - the flat, low-contrast
//    shading this shader previously had.
//
//  - The vertex normal therefore has to be brought into eye space too, via the
//    normal matrix (inverse transpose of the modelview's 3x3). The GUI path
//    includes a scale(1,-1,1) from setupGuiTransform, so the plain 3x3 is not
//    good enough here.
//
// Normal is constant across a face, so evaluating this per vertex is exact.
layout(binding = 0) uniform LightUBO {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
    mat4 ModelViewMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
// DefaultVertexFormats.ITEM is POSITION_3F, COLOR_4UB, TEX_2F, NORMAL_3B,
// PADDING_1B, so the normal sits at element index 3. Those last three bytes
// plus the padding byte are bound as one R8G8B8A8_SNORM slot; only xyz
// carries anything, which is why this reads as a vec3.
layout(location = 3) in vec3 Normal;


// The per-draw matrix lives in a push constant, not a UBO: it changes on
// every draw (chunk sections are drawn chunk-local), and push constants need
// no descriptor set, no UBO slice and no vkCmdBindDescriptorSets to publish.
layout(push_constant) uniform PushConstants {
    mat4 MVP;
};


layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;
layout(location = 3) out float faceShade;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexDistance = fog_distance(Position.xyz, 0);
    vertexColor = Color;

    vec3 eyeNormal = normalize(transpose(inverse(mat3(ModelViewMat))) * Normal);
    faceShade = minecraft_mix_light(Light0_Direction, Light1_Direction, eyeNormal, vec4(1.0)).r;
}
