#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUV;
layout (location = 2) in float aLight;

uniform mat4 uProjection;
uniform mat4 uView;

out vec2 vUV;
out float vDist;

void main() {
    vec4 viewPos = uView * vec4(aPos, 1.0);
    vDist = length(viewPos.xyz);
    vUV = aUV;
    gl_Position = uProjection * viewPos;
}
