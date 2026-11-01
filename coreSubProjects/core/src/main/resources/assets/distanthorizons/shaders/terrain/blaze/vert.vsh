#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in uvec3 vPosition;
layout(location = 1) in uint meta; // contains light and micro-offset data
layout(location = 2) in vec4 vColor;
layout(location = 3) in uint irisMaterial;
layout(location = 4) in uint irisNormal;
layout(location = 5) in uint textureTile; // block texture tile id, 0 = flat color

// order matters, this must match the fragment shader's inputs
layout(location = 0) out vec3 vPos;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 vertexWorldPos;
// block-grid position used to generate texture UVs, fract() of this repeats per block
layout(location = 3) out vec3 vBlockPos;
layout(location = 4) flat out uint vNormalIndex;
layout(location = 5) flat out uint vTextureTileId;

layout (std140) uniform vertUniqueUniformBlock
{
    vec3 uModelOffset;
};

layout (std140) uniform vertSharedUniformBlock 
{ 
    bool uIsWhiteWorld;
    
    float uWorldYOffset;
    float uMircoOffset;
    float uEarthRadius;
    
    float uFrameMod8;
    float uViewWidth;
    float uViewHeight;

    vec3 uCameraPos;
    mat4 uCombinedMatrix;
};

uniform sampler2D uLightMap;

vec2 jitterOffsets[8] = vec2[8](
    vec2( 0.125, -0.375),
    vec2(-0.125,  0.375),
    vec2( 0.625,  0.125),
    vec2( 0.375, -0.625),
    vec2(-0.625,  0.625),
    vec2(-0.875, -0.125),
    vec2( 0.375, -0.875),
    vec2( 0.875,  0.875)
);

vec2 TAAJitter(vec2 coord, float w) 
{
    vec2 offset = jitterOffsets[int(uFrameMod8)] * (w / vec2(uViewWidth, uViewHeight));
    return coord + offset;
}

/** 
 * LOD terrain Vertex Shader
 */
void main()
{
    vPos = vPosition; // This is so it can be passed to the fragment shader
    
    vBlockPos = vec3(vPosition.xyz);
    vNormalIndex = uint(irisNormal);
    vTextureTileId = textureTile;
    
    vertexWorldPos = vPosition.xyz + (uModelOffset - uCameraPos);
    
    float vertexYPos = vPosition.y + uWorldYOffset;
    
    uint mirco = (meta & 0xFF00u) >> 8u; // mirco offset which is a xyz 2bit value
    // 0b00 = no offset
    // 0b01 = positive offset
    // 0b11 = negative offset
    // format is: 0b00zzyyxx
    float mx = (mirco & 1u)!=0u ? uMircoOffset : 0.0;
    mx = (mirco & 2u)!=0u ? -mx : mx;
    //float my = (mirco & 4u)!=0u ? uMircoOffset : 0.0;
    //my = (mirco & 8u)!=0u ? -my : my;
    float mz = (mirco & 16u)!=0u ? uMircoOffset : 0.0;
    mz = (mirco & 32u)!=0u ? -mz : mz;
    
    vertexWorldPos.x += mx;
    //vertexWorldPos.y += my;
    vertexWorldPos.z += mz;
    
    // apply the earth curvature if needed
    if (uEarthRadius < -1.0f || uEarthRadius > 1.0f)
    {
        // vertex transformation logic - stduhpf
        float localRadius = uEarthRadius + vertexYPos;
        float phi = length(vertexWorldPos.xz) / localRadius;
        vertexWorldPos.y += (cos(phi) - 1.0) * localRadius;
        vertexWorldPos.xz = vertexWorldPos.xz * sin(phi) / phi;
    }
    
    uint lights = meta & 0xFFu;
    float skyLight = (float(lights/16u)+0.5) / 16.0;
    float blockLight = (mod(float(lights), 16.0)+0.5) / 16.0;
    vertexColor = vec4(texture(uLightMap, vec2(skyLight, blockLight)).xyz, 1.0);
    
    if (!uIsWhiteWorld)
    {
        vertexColor *= vColor;
    }
    
    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);
    
    // -1 if TAA is diabled
    if (uFrameMod8 > 0)
    {
		// jittering the model around is necessary to smooth out TAA properly
        gl_Position.xy = TAAJitter(gl_Position.xy, gl_Position.w);
    }
}
