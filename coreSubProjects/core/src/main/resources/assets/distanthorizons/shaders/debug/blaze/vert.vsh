#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 vPosition;
layout(location = 1) in vec4 vColor;

layout (std140) uniform uniformBlock
{
    mat4 uViewProj;
};

layout(location = 0) out vec4 fColor;

void main()
{
    gl_Position = uViewProj * vec4(vPosition, 1.0);
    fColor = vColor;
}
