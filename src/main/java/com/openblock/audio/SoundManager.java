package com.openblock.audio;

import com.openblock.world.BlockType;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.EXTEfx.*;

public class SoundManager {

    private static final String STEP_DIR   = "sounds/step/";
    private static final String DIG_DIR    = "sounds/dig/";
    private static final String LIQUID_DIR = "sounds/liquid/";

    private final Map<String, List<Integer>> stepBuffers = new HashMap<>();
    private final Map<String, List<Integer>> digBuffers  = new HashMap<>();
    /** Active one-shot sources; checked and freed on the main thread each play() call. */
    private final List<Integer> activeSources = new ArrayList<>();
    private final Random rng = new Random();

    // Damage sounds (sounds/damage/): hurt* = the classic "oof" (always plays on
    // any damage), hit* = generic impact, fallsmall*/fallbig* = fall damage
    private final List<Integer> oofBuffers       = new ArrayList<>();
    private final List<Integer> hitBuffers       = new ArrayList<>();
    private final List<Integer> fallSmallBuffers = new ArrayList<>();
    private final List<Integer> fallBigBuffers   = new ArrayList<>();

    // Rain ambient (sounds/ambient/weather/rain*.ogg). The classic clips have
    // soft fade-in/out edges baked in, so playing them back-to-back always dips
    // to silence at the joins. Minecraft's trick: fire them as OVERLAPPING
    // one-shots — each new clip starts while the previous is ~60% done, so one
    // clip's fade-out crossfades under the next clip's fade-in.
    private final List<Integer> rainBuffers   = new ArrayList<>();
    private final List<Float>   rainDurations = new ArrayList<>();
    private final List<Integer> rainSources   = new ArrayList<>(); // live overlapping clips
    private float rainNextClip = 0f; // countdown until the next clip starts
    private static final float RAIN_GAIN = 0.27f; // master rain volume
    /** Smoothed rain gain — eases toward the target so rain→snow / diving
     *  underwater fades the patter out instead of cutting it. */
    private float rainGain = 0f;
    /** EFX lowpass applied to the rain while the player is underwater; 0 when
     *  EFX is unavailable (rain then just gets quieter, no filter). */
    private int muffleFilter = 0;
    private boolean muffled = false;
    /** Fast-tracking volume multiplier (underwater ducking). Separate from the
     *  slow rainGain easing so surfacing snaps back in ~a quarter second
     *  instead of swelling over a full one. */
    private float duckGain = 1f;

    // UI + pickup one-shots (sounds/random/)
    private int clickBuf, pickupBuf;

    // Splash one-shot buffers
    private int splashBuf1, splashBuf2, heavySplashBuf;
    private boolean nextSplashIsFirst = true;
    // Looping water-flow ambient
    private int waterAmbientBuf;
    private int waterAmbientSrc = 0;
    private boolean waterAmbientPlaying = false;

    public void init() {
        for (String cat : new String[]{"grass", "stone", "sand", "snow", "wood", "gravel"}) {
            loadCategory(STEP_DIR, cat, stepBuffers);
            loadCategory(DIG_DIR,  cat, digBuffers);
        }
        splashBuf1     = loadBuffer(LIQUID_DIR + "splash.ogg");
        splashBuf2     = loadBuffer(LIQUID_DIR + "splash2.ogg");
        heavySplashBuf = loadBuffer(LIQUID_DIR + "heavy_splash.ogg");
        waterAmbientBuf = loadBuffer(LIQUID_DIR + "water.ogg");
        clickBuf  = loadBuffer("sounds/random/clickclassic.ogg");
        pickupBuf = loadBuffer("sounds/random/pickup.ogg");

        // Underwater lowpass for the rain (OpenAL Soft ships EFX everywhere,
        // but degrade gracefully if it's missing)
        try {
            muffleFilter = alGenFilters();
            alFilteri(muffleFilter, AL_FILTER_TYPE, AL_FILTER_LOWPASS);
            alFilterf(muffleFilter, AL_LOWPASS_GAIN,   1.0f);
            alFilterf(muffleFilter, AL_LOWPASS_GAINHF, 0.18f); // highs mostly gone
            if (alGetError() != AL_NO_ERROR) muffleFilter = 0;
        } catch (Throwable t) {
            muffleFilter = 0;
        }

        // Rain ambient clips
        File rainDir = new File("sounds/ambient/weather");
        File[] rainFiles = rainDir.isDirectory()
            ? rainDir.listFiles((d, n) -> n.toLowerCase().startsWith("rain") && n.endsWith(".ogg"))
            : null;
        if (rainFiles != null) {
            for (File f : rainFiles) {
                int buf = loadBuffer(f.getAbsolutePath());
                if (buf != 0) {
                    rainBuffers.add(buf);
                    rainDurations.add(bufferSeconds(buf));
                }
            }
        }

        // Damage sounds — categorised by filename prefix
        File dmgDir = new File("sounds/damage");
        File[] dmgFiles = dmgDir.isDirectory() ? dmgDir.listFiles((d, n) -> n.endsWith(".ogg")) : null;
        if (dmgFiles != null) {
            for (File f : dmgFiles) {
                int buf = loadBuffer(f.getAbsolutePath());
                if (buf == 0) continue;
                String n = f.getName().toLowerCase();
                if (n.startsWith("fallbig"))        fallBigBuffers.add(buf);
                else if (n.startsWith("fallsmall")) fallSmallBuffers.add(buf);
                else if (n.startsWith("hurt"))      oofBuffers.add(buf);
                else                                hitBuffers.add(buf); // hit*
            }
        }
    }

