#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
// DefaultVertexFormats.POSITION_TEX_NORMAL carries the per-vertex normal at
// element index 2, in MODEL space (pre-modelview). It is transformed to eye
// space below so the lighting rotates with the entity's orientation, the way
// vanilla GL_LIGHTING shades mobs - a model-space fixed shade would keep the
// same face bright no matter how the mob turns, which is what looked wrong.
layout(location = 2) in vec3 Normal;

// The per-draw matrices live in a push constant: MVP for the clip-space
// position and ModelViewMat so the vertex normal can be brought into eye space
// for orientation-dependent lighting. Push constants need no descriptor set
// and no vkCmdBindDescriptorSets, so this adds no new binding to the pipeline
// (retrofitting a UBO binding onto this shared pipeline previously broke
// entity colours, hence the push-constant route).
layout(push_constant) uniform PushConstants {
    mat4 ModelViewMat;
    mat4 MVP;
};

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out float vertexDistance;
// Eye-space normal, handed to the fragment stage where vanilla's two lights
// live. Kept unnormalised across the stage so the interpolation stays linear;
// the fragment shader normalises it.
layout(location = 3) out vec3 eyeNormal;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    texCoord0 = UV0;
    // Fog is computed from the MODEL-space vertex and the modelview's z, not
    // from gl_Position: the CPU already published FogStart/FogEnd in the same
    // units the block shader uses, so sharing fog.glsl keeps entities, terrain
    // and particles fading identically. Matching block.vsh's call site exactly
    // is the point - an entity that does not fog simply floats on top of the
    // fogged terrain it stands in.
    vertexDistance = fog_distance(Position.xyz, 0);

    // Eye-space normal for the directional shading done in the fragment stage.
    // The normal matrix (inverse transpose of the modelview's 3x3) keeps the
    // result correct under non-uniform scales. Same construction item.vsh uses.
    eyeNormal = transpose(inverse(mat3(ModelViewMat))) * Normal;
}
