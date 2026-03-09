#version 330 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in float aLight;

out vec2 vTexCoord;
out float vLight;

uniform mat4 uProjection;

void main() {
    gl_Position = uProjection * vec4(aPosition, 1.0);
    vTexCoord   = aTexCoord;
    vLight      = aLight;
}
