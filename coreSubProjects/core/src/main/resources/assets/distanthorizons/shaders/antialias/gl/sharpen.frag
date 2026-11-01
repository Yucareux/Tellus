#version 330 core

uniform sampler2D uCurrentColorSampler;

in vec2 texCoord;

out vec4 fragColor;


uniform float uViewWidth;
uniform float uViewHeight;
uniform float uCasAmount;



/** 
 * CAS - Contrast Adaptive Sharpening 
 * James doesn't know what any of this logic does, but it appears to work.
 */
vec3 SharpenFilter(vec3 color, vec2 textureCoord) 
{
    vec2 texOffset = vec2(1.0 / uViewWidth, 1.0 / uViewHeight);

    vec3 a = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(-1, -1)).rgb;
    vec3 b = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(0, -1)).rgb;
    vec3 c = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(1, -1)).rgb;
    vec3 d = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(-1, 0)).rgb;
    vec3 e = color;
    vec3 f = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(1, 0)).rgb;
    vec3 g = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(-1, 1)).rgb;
    vec3 h = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(0, 1)).rgb;
    vec3 i = texture(uCurrentColorSampler, textureCoord + texOffset * vec2(1, 1)).rgb;

    vec3 mnRGB = min(min(min(d, e), min(f, b)), h) + min(min(min(a, g), c), i);
    vec3 mxRGB = max(max(max(d, e), max(f, b)), h) + max(max(max(a, g), c), i);

    vec3 rcpMxRGB = 1.0 / mxRGB;
    vec3 ampRGB = clamp(min(mnRGB, 2.0 - mxRGB) * rcpMxRGB, 0.0, 1.0);

    ampRGB = inversesqrt(ampRGB);
    float peak = 8.0 - 3.0 * uCasAmount;
    vec3 wRGB = -1.0 / (ampRGB * peak);
    vec3 rcpWeightRGB = 1.0 / (1.0 + 4.0 * wRGB);

    return clamp(((b + d + f + h) * wRGB + e) * rcpWeightRGB, 0.0, 1.0);
}

void main()
{
    vec3 color = texture(uCurrentColorSampler, texCoord).rgb;
    vec3 sharpenedColor = SharpenFilter(color, texCoord);
    fragColor = vec4(sharpenedColor, 1.0);
}
