#version 450

layout(push_constant) uniform BlurPushConstants {
    mat4 uMvp;
    vec4 uParams; // x = float(radius), y = sampleStep, z = direction, w = unused
} pc;

layout(set = 0, binding = 0) uniform sampler2D uTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    int radius = int(pc.uParams.x);
    float sampleStep = pc.uParams.y;
    float direction = pc.uParams.z;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 32; i++) {
        if (i > radius) break;
        float offset = float(i) * sampleStep * direction;
        vec2 sampleUv = clamp(vTexCoord + vec2(offset, 0.0), vec2(0.0), vec2(1.0));
        sum += texture(uTexture, sampleUv);
    }
    outColor = sum / min(float(radius) + 1.0, 32.0);
}
