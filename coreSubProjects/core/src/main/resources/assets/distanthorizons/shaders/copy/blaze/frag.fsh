#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

uniform sampler2D uCopyTexture;

// DH copy frag
void main()
{
    fragColor = texture(uCopyTexture, texCoord);
}