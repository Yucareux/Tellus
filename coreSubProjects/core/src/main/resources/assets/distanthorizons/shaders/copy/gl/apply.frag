#version 330 core

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D uCopyTexture;

// DH copy frag
void main()
{
    fragColor = texture(uCopyTexture, texCoord);
}