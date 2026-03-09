#version 330 core

in vec2  vTexCoord;
in float vLight;
in float vFogFactor;

out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec3 uFogColor;
uniform float uAmbient;

void main() {
    vec4 texColor = texture(uTexture, vTexCoord);
    if (texColor.a < 0.1) discard;

    vec4 lit = vec4(texColor.rgb * vLight * uAmbient, texColor.a);
    fragColor = mix(vec4(uFogColor, 1.0), lit, vFogFactor);
}
