#version 330 core

in vec2 vUV;
in float vAlpha;

uniform sampler2D uTexture;
uniform float uBrightness; // day/night light level

out vec4 FragColor;

void main() {
    vec4 c = texture(uTexture, vUV);
    float a = c.a * vAlpha;
    if (a < 0.03) discard;
    FragColor = vec4(c.rgb * uBrightness, a);
}
