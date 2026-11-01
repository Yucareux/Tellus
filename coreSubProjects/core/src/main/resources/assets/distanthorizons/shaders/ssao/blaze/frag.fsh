#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

#define SAMPLE_MAX 64

#define saturate(x) (clamp((x), 0.0, 1.0))

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;


layout (std140) uniform fragUniformBlock
{
    int uSampleCount;

    float uRadius;
    float uStrength;
    float uMinLight;
    float uBias;
    float uFadeDistanceInBlocks;

    mat4 uInvProj;
    mat4 uProj;

    bool uIsReverseZDepth;
    bool uDepthIsZeroToPositiveOne;
};

uniform sampler2D uDhDepthTexture;

const float EPSILON = 1.e-6;
const float GOLDEN_ANGLE = 2.39996323;
const vec3 MAGIC = vec3(0.06711056, 0.00583715, 52.9829189);
const float PI = 3.1415926538;
const float TAU = PI * 2.0;


vec3 unproject(vec4 pos) { return pos.xyz / pos.w; }

float InterleavedGradientNoise(const in vec2 pixel) 
{
    float x = dot(pixel, MAGIC.xy);
    return fract(MAGIC.z * fract(x));
}

/** 
 * this method is shared across several shaders,
 * if updated, make sure to update the other versions as well.
 */
vec3 calcViewPosition(float fragmentDepth, mat4 invProj)
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

    vec4 eyeCoord = invProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}


float GetSpiralOcclusion(const in vec2 uv, const in vec3 viewPos, const in vec3 viewNormal) 
{
    float dither = InterleavedGradientNoise(gl_FragCoord.xy);
    float rotatePhase = dither * TAU;
    float rStep = uRadius / uSampleCount;

    vec2 offset;

    float ao = 0.0;
    int sampleCount = 0;
    float radius = rStep;
    for (int i = 0; i < clamp(uSampleCount, 1, SAMPLE_MAX); i++) 
    {
        vec2 offset = vec2(
            sin(rotatePhase),
            cos(rotatePhase)
        ) * radius;
        
        radius += rStep;
        rotatePhase += GOLDEN_ANGLE;

        vec3 sampleViewPos = viewPos + vec3(offset, -0.1);
        vec3 sampleClipPos = unproject(uProj * vec4(sampleViewPos, 1.0)) * 0.5 + 0.5;
        sampleClipPos = saturate(sampleClipPos);

        float sampleClipDepth = textureLod(uDhDepthTexture, sampleClipPos.xy, 0.0).r;
        if (sampleClipDepth >= 1.0 - EPSILON)
        {
            continue;   
        }

        if (uDepthIsZeroToPositiveOne)
        {
            vec4 ndc = vec4(
                sampleClipPos.x * 2.0 - 1.0, // UV [0,1] -> NDC [-1,+1]
                sampleClipPos.y * 2.0 - 1.0,
                sampleClipDepth,
                1.0 // w=1 placeholder for matrix multiplication
            );
            sampleViewPos = unproject(uInvProj * ndc);
        }
        else
        {
            sampleClipPos.z = sampleClipDepth;
            sampleViewPos = unproject(uInvProj * vec4(sampleClipPos * 2.0 - 1.0, 1.0));
        }
        
        vec3 diff = sampleViewPos - viewPos;
        float sampleDist = length(diff);
        vec3 sampleNormal = diff / sampleDist;

        float sampleNoLm = max(dot(viewNormal, sampleNormal) - uBias, 0.0);
        float aoF = 1.0 - saturate(sampleDist / uRadius);
        ao += sampleNoLm * aoF;
        sampleCount++;
    }

    ao /= max(sampleCount, 1);
    ao = smoothstep(0.0, uStrength, ao);

    return ao * (1.0 - uMinLight);
}


void main() 
{
    float fragmentDepth = textureLod(uDhDepthTexture, texCoord, 0).r;
    float occlusion = 0.0;
    
    bool isGround;
    if (uIsReverseZDepth)
    {
        isGround = (fragmentDepth > 0.0f);
    }
    else
    {
        isGround = (fragmentDepth < 1.0f);
    }
    
    // Do not apply to sky
    if (isGround)
    {
        vec3 viewPos = calcViewPosition(fragmentDepth, uInvProj);
        
        // fading is done to prevent banding/noise
        // at super far distance
        float distanceFromCamera = length(viewPos);
        float fadeDistance = uFadeDistanceInBlocks;
        if (distanceFromCamera < fadeDistance)
        {
            vec3 viewNormal = cross(dFdx(viewPos.xyz), dFdy(viewPos.xyz));
            viewNormal = normalize(viewNormal);
            occlusion = GetSpiralOcclusion(texCoord, viewPos, viewNormal);
            
            // linearly fade with distance
            occlusion *= (fadeDistance - distanceFromCamera) / fadeDistance;
        }
        else
        {
            // we're out of range, no need to do any SSAO calculations
            occlusion = 0.0;
        }
    }
    
    fragColor = vec4(vec3(1.0 - occlusion), 1.0);
}
