package com.openblock.weather;

import org.joml.Vector3f;

import java.util.Random;

/**
 * World weather state: clear or raining, with natural Minecraft-style cycles
 * (long clear stretches, shorter storms) and a smooth intensity fade so rain
 * rolls in and out instead of snapping. Above {@link #SNOW_LINE} the same
 * weather falls as snow instead of rain.
 *
 * /weather clear|rain overrides the natural cycle and restarts its timer.
 */
public class Weather {
    /** Altitude where rain turns to snow — matches the terrain snow-grass line. */
    public static final float SNOW_LINE = 95f;

    /** Seconds for rain to fully fade in/out. */
    private static final float FADE_SECONDS = 8f;

    private final Random rng = new Random();
    private boolean raining = false;
    private float intensity = 0f;   // 0 = clear skies, 1 = full downpour
    private float untilChange;      // countdown to the next natural flip

    public Weather() {
        untilChange = clearDuration();
    }

    public void update(float delta) {
        untilChange -= delta;
        if (untilChange <= 0f) {
            raining = !raining;
            untilChange = raining ? rainDuration() : clearDuration();
        }
        float target = raining ? 1f : 0f;
        if (intensity < target) intensity = Math.min(target, intensity + delta / FADE_SECONDS);
        else if (intensity > target) intensity = Math.max(target, intensity - delta / FADE_SECONDS);
    }

    /** /weather command: force a state and restart the natural cycle timer. */
    public void setRaining(boolean on) {
        raining = on;
        untilChange = on ? rainDuration() : clearDuration();
    }

    public boolean isRaining()    { return raining; }
    public float   getIntensity() { return intensity; }

    // Natural cycle (also applied when /weather forces a state, so commanded
    // weather never lasts forever): storms are uncommon — clear spells run
    // 6-18 minutes (day cycle is 10) and storms 1.5-5, both freshly random
    // every time, any hour of the day or night.
    private float clearDuration() { return 360f + rng.nextFloat() * 720f; }
    private float rainDuration()  { return 80f + rng.nextFloat() * 220f; }

    /**
     * Pulls the sky/fog colour toward storm grey by the current intensity.
     * The grey target scales with ambient light so night storms stay dark.
     * Mutates and returns the given colour (callers pass a fresh vector).
     */
    public Vector3f skyWithRain(Vector3f sky, float ambient) {
        if (intensity <= 0f) return sky;
        float l = Math.min(1f, ambient * 1.05f);
        return sky.lerp(new Vector3f(0.42f * l, 0.44f * l, 0.47f * l), 0.75f * intensity);
    }

    /** Storms dim world lighting, like Minecraft's rain darkening. */
    public float ambientWithRain(float ambient) {
        return ambient * (1f - 0.28f * intensity);
    }
}
