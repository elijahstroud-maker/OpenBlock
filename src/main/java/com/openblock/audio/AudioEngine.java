package com.openblock.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;

import java.nio.IntBuffer;

import static org.lwjgl.openal.ALC10.*;

/** Owns the OpenAL device and context. Initialise once; share across MusicPlayer and SoundManager. */
public class AudioEngine {

    private long device;
    private long context;

    public void init() {
        device = alcOpenDevice((CharSequence) null);
        if (device == 0) return;
        context = alcCreateContext(device, (IntBuffer) null);
        alcMakeContextCurrent(context);
        AL.createCapabilities(ALC.createCapabilities(device));
    }

    /**
     * The open device handle. LWJGL's AL/ALC capabilities are thread-local —
     * any background thread that calls AL10.* functions directly (MusicPlayer's
     * decode-and-play thread) must call {@code AL.createCapabilities(ALC.createCapabilities(getDevice()))}
     * on itself first, or those calls silently fail.
     */
    public long getDevice() { return device; }

    public void cleanup() {
        if (context != 0) alcDestroyContext(context);
        if (device  != 0) alcCloseDevice(device);
    }
}
