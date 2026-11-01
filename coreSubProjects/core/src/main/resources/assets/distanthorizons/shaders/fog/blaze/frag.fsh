#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

layout (std140) uniform fragUniformBlock
{
    // fog uniforms
    vec4 uFogColor;
    float uFogScale;
    float uFogVerticalScale;
    int uFogDebugMode;
    int uFogFalloffType;
    
    // fog config
    float uFarFogStart;
    float uFarFogLength;
    float uFarFogMin;
    float uFarFogRange;
    float uFarFogDensity;
    
    // height fog config
    float uHeightFogStart;
    float uHeightFogLength;
    float uHeightFogMin;
    float uHeightFogRange;
    float uHeightFogDensity;
    
    // ???
    bool uHeightFogEnabled;
    int uHeightFogFalloffType;
    bool uHeightBasedOnCamera;
    float uHeightFogBaseHeight;
    bool uHeightFogAppliesUp;
    bool uHeightFogAppliesDown;
    bool uUseSphericalFog;
    int uHeightFogMixingMode;
    float uCameraBlockYPos;
    
    // inverted model view matrix and projection matrix
    mat4 uInvMvmProj;

    bool uIsReverseZDepth;
    bool uDepthIsZeroToPositiveOne;
};

uniform sampler2D uDhDepthTexture;



//====================//
// method definitions //
//====================//

vec3 calcViewPosition(float fragmentDepth, mat4 invMvmProj);

float getFarFogThickness(float dist);
float getHeightFogThickness(float dist);
float calculateHeightFogDepth(float worldYPos);
float mixFogThickness(float far, float height);

float linearFog(float worldDist, float fogStart, float fogLength, float fogMin, float fogRange);
float exponentialFog(float x, float fogStart, float fogLength, float fogMin, float fogRange, float fogDensity);
float exponentialSquaredFog(float x, float fogStart, float fogLength, float fogMin, float fogRange, float fogDensity);



//======//
// main //
//======//

/**
 * Fragment shader for fog.
 * This should be run last so it applies above other affects like Ambient Occlusioning
 */
void main()
{
    float fragmentDepth = texture(uDhDepthTexture, texCoord).r;
    fragColor = vec4(uFogColor.rgb, 0.0);

    bool drawnTo;
    if (uIsReverseZDepth)
    {
        drawnTo = (fragmentDepth > 0);
    }
    else
    {
        // a fragment depth of "1" means the fragment wasn't drawn to,
        // we only want to apply Fog to LODs, not to the sky outside the LODs
        drawnTo = (fragmentDepth < 1.0);
    }

    if (drawnTo)
    {
        int fogDebugMode = uFogDebugMode;
        if (fogDebugMode == 0)
        {
            // render fog based on distance from the camera
            vec3 vertexWorldPos = calcViewPosition(fragmentDepth, uInvMvmProj);

            float horizontalWorldDistance = length(vertexWorldPos.xz) * uFogScale;
            float worldDistance = length(vertexWorldPos.xyz) * uFogScale;
            float activeDistance = uUseSphericalFog ? worldDistance : horizontalWorldDistance;


            // far fog
            float farFogThickness = getFarFogThickness(activeDistance);

            // height fog
            float heightFogDepth = calculateHeightFogDepth(vertexWorldPos.y);
            float heightFogThickness = getHeightFogThickness(heightFogDepth);

            // combined fog
            float mixedFogThickness = mixFogThickness(farFogThickness, heightFogThickness);
            fragColor.a = clamp(mixedFogThickness, 0.0, 1.0);
        }
        else if (fogDebugMode == 1)
        {
            // test code
            
            // render everything with the fog color
            fragColor.a = 1.0;
        }
        else
        {
            // test code.

            // this can be fired by manually changing the fullFogMode to a (normally)
            // invalid value (like 7). 
            // By having a separate if statement defined by
            // a uniform we don't have to worry about GLSL optimizing away different
            // options when testing, causing a bunch of headaches if we just want to render the screen red.

            float depthValue = textureLod(uDhDepthTexture, texCoord, 0).r;
            fragColor.rgb = vec3(depthValue); // Convert depth value to grayscale color
            fragColor.a = 1.0;
        }
    }
}



