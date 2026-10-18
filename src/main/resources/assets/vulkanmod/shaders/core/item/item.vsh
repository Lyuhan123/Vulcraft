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
    vec3 MaterialAmbient;
    vec3 MaterialDiffuse;
    float ColorMaterialMode;
    float LightModelAmbient;
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
// vec3, not a scalar: the GL material equation is
//   ambient_mat * ambient_light + diffuse_mat * diffuse_light * (N.L0 + N.L1)
// and ambient_mat / diffuse_mat are colours. It only degenerates to a scalar
// when the two are equal, which is what vanilla's own setup produces.
layout(location = 3) out vec3 faceShade;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexDistance = fog_distance(Position.xyz, 0);
    vertexColor = Color;

    vec3 eyeNormal = normalize(transpose(inverse(mat3(ModelViewMat))) * Normal);

    // GL_COLOR_MATERIAL resolution. When it is on with a mode that covers a
    // parameter, that parameter tracks the current vertex colour - which the
    // fragment stage has ALREADY multiplied into `color`. So the tracked
    // material contributes WHITE here; handing the colour over as well would
    // apply it twice. This is exactly why the previous hardcoded vec4(1.0)
    // happened to be right for vanilla, whose only lighting setup pairs
    // enableColorMaterial() with colorMaterial(GL_FRONT_AND_BACK,
    // GL_AMBIENT_AND_DIFFUSE).
    //
    // The reverse case is the point of the whole material series: a mod that
    // calls glMaterial(GL_FRONT, GL_DIFFUSE, ...) with colour material OFF now
    // gets its colour, instead of it being silently dropped.
    bool trackAmbient = mod(ColorMaterialMode, 2.0) >= 1.0;
    bool trackDiffuse = ColorMaterialMode >= 2.0;
    vec3 matAmbient = trackAmbient ? vec3(1.0) : MaterialAmbient;
    vec3 matDiffuse = trackDiffuse ? vec3(1.0) : MaterialDiffuse;

    vec2 nDotL = minecraft_compute_light(Light0_Direction, Light1_Direction, eyeNormal);
    vec2 lit = max(vec2(0.0), nDotL);
    // MINECRAFT_LIGHT_POWER (0.6) is the per-light diffuse of vanilla's two
    // setups; LightModelAmbient (0.4) is the ambient term, which used to be the
    // MINECRAFT_AMBIENT_LIGHT constant hardcoded in light.glsl.
    //
    // The min(1.0) clamp is load-bearing: with both lights fully on a face the
    // sum reaches 0.4 + 1.2 = 1.6, and the port has always clamped it to 1.0
    // (minecraft_mix_light_separate). Dropping it over-brightens every lit item,
    // so it stays - applied per channel, which is exact for the vanilla case
    // where the two material colours are equal.
    faceShade = min(vec3(1.0), matAmbient * LightModelAmbient
                  + matDiffuse * MINECRAFT_LIGHT_POWER * (lit.x + lit.y));
}
