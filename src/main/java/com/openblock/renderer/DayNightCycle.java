package com.openblock.renderer;

import org.joml.Vector3f;

/**
 * Tracks time-of-day and exposes sky color, ambient light, and sun/moon direction.
 * time=0.0 = midnight, time=0.25 = sunrise, time=0.5 = noon, time=0.75 = sunset.
 */
public class DayNightCycle {
    private static final float DAY_LENGTH = 600.0f; // seconds for a full cycle

    // Key sky colors
    private static final Vector3f SKY_NIGHT   = new Vector3f(0.01f, 0.01f, 0.05f);
    private static final Vector3f SKY_SUNRISE = new Vector3f(0.95f, 0.50f, 0.20f);
    private static final Vector3f SKY_DAY     = new Vector3f(0.53f, 0.81f, 0.98f);
    private static final Vector3f SKY_SUNSET  = new Vector3f(0.90f, 0.40f, 0.10f);

    /** 0–1, starts at 0.5 (midday) */
    private float time = 0.5f;

    public void update(float delta) {
        time += delta / DAY_LENGTH;
        if (time >= 1.0f) time -= 1.0f;
    }

    /** Returns the sky/fog color for the current time. */
    public Vector3f getSkyColor() {
        // Blend through: night(0) → sunrise(0.25) → day(0.4) → sunset(0.75) → night(1.0)
        if (time < 0.20f) {
            // night → pre-sunrise (0.0 → 0.20)
            float t = time / 0.20f;
            return lerp(SKY_NIGHT, SKY_NIGHT, t);
        } else if (time < 0.28f) {
            // night → sunrise (0.20 → 0.28)
            float t = (time - 0.20f) / 0.08f;
            return lerp(SKY_NIGHT, SKY_SUNRISE, smoothstep(t));
        } else if (time < 0.35f) {
            // sunrise → day (0.28 → 0.35)
            float t = (time - 0.28f) / 0.07f;
            return lerp(SKY_SUNRISE, SKY_DAY, smoothstep(t));
        } else if (time < 0.65f) {
            // full day (0.35 → 0.65)
            return new Vector3f(SKY_DAY);
        } else if (time < 0.72f) {
            // day → sunset (0.65 → 0.72)
            float t = (time - 0.65f) / 0.07f;
            return lerp(SKY_DAY, SKY_SUNSET, smoothstep(t));
        } else if (time < 0.80f) {
            // sunset → night (0.72 → 0.80)
            float t = (time - 0.72f) / 0.08f;
            return lerp(SKY_SUNSET, SKY_NIGHT, smoothstep(t));
        } else {
            // night (0.80 → 1.0)
            return new Vector3f(SKY_NIGHT);
        }
    }

    /** Ambient multiplier: 1.0 at noon, 0.15 at midnight. */
    public float getAmbient() {
        // Day portion: 0.25 (sunrise) to 0.75 (sunset)
        if (time >= 0.28f && time <= 0.72f) {
            // fade in at dawn, full during day, fade out at dusk
            float dayFraction = (time - 0.28f) / (0.72f - 0.28f); // 0→1→0
            float bell = (float) Math.sin(dayFraction * Math.PI);   // peaks at 0.5
            return 0.15f + 0.85f * bell;
        } else {
            // Night — gradually darken/brighten at transition edges
            if (time < 0.28f) {
                float t = (time - 0.20f) / 0.08f;
                return t < 0 ? 0.15f : 0.15f + 0.05f * Math.max(0, t);
            } else {
                float t = (time - 0.72f) / 0.08f;
                return t > 1 ? 0.15f : 0.20f - 0.05f * t;
            }
        }
    }

    /**
     * Sun direction: arc in the XY-plane.
     * At time=0.25 (sunrise) the sun is on the horizon (+X), climbs to +Y at noon,
     * sets on the -X horizon at time=0.75.
     */
    public Vector3f getSunDirection() {
        // Angle: 0 at time=0.25, PI at time=0.75
        float angle = (float) ((time - 0.25f) * Math.PI * 2.0);
        return new Vector3f((float) Math.cos(angle), (float) Math.sin(angle), 0.2f).normalize();
    }

    /** Moon is opposite the sun. */
    public Vector3f getMoonDirection() {
        Vector3f sun = getSunDirection();
        return new Vector3f(-sun.x, -sun.y, -sun.z);
    }

    /** Is the sun currently above the horizon? */
    public boolean isSunUp() {
        return time >= 0.25f && time <= 0.75f;
    }

    public float getTime() { return time; }

    private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }

    private static float smoothstep(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
