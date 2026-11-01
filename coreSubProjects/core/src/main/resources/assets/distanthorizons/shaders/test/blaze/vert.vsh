#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 vPosition;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 fColor;

// DH vert test
void main()
{
    gl_Position = vec4(vPosition, 0.0, 1.0);
    fColor = vColor;
}