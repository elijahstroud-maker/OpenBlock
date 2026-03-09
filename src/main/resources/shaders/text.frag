#version 330 core

in vec2  vTexCoord;
in float vLight;

out vec4 fragColor;

uniform sampler2D uTexture;

void main() {
    vec4 c = texture(uTexture, vTexCoord);
    // Preserve texture alpha (for transparent text background); tint RGB by vLight
    fragColor = vec4(c.rgb * vLight, c.a);
}
