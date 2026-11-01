#version 330 core

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D uColorTexture;
uniform sampler2D uDepthTexture;

uniform bool uIsReverseZDepth;


/** 
 * Fog application shader
 *
 * This merges the rendered fog onto DH's rendered LODs
 */
void main()
{
    fragColor = vec4(0.0);

    // a fragment depth of "1" means the fragment wasn't drawn to,
    // only update fragments that were drawn to
    float fragmentDepth = textureLod(uDepthTexture, texCoord, 0).r;

    bool drawnTo;
    if (uIsReverseZDepth)
    {
        drawnTo = (fragmentDepth != 0);
    }
    else
    {
        // don't apply to the sky
        drawnTo = (fragmentDepth != 1.0);
    }

    if (drawnTo)
    {
        fragColor = texture(uColorTexture, texCoord);
    }
}
