#version 330 core

in vec2  vTexCoord;
in float vLight;

out vec4 fragColor;

uniform vec4 uColor;

void main() {
    // vLight lets per-vertex brightness vary (used for star twinkle variation)
    fragColor = vec4(uColor.rgb * vLight, uColor.a);
}
