#version 330 core

in vec2  vTexCoord;
in float vLight;
in float vFogFactor;
in vec3  vWorldPos;

out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec3 uFogColor;
uniform float uAmbient;

// Volumetric height fog: a ground-hugging haze layer with full density at/below
// uHeightFogBase and exponential falloff above it. The fog amount is the analytic
// integral of that density along the camera->fragment ray, so haze pools in
// valleys and over water while peaks and high vantage points stay clear.
uniform vec3  uCameraPos;
uniform float uHeightFogDensity;  // 0 disables (underwater, cloud pass)
uniform float uHeightFogFalloff;
uniform float uHeightFogBase;

void main() {
    vec4 texColor = texture(uTexture, vTexCoord);
    if (texColor.a < 0.1) discard;

    vec4 lit = vec4(texColor.rgb * vLight * uAmbient, texColor.a);

    float heightFog = 0.0;
    if (uHeightFogDensity > 0.0) {
        float dist = length(vWorldPos - uCameraPos);
        float f  = uHeightFogFalloff;
        float y0 = min(uCameraPos.y, vWorldPos.y);
        float y1 = max(uCameraPos.y, vWorldPos.y);

        // Average density along the ray, exact for the piecewise medium:
        // density = 1 below base, exp(-f*(y-base)) above it.
        float avg;
        if (y1 - y0 < 0.01) {
            avg = exp(-f * max(y0 - uHeightFogBase, 0.0));
        } else {
            float below = clamp(uHeightFogBase - y0, 0.0, y1 - y0);
            float h0 = max(y0 - uHeightFogBase, 0.0);
            float h1 = max(y1 - uHeightFogBase, 0.0);
            float above = (h1 > h0) ? (exp(-f * h0) - exp(-f * h1)) / f : 0.0;
            avg = (below + above) / (y1 - y0);
        }
        heightFog = clamp(uHeightFogDensity * dist * avg, 0.0, 1.0);
    }

    // Combine with linear distance fog (vFogFactor: 1 = clear, 0 = full fog)
    float fog = 1.0 - vFogFactor * (1.0 - heightFog);
    fragColor = mix(lit, vec4(uFogColor, 1.0), fog);
}
