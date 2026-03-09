#version 330 core

in vec2 vTexCoord;

out vec4 fragColor;

uniform vec4 uColor;

void main() {
    fragColor = uColor;
}
