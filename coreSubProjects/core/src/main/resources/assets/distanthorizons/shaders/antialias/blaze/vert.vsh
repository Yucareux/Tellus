#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 vPosition;

layout(location = 0) out vec2 texCoord;

// DH anti-alias
void main()
{
    gl_Position = vec4(vPosition, 1.0, 1.0);
    texCoord = vPosition.xy * 0.5 + 0.5;
}