#version 450

layout(push_constant) uniform PushConstants {
    mat4 uMvp;
    vec4 uColor;
    vec2 uUvOffset;
    vec2 uUvScale;
    float uDarkMix;
} pc;

layout(set = 0, binding = 0) uniform sampler2D uTexture;
layout(set = 0, binding = 1) uniform sampler2D uDarkTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = vTexCoord * pc.uUvScale + pc.uUvOffset;
    vec4 base = texture(uTexture, uv);
    if (pc.uDarkMix > 0.001) {
        vec4 darkSample = texture(uDarkTexture, uv);
        base = mix(base, darkSample, pc.uDarkMix);
    }
    outColor = base * pc.uColor;
}
