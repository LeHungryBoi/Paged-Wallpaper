#version 450

layout(push_constant) uniform BlurPushConstants {
    mat4 uMvp;
    vec4 uParams; // x = float(radius), y = sampleStep, z = direction, w = unused
} pc;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    vec4 pos = pc.uMvp * vec4(aPosition, 0.0, 1.0);
    gl_Position = vec4(pos.x, -pos.y, (pos.z + pos.w) * 0.5, pos.w);
    vTexCoord = aTexCoord;
}