    /** The classic "oof" — layered on top of every damage sound, like old MC. */
    private void playOof() {
        if (oofBuffers.isEmpty()) return;
        playOneShot(oofBuffers.get(rng.nextInt(oofBuffers.size())),
                    0.98f + rng.nextFloat() * 0.04f, 0.55f);
    }

    /** Generic player-hurt (drowning, cactus, ...): oof + impact hit together. */
    public void playHurt() {
        playOof();
        if (hitBuffers.isEmpty()) return;
        playOneShot(hitBuffers.get(rng.nextInt(hitBuffers.size())),
                    0.95f + rng.nextFloat() * 0.10f, 0.45f);
    }

    /** Fall damage: oof + fall impact; big = hard fall (several hearts). */
    public void playFall(boolean big) {
        playOof();
        List<Integer> list = big ? fallBigBuffers : fallSmallBuffers;
        if (list.isEmpty()) list = hitBuffers;
        if (list.isEmpty()) return;
        playOneShot(list.get(rng.nextInt(list.size())), 1.0f, 0.55f);
    }

    /** Quiet block-hit tick while mining (step sound, low pitch and volume). */
    public void playHit(BlockType block) {
        play(stepBuffers, categoryFor(block), 0.55f, 0.10f);
    }

    /** Play splash when entering water. heavy=true for high-fall entry. flowing=true reduces volume. */
    public void playSplash(boolean heavy, boolean flowing) {
        int buf;
        if (heavy) {
            buf = heavySplashBuf;
        } else {
            buf = nextSplashIsFirst ? splashBuf1 : splashBuf2;
            nextSplashIsFirst = !nextSplashIsFirst;
        }
        if (buf == 0) return;
        float gain = heavy ? 0.55f : 0.30f;
        if (flowing) gain *= 0.55f; // flowing water is shallower — quieter splash
        playOneShot(buf, 1.0f, gain);
    }

    /** Call every frame — fades the looping water ambient based on proximity (0 = off, 1 = full). */
    public void updateWaterAmbient(float gain) {
        if (waterAmbientBuf == 0) return;
        if (gain > 0.0f) {
            if (waterAmbientSrc == 0) {
                waterAmbientSrc = alGenSources();
                alSourcei(waterAmbientSrc, AL_SOURCE_RELATIVE, AL_TRUE);
                alSourcei(waterAmbientSrc, AL_BUFFER,          waterAmbientBuf);
                alSourcei(waterAmbientSrc, AL_LOOPING,         AL_TRUE);
            }
            if (!waterAmbientPlaying) {
                alSourcePlay(waterAmbientSrc);
                waterAmbientPlaying = true;
            }
            alSourcef(waterAmbientSrc, AL_GAIN, gain * 0.35f);
        } else if (waterAmbientPlaying) {
            alSourceStop(waterAmbientSrc);
            waterAmbientPlaying = false;
        }
    }

