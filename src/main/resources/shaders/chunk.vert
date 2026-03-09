#version 330 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aLight;

out vec2 vTexCoord;
out float vLight;
out float vFogFactor;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProjection;

const float FOG_START = 160.0;
const float FOG_END   = 260.0;

void main() {
    vec4 worldPos = uModel * vec4(aPosition, 1.0);
    vec4 viewPos  = uView  * worldPos;
    gl_Position   = uProjection * viewPos;

    vTexCoord = aTexCoord;
    vLight    = aLight;

    float dist = length(viewPos.xyz);
    vFogFactor = clamp((FOG_END - dist) / (FOG_END - FOG_START), 0.0, 1.0);
}
