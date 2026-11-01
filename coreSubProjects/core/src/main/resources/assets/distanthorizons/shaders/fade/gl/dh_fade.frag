#version 330 core

in vec2 texCoord;

out vec4 fragColor;

// inverted model view matrix and projection matrix
uniform mat4 uDhInvMvmProj;

uniform sampler2D uDhDepthTexture;
uniform sampler2D uMcColorTexture;
uniform sampler2D uDhColorTexture;

uniform float uStartFadeBlockDistance;
uniform float uEndFadeBlockDistance;

uniform bool uDepthIsZeroToPositiveOne;



/** 
 * this method is shared across several shaders,
 * if updated, make sure to update the other versions as well.
 */
vec3 calcViewPosition(float fragmentDepth, mat4 invMvmProj)
{
    // normalized device coordinates
    vec4 ndc = vec4(texCoord.xy, fragmentDepth, 1.0);
    if (uDepthIsZeroToPositiveOne)
    {
        // Z already in [0,1], don't remap
        ndc.xy = ndc.xy * 2.0 - 1.0;
    }
    else
    {
        // UV [0,1] -> NDC [-1,+1]
        ndc.xyz = ndc.xyz * 2.0 - 1.0;
    }

    vec4 eyeCoord = invMvmProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

/**
 * Used to fade out vanilla chunks so the transition
 * between DH and vanilla is smoother.
 */
void main() 
{
    // includes both the vanilla chunks as well as DH
    vec4 combinedMcDhColor = texture(uMcColorTexture, texCoord);
    // just the DH render pass
    vec4 dhColor = texture(uDhColorTexture, texCoord);



    // ignore anything that DH hasn't drawn to
    if (dhColor.a == 0.0f)
    {
        // if not done vanilla clouds will render incorrectly at night
        dhColor = combinedMcDhColor;
    }
    
    
    float dhFragmentDepth = texture(uDhDepthTexture, texCoord).r;
    vec3 dhVertexWorldPos = calcViewPosition(dhFragmentDepth, uDhInvMvmProj);
    float dhFragmentDistance = length(dhVertexWorldPos.xzy);
    
    
    float startFade = uEndFadeBlockDistance;
    float endFade = uStartFadeBlockDistance;
    
    // Smoothly transition between combinedMcDhColor and uDhColorTexture
    // as the depth increases from the camera
    float fadeStep = smoothstep(startFade, endFade, dhFragmentDistance);
    fragColor = mix(combinedMcDhColor, dhColor, fadeStep);
    fragColor.a = 1.0;
    
}

