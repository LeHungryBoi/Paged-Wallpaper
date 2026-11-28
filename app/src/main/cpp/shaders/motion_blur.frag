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
    int sampleCount = min(radius + 1, 16);
    float sampleStep = pc.uParams.y;
    float direction = pc.uParams.z;
    vec4 sum = vec4(0.0);

    // The sampler is clamp-to-edge and the source has a single mip level.
    // Explicit LOD avoids implicit derivative work for every blur tap.
    for (int i = 0; i < 16; i++) {
        if (i >= sampleCount) break;
        float offset = float(i) * sampleStep * direction;
        vec2 sampleUv = vTexCoord + vec2(offset, 0.0);
        sum += textureLod(uTexture, sampleUv, 0.0);
    }
    outColor = sum / float(sampleCount);
}
