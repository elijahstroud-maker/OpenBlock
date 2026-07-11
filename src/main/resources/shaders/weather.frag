#version 330 core

in vec2 vUV;
in float vDist;

uniform sampler2D uTexture;
uniform float uScroll;   // grows over time; v = phase - y*scale, so subtracting it moves streaks DOWN
uniform vec4  uColor;    // rgb = light tint, a = master opacity
uniform float uFadeDist; // curtains fade toward the grid edge

out vec4 FragColor;

void main() {
    vec4 tex = texture(uTexture, vec2(vUV.x, vUV.y - uScroll));
    float edge = clamp(1.0 - vDist / uFadeDist, 0.0, 1.0);
    float a = tex.a * uColor.a * (0.35 + 0.65 * edge);
    if (a < 0.02) discard;
    FragColor = vec4(tex.rgb * uColor.rgb, a);
}