//================//
// helper methods //
//================//

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



//=========//
// far fog //
//=========//

float getFarFogThickness(float dist)
{
    if (uFogFalloffType == 0) // LINEAR
    {
        return linearFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange);
    }
    else if (uFogFalloffType == 1) // EXPONENTIAL
    {
        return exponentialFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
    }
    else // EXPONENTIAL_SQUARED
    {
        return exponentialSquaredFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
    }
}

float getHeightFogThickness(float dist)
{
    if (!uHeightFogEnabled)
    {
        return 0.0;
    }

    if (uHeightFogFalloffType == 0) // LINEAR
    {
        return linearFog(dist, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange);
    }
    else if (uHeightFogFalloffType == 1) // EXPONENTIAL
    {
        return exponentialFog(dist, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
    }
    else // EXPONENTIAL_SQUARED
    {
        return exponentialSquaredFog(dist, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
    }
}

float linearFog(float worldDist, float fogStart, float fogLength, float fogMin, float fogRange)
{
    worldDist = (worldDist - fogStart) / fogLength;
    worldDist = clamp(worldDist, 0.0, 1.0);
    return fogMin + fogRange * worldDist;
}

float exponentialFog(
    float x, float fogStart, float fogLength,
    float fogMin, float fogRange, float fogDensity)
{
    x = max((x-fogStart)/fogLength, 0.0) * fogDensity;
    return fogMin + fogRange - fogRange/exp(x);
}

float exponentialSquaredFog(
    float x, float fogStart, float fogLength,
    float fogMin, float fogRange, float fogDensity)
{
    x = max((x-fogStart)/fogLength, 0.0) * fogDensity;
    return fogMin + fogRange - fogRange/exp(x*x);
}



//============//
// height fog //
//============//

/** 1 = full fog, 0 = no fog */
float calculateHeightFogDepth(float worldYPos)
{
    // worldYPos -65 - 384


    //worldYPos = worldYPos * -1; // negative, fog below height; positive, fog above height
    //return worldYPos * uFogVerticalScale; // "* uFogVerticalScale" is done to convert world position to a percent of the world height;

    if (!uHeightFogEnabled)
    {
        // ignore the height
        return 0.0;
    }


    if (!uHeightBasedOnCamera)
    {
        worldYPos -= (uHeightFogBaseHeight - uCameraBlockYPos);
    }


    if (uHeightFogAppliesDown && uHeightFogAppliesUp)
    {
        return abs(worldYPos) * uFogVerticalScale;
    }
    else if (uHeightFogAppliesDown)
    {
        // apploy fog below given height
        return -worldYPos * uFogVerticalScale;
    }
    else if (uHeightFogAppliesUp)
    {
        // apply fog above given height
        return worldYPos * uFogVerticalScale;
    }
    else
    {
        // shouldn't happen,
        return 0.0;
    }

}

float mixFogThickness(float far, float height)
{
    switch (uHeightFogMixingMode)
    {
        case 0: // BASIC
        case 1: // IGNORE_HEIGHT 
        return far;

        case 2: // MAX
        return max(far, height);

        case 3: // ADDITION
        return (far + height);

        case 4: // MULTIPLY
        return far * height;

        case 5: // INVERSE_MULTIPLY
        return (1.0 - (1.0-far)*(1.0-height));

        case 6: // LIMITED_ADDITION
        return (far + max(far, height));

        case 7: // MULTIPLY_ADDITION
        return (far + far*height);

        case 8: // INVERSE_MULTIPLY_ADDITION
        return (far + 1.0 - (1.0-far)*(1.0-height));

        case 9: // AVERAGE
        return (far*0.5 + height*0.5);
    }

    // shouldn't happen, but default to BASIC / IGNORE_HEIGHT
    // if an invalid option is selected
    return far;
}



