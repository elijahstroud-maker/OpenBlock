#version 330 core

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;

void main() {
    vec4 c = texture(uTexture, vTexCoord);
    if (c.a < 0.05) discard;
    fragColor = c;
}