    /**
     * Call every tick with the target loudness: 0 = silent (clear weather,
     * snowing — snow is silent like Minecraft — or deep underwater/underground),
     * up to 1 = full downpour. The actual gain eases toward the target (~1.5s
     * full fade). Clips are layered: a fresh random clip (slight pitch
     * variation) starts at ~55-70% of the current clip's length, so their
     * baked-in edge fades always overlap and the patter never dips to silence.
     * {@code duck} is a fast-followed volume multiplier (underwater ducking) and
     * {@code muffle} lowpasses the rain (player just under the water surface).
     */
    public void updateRainAmbient(float target, float duck, float delta, boolean muffle) {
        if (rainBuffers.isEmpty()) return;

        // Duck follows quickly (~0.25s full swing) — click-free but snappy
        float dstep = delta * 4f;
        if (duckGain < duck)      duckGain = Math.min(duck, duckGain + dstep);
        else if (duckGain > duck) duckGain = Math.max(duck, duckGain - dstep);

        if (muffle != muffled) {
            muffled = muffle;
            if (muffleFilter != 0) {
                for (int src : rainSources) {
                    alSourcei(src, AL_DIRECT_FILTER, muffled ? muffleFilter : AL_FILTER_NULL);
                }
            }
        }

        float step = delta * 0.65f;
        if (rainGain < target)      rainGain = Math.min(target, rainGain + step);
        else if (rainGain > target) rainGain = Math.max(target, rainGain - step);

        // Reap clips that finished playing
        rainSources.removeIf(src -> {
            if (alGetSourcei(src, AL_SOURCE_STATE) != AL_PLAYING) {
                alDeleteSources(src);
                return true;
            }
            return false;
        });

        if (rainGain <= 0.005f) {
            rainNextClip = 0f; // fresh clip immediately when rain returns
            // Live clips have already been faded to ~0 by the loop below —
            // let them run out on their own and get reaped.
            for (int src : rainSources) alSourcef(src, AL_GAIN, 0f);
            return;
        }

        rainNextClip -= delta;
        if (rainNextClip <= 0f) {
            int i = rng.nextInt(rainBuffers.size());
            int src = alGenSources();
            if (src != 0) {
                alSourcei(src, AL_SOURCE_RELATIVE, AL_TRUE);
                alSourcei(src, AL_BUFFER, rainBuffers.get(i));
                alSourcef(src, AL_PITCH, 0.95f + rng.nextFloat() * 0.10f);
                alSourcef(src, AL_GAIN, rainGain * duckGain * RAIN_GAIN);
                if (muffled && muffleFilter != 0) {
                    alSourcei(src, AL_DIRECT_FILTER, muffleFilter);
                }
                alSourcePlay(src);
                rainSources.add(src);
            }
            rainNextClip = rainDurations.get(i) * (0.55f + rng.nextFloat() * 0.15f);
        }

        // All live clips track the smoothed master gain every tick
        for (int src : rainSources) alSourcef(src, AL_GAIN, rainGain * duckGain * RAIN_GAIN);
    }

    /** Buffer length in seconds, from OpenAL's stored PCM properties. */
    private static float bufferSeconds(int buf) {
        int size = alGetBufferi(buf, AL_SIZE);
        int ch   = alGetBufferi(buf, AL_CHANNELS);
        int bits = alGetBufferi(buf, AL_BITS);
        int freq = alGetBufferi(buf, AL_FREQUENCY);
        if (ch == 0 || bits == 0 || freq == 0) return 1f;
        return size / (float) (ch * (bits / 8) * freq);
    }

