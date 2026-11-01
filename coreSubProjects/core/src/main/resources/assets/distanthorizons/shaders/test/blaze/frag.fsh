#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec4 fColor;

layout(location = 0) out vec4 fragColor;

// DH frag test
void main()
{
    fragColor = fColor;
}