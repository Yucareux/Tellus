#version 330

in vec4 vPos;
in vec4 vertexColor;
in vec3 vertexWorldPos;
in vec3 vBlockPos;
flat in uint vNormalIndex;
flat in uint vTextureTileId;
in vec4 gl_FragCoord;

out vec4 fragColor;

// Each tile stores the color ratio relative to the LOD's flat 
// color where 128 means using the base LOD color.
// Tiles are packed in a 2D grid, 256 tiles per row,
// see AbstractBlockTextureAtlas.TILES_PER_ROW
uniform sampler2D uBlockAtlas;

// Fade/Clip Uniforms
uniform float uClipDistance = 0.0;

// Noise Uniforms
uniform bool uNoiseEnabled;
uniform int uNoiseSteps;
uniform float uNoiseIntensity;
uniform int uNoiseDropoff;
uniform bool uDitherDhRendering;


// The random functions for diffrent dimentions
float rand(float co) { return fract(sin(co*(91.3458)) * 47453.5453); }
float rand(vec2 co) { return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453); }
float rand(vec3 co) { return rand(co.xy + rand(co.z)); }

// Puts steps in a float
// EG. setting stepSize to 4 then this would be the result of this function
// In:  0.0, 0.1, 0.2, 0.3,  0.4,  0.5, 0.6, ..., 1.1, 1.2, 1.3
// Out: 0.0, 0.0, 0.0, 0.25, 0.25, 0.5, 0.5, ..., 1.0, 1.0, 1.25
vec3 quantize(vec3 val, int stepSize)
{
    return floor(val * stepSize) / stepSize;
}

void applyNoise(inout vec4 fragColor, const in float viewDist)
{
    vec3 vertexNormal = normalize(cross(dFdy(vPos.xyz), dFdx(vPos.xyz)));
    // This bit of code is required to fix the vertex position problem cus of floats in the verted world position varuable
    vec3 fixedVPos = vPos.xyz + vertexNormal * 0.001;

    float noiseAmplification = uNoiseIntensity;
    float lum = (fragColor.r + fragColor.g + fragColor.b) / 3.0;
    noiseAmplification = (1.0 - pow(lum * 2.0 - 1.0, 2.0)) * noiseAmplification; // Lessen the effect on depending on how dark the object is, equasion for this is -(2x-1)^{2}+1
    noiseAmplification *= fragColor.a; // The effect would lessen on transparent objects

    // Random value for each position
    float randomValue = rand(quantize(fixedVPos, uNoiseSteps))
    * 2.0 * noiseAmplification - noiseAmplification;

    // Modifies the color
    // A value of 0 on the randomValue will result in the original color, while a value of 1 will result in a fully bright color
    vec3 newCol = fragColor.rgb + (1.0 - fragColor.rgb) * randomValue;
    newCol = clamp(newCol, 0.0, 1.0);

    if (uNoiseDropoff != 0) {
        float distF = min(viewDist / uNoiseDropoff, 1.0);
        newCol = mix(newCol, fragColor.rgb, distF); // The further away it gets, the less noise gets applied
    }

    fragColor.rgb = newCol;
}

/** returns a normalized value between 0.0 and 1.0 */
float bayerMatrix4x4(vec2 st)
{
    int x = int(mod(st.x, 4.0));
    int y = int(mod(st.y, 4.0));

    // Flattened 4x4 Bayer matrix
    float bayer4x4[16] = float[16](
        0.0,  8.0,  2.0, 10.0,
        12.0,  4.0, 14.0,  6.0,
        3.0, 11.0,  1.0,  9.0,
        15.0,  7.0, 13.0,  5.0
    );

    // Calculate the 1D index from the 2D coordinates
    int index = y * 4 + x;

    // Return the Bayer value normalized between 0.0 and 1.0
    return bayer4x4[index] / 16.0;
}



/**
 * Returns the texture coordinate for this fragment,
 * matching the orientation block textures are baked with.
 * The normal index is the same order as EDhDirection:
 * 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
 */
vec2 blockFaceUv()
{
    vec3 pos = fract(vBlockPos);
    switch (vNormalIndex)
    {
        case 0u: return vec2(pos.x, 1.0 - pos.z); // down
        case 1u: return vec2(pos.x, pos.z); // up
        case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y); // north
        case 3u: return vec2(pos.x, 1.0 - pos.y); // south
        case 4u: return vec2(pos.z, 1.0 - pos.y); // west
        default: return vec2(1.0 - pos.z, 1.0 - pos.y); // east
    }
}

void main()
{
    fragColor = vertexColor;
    
    if (vTextureTileId != 0u)
    {
        // tile id -> grid cell -> exact texel.
        // texture() allow us to use mipmaps
        ivec2 atlasSize = textureSize(uBlockAtlas, 0);
        vec2 tileOrigin = vec2(float(vTextureTileId % 256u), float(vTextureTileId / 256u)) * 16.0;
        vec2 uv = (tileOrigin + blockFaceUv() * 16.0) / vec2(atlasSize);
        vec4 tile = texture(uBlockAtlas, uv);
        
        // The tile's color ratio preserves the LOD's original tint and shading.
        // Tile color value of gray, 128 (half way between 0 and 255)
        // means the LOD will use it's base color.
        vec3 clampedColor = clamp(fragColor.rgb * (tile.rgb * 2.0), 0.0, 1.0);
        fragColor.rgb = mix(fragColor.rgb, clampedColor, tile.a);
    }
    
    float viewDist = length(vertexWorldPos);
    
    if (uDitherDhRendering)
    {
        // Dither out the fragment based on distance and noise.
        // Dithering is used since it works for both opaque and transparent rendering

        // noise increases as the distance increases
        // the fragCoord is used since it is stable and small so the dithering is cleaner
        float worldNoise = bayerMatrix4x4(gl_FragCoord.xy);
        // minor fudge factor to make sure all pixels fade out
        // if not included 1 in 16 pixels would never fade away
        worldNoise += 0.001;
        
        float fadeStep = smoothstep(uClipDistance, uClipDistance * 1.5, viewDist);
        if (fadeStep <= worldNoise)
        {
            discard;
        }
    }
    else
    {
        if (viewDist < uClipDistance && uClipDistance > 0.0)
        {
            discard;
        }
    }
    
    if (uNoiseEnabled
        // only apply noise to untextured blocks, 
        // textured blocks don't need the fake texturing
        && vTextureTileId == 0u)
    {
        applyNoise(fragColor, viewDist);
    }
}
