#version 330 core

// Solid-colour UI overlay (damage flash, chat background). Pairs with ui.vert.
out vec4 fragColor;

uniform vec4 uColor;

void main() {
    fragColor = uColor;
}
