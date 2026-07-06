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

public class SoundManager {

    private static final String STEP_DIR   = "sounds/step/";
    private static final String DIG_DIR    = "sounds/dig/";
    private static final String LIQUID_DIR = "sounds/liquid/";

    private final Map<String, List<Integer>> stepBuffers = new HashMap<>();
    private final Map<String, List<Integer>> digBuffers  = new HashMap<>();
    /** Active one-shot sources; checked and freed on the main thread each play() call. */
    private final List<Integer> activeSources = new ArrayList<>();
    private final Random rng = new Random();

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
            case STONE, BEDROCK -> "stone";
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
            case STONE, BEDROCK -> "stone";
            case SAND           -> "sand";
            case LOG            -> "wood";
            case GRAVEL         -> "gravel";
            default             -> "grass"; // SNOW_GRASS, GRASS, DIRT, LEAVES, CACTUS
        };
    }

    public void cleanup() {
        if (waterAmbientSrc != 0) { alSourceStop(waterAmbientSrc); alDeleteSources(waterAmbientSrc); }
        for (int src : activeSources) alDeleteSources(src);
        activeSources.clear();
        for (List<Integer> list : stepBuffers.values()) for (int buf : list) alDeleteBuffers(buf);
        for (List<Integer> list : digBuffers.values())  for (int buf : list) alDeleteBuffers(buf);
        stepBuffers.clear();
        digBuffers.clear();
        for (int buf : new int[]{splashBuf1, splashBuf2, heavySplashBuf, waterAmbientBuf}) {
            if (buf != 0) alDeleteBuffers(buf);
        }
    }
}
