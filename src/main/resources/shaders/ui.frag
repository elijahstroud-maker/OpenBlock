#version 330 core

in vec2  vTexCoord;
in float vLight;

out vec4 fragColor;

uniform sampler2D uTexture;

void main() {
    // vLight      = brightness (grayscale colour)
    // vTexCoord.x = per-vertex alpha (u coord repurposed)
    vec4 tex = texture(uTexture, vTexCoord);
    fragColor = vec4(tex.rgb * vLight, vTexCoord.x);
}
