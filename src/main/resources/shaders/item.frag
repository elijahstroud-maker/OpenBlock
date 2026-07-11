#version 330 core

in vec2 vUV;
in float vLight;

uniform sampler2D uTexture;
uniform float uAmbient; // day/night light level

out vec4 FragColor;

void main() {
    vec4 c = texture(uTexture, vUV);
    if (c.a < 0.1) discard;
    FragColor = vec4(c.rgb * vLight * uAmbient, 1.0);
}
