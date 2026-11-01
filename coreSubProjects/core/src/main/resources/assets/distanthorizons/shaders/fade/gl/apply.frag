#version 330 core

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D uFadeColorTextureUniform;



void main()
{
    fragColor = texture(uFadeColorTextureUniform, texCoord);
}
