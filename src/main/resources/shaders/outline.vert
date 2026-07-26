#version 330 core

layout(location = 0) in vec3 aPosition;

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;

void main() {
    vec4 viewPos = uView * uModel * vec4(aPosition, 1.0);
    // Pull the outline ~4cm toward the camera: the edge quads extend up to
    // ~2cm into a neighboring block, and without this bias that neighbor's
    // face depth-clips the shared edges (outline looked thin or cut off).
    float len = length(viewPos.xyz);
    if (len > 0.05) viewPos.xyz *= (len - 0.04) / len;
    gl_Position = uProjection * viewPos;
}