    private void loadCategory(String dir, String name, Map<String, List<Integer>> target) {
        List<Integer> list = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            File f = new File(dir + name + i + ".ogg");
            if (!f.exists()) break;
            int buf = loadBuffer(f.getAbsolutePath());
            if (buf != 0) list.add(buf);
        }
        if (!list.isEmpty()) target.put(name, list);
    }

    private int loadBuffer(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            ByteBuffer fileData = MemoryUtil.memAlloc(bytes.length);
            fileData.put(bytes).flip();

            ShortBuffer pcm;
            int channels, sampleRate;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(fileData, ch, sr);
                MemoryUtil.memFree(fileData);
                if (pcm == null) return 0;
                channels   = ch.get(0);
                sampleRate = sr.get(0);
            }

            int format = (channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
            int buf = alGenBuffers();
            alBufferData(buf, format, pcm, sampleRate);
            MemoryUtil.memFree(pcm);
            return buf;
        } catch (Exception e) {
            return 0;
        }
    }

    public void playStep(BlockType block) {
        play(stepBuffers, categoryFor(block), 0.9f + rng.nextFloat() * 0.2f, 0.15f);
    }

    /** Item-pickup "pop" with MC's random pitch wobble. */
    public void playPop() {
        if (pickupBuf != 0) {
            playOneShot(pickupBuf, 0.85f + rng.nextFloat() * 0.35f, 0.35f);
        } else {
            // Fallback: a grass step chirped way up — short, high, and quiet
            play(stepBuffers, "grass", 1.9f + rng.nextFloat() * 0.3f, 0.25f);
        }
    }

    /** UI button click — flat pitch, like the classic menu click. */
    public void playClick() {
        if (clickBuf != 0) playOneShot(clickBuf, 1.0f, 0.5f);
    }

    public void playBreak(BlockType block) {
        play(digBuffers, breakCategoryFor(block), 0.8f + rng.nextFloat() * 0.2f, 0.30f);
    }

    private void play(Map<String, List<Integer>> bufMap, String category, float pitch, float gain) {
        List<Integer> list = bufMap.get(category);
        if (list == null || list.isEmpty()) return;
        playOneShot(list.get(rng.nextInt(list.size())), pitch, gain);
    }

    private void playOneShot(int buf, float pitch, float gain) {
        // Free any sources that have finished — all on the main thread
        activeSources.removeIf(src -> {
            if (alGetSourcei(src, AL_SOURCE_STATE) != AL_PLAYING) {
                alDeleteSources(src);
                return true;
            }
            return false;
        });

        if (buf == 0) return;
        int source = alGenSources();
        if (source == 0) return;

        alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
        alSourcei(source, AL_BUFFER, buf);
        alSourcef(source, AL_PITCH, pitch);
        alSourcef(source, AL_GAIN, gain);
        alSourcePlay(source);
        activeSources.add(source);
    }

    private String categoryFor(BlockType block) {
        return switch (block) {
            case STONE, COBBLESTONE, BEDROCK -> "stone";
            case SAND           -> "sand";
            case SNOW_GRASS     -> "snow";
            case LOG            -> "wood";
            case GRAVEL         -> "gravel";
            default             -> "grass"; // GRASS, DIRT, LEAVES, CACTUS
        };
    }

    /** Break sounds: snow blocks use grass (dirt) sound, not snow crunch. */
    private String breakCategoryFor(BlockType block) {
        return switch (block) {
            case STONE, COBBLESTONE, BEDROCK -> "stone";
            case SAND           -> "sand";
            case LOG            -> "wood";
            case GRAVEL         -> "gravel";
            default             -> "grass"; // SNOW_GRASS, GRASS, DIRT, LEAVES, CACTUS
        };
    }

    public void cleanup() {
        if (waterAmbientSrc != 0) { alSourceStop(waterAmbientSrc); alDeleteSources(waterAmbientSrc); }
        for (int src : rainSources) { alSourceStop(src); alDeleteSources(src); }
        rainSources.clear();
        if (muffleFilter != 0) alDeleteFilters(muffleFilter);
        for (int buf : rainBuffers) alDeleteBuffers(buf);
        for (int src : activeSources) alDeleteSources(src);
        activeSources.clear();
        for (List<Integer> list : stepBuffers.values()) for (int buf : list) alDeleteBuffers(buf);
        for (List<Integer> list : digBuffers.values())  for (int buf : list) alDeleteBuffers(buf);
        stepBuffers.clear();
        digBuffers.clear();
        for (int buf : new int[]{splashBuf1, splashBuf2, heavySplashBuf, waterAmbientBuf,
                                 clickBuf, pickupBuf}) {
            if (buf != 0) alDeleteBuffers(buf);
        }
        for (int buf : oofBuffers)       alDeleteBuffers(buf);
        for (int buf : hitBuffers)       alDeleteBuffers(buf);
        for (int buf : fallSmallBuffers) alDeleteBuffers(buf);
        for (int buf : fallBigBuffers)   alDeleteBuffers(buf);
    }
}
