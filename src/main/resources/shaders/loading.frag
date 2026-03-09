#version 330 core

in vec2  vTexCoord;
in float vLight;

out vec4 fragColor;

uniform sampler2D uTexture;

void main() {
    fragColor = texture(uTexture, vTexCoord) * vec4(vec3(vLight), 1.0);
}
