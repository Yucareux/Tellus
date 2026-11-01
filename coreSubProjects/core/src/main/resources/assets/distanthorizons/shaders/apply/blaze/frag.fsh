#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

layout (std140) uniform baseFragUniformBlock
{
    bool uIsReverseZDepth;
};

uniform sampler2D uSourceColorTexture;
uniform sampler2D uSourceDepthTexture;

/** 
 * LOD application shader
 *
 * This merges the rendered LODs into Minecraft's texture/FBO   
 */
void main()
{
    fragColor = vec4(0.0);
    float fragmentDepth = texture(uSourceDepthTexture, texCoord).r;
    
    bool drawnTo;
    if (uIsReverseZDepth)
    {
        drawnTo = (fragmentDepth != 0.0f);
    }
    else
    {
        // a fragment depth of "1" means the fragment wasn't drawn to
        drawnTo = (fragmentDepth != 1.0f);
    }
    
    // only update fragments that were drawn to
    if (drawnTo)
    {
        fragColor = texture(uSourceColorTexture, texCoord);
    }
    else
    {
        // use the original MC texture if no LODs were drawn to this fragment
        discard;
    }
}