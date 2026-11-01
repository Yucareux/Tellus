#version 330 core

in vec2 texCoord;

out vec4 fragColor;


uniform sampler2D uCurrentColorSampler;
uniform sampler2D uCurrentDepthSampler;

uniform sampler2D uHistoryColorSampler;


uniform mat4 uDhProjectionInverse;
uniform mat4 uDhModelViewInverse;

uniform mat4 uDhPrevProjMvm;

uniform float uCameraOffsetX;
uniform float uCameraOffsetY;
uniform float uCameraOffsetZ;

uniform float uViewWidth;
uniform float uViewHeight;

uniform bool uDepthIsZeroToPositiveOne;




vec2 neighbourhoodOffsets[8] = vec2[8]
(
    vec2(-1.0, -1.0), vec2( 0.0, -1.0), vec2( 1.0, -1.0),
    vec2(-1.0,  0.0),                   vec2( 1.0,  0.0),
    vec2(-1.0,  1.0), vec2( 0.0,  1.0), vec2( 1.0,  1.0)
);

/** 
 * based on calcViewPosition
 * which is shared across several shaders,
 * if updated, make sure to update the other versions as well.
 */
vec4 calcNdc(float fragmentDepth)
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

    return ndc;
}

vec2 reprojectDh(float fragmentDepth)
{
    vec4 ndc = calcNdc(fragmentDepth);
    
    vec4 viewPosPrev = uDhProjectionInverse * ndc;
    viewPosPrev /= viewPosPrev.w;
    viewPosPrev = uDhModelViewInverse * viewPosPrev;

    vec4 previousPosition =
        uDhPrevProjMvm
        * (viewPosPrev + vec4(uCameraOffsetX, uCameraOffsetY, uCameraOffsetZ, 0.0));

    return (previousPosition.xy / previousPosition.w) * 0.5 + 0.5;
}

vec3 neighborClamp(vec3 currentColor, vec3 historySample, vec2 texelSize)
{
    vec3 minColor = currentColor;
    vec3 maxColor = currentColor;
    for (int i = 0; i < 8; i++)
    {
        vec2 texPos = texCoord + neighbourhoodOffsets[i] * texelSize;
        vec3 color = textureLod(uCurrentColorSampler, texPos, 0.0).rgb;
        minColor = min(minColor, color);
        maxColor = max(maxColor, color);
    }
    return clamp(historySample, minColor, maxColor);
}

/** aka "TAA" */
vec4 TemporalAntiAlias(inout vec3 color, float prevAlpha)
{
    float dhDepth = textureLod(uCurrentDepthSampler, texCoord, 0.0f).r;
    vec2 prvCoord = reprojectDh(dhDepth);

    vec3 historyColor = textureLod(uHistoryColorSampler, prvCoord, 0.0f).rgb;
    if (historyColor == vec3(0.0f))
    {
		// if we don't have any history, use the previous color
		// Note: this may break for perfectly black areas
        return vec4(color, prevAlpha);
    }

    float blendFactor = 0.0f;
    if (prvCoord.x > 0.0f && prvCoord.x < 1.0f
        && prvCoord.y > 0.0f && prvCoord.y < 1.0f)
    {
		// apply TAA if this texel is inside the area
		// tracked by the historical frame
        vec2 velocity = (texCoord - prvCoord) * vec2(uViewWidth, uViewHeight);
        blendFactor = exp(-length(velocity)) * 0.6f + 0.3f;
    }

    vec2 viewInverse = vec2(1.0f / uViewWidth, 1.0f / uViewHeight);
    vec3 neighborClampColor = neighborClamp(color, historyColor, viewInverse);

    color = mix(color, neighborClampColor, blendFactor);
    return vec4(color, prevAlpha);
}

void main()
{
    vec3 currentColor = textureLod(uCurrentColorSampler, texCoord, 0.0).rgb;
    float prevAlpha = textureLod(uCurrentColorSampler, texCoord, 0.0f).a;
    
    vec4 taaColor = TemporalAntiAlias(currentColor, prevAlpha);
    fragColor = taaColor;
}
