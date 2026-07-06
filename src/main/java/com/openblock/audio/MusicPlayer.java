package com.openblock.audio;

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

/**
 * Plays background music from sounds/music/ using OpenAL + STB Vorbis.
 *
 * Rules (Minecraft-style):
 *  - Day tracks (all except "13 (Gold LP).ogg") shuffle randomly; every track
 *    plays once before the list repeats.
 *  - "13 (Gold LP).ogg" has a NIGHT_CHANCE probability of playing at night.
 *  - A random silence gap (45–210 s) separates tracks, like Minecraft.
 */
public class MusicPlayer {

    private static final String MUSIC_DIR    = "sounds/music/";
    private static final String NIGHT_TRACK  = "13 (Gold LP).ogg";
    private static final float  NIGHT_CHANCE = 0.30f;
    private static final float  MIN_DELAY    = 45f;
    private static final float  MAX_DELAY    = 210f;

    private final List<String> dayTracks = new ArrayList<>();
    private final List<String> shuffled  = new ArrayList<>();
    private int shuffleIndex = 0;

    private float cooldown = 10f;
    private final Random rng = new Random();

    private volatile boolean playing = false;
    private volatile int activeSource = 0;

    public void init() {
        File dir = new File(MUSIC_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) ->
                name.endsWith(".ogg") && !name.equals(NIGHT_TRACK));
            if (files != null) {
                for (File f : files) dayTracks.add(f.getAbsolutePath());
            }
        }
        reshuffleDay();
    }

    /** Called every game frame. isDay should come from DayNightCycle.isSunUp(). */
    public void update(float delta, boolean isDay) {
        if (playing) return;

        cooldown -= delta;
        if (cooldown > 0) return;

        if (isDay) {
            if (!shuffled.isEmpty()) {
                if (shuffleIndex >= shuffled.size()) reshuffleDay();
                playFile(shuffled.get(shuffleIndex++));
            }
        } else {
            if (rng.nextFloat() < NIGHT_CHANCE) {
                File f = new File(MUSIC_DIR + NIGHT_TRACK);
                if (f.exists()) playFile(f.getAbsolutePath());
                else            resetCooldown();
            } else {
                resetCooldown();
            }
        }
    }

    private void playFile(String path) {
        playing = true;
        resetCooldown();

        Thread t = new Thread(() -> {
            int source = 0;
            int buffer = 0;
            try {
                byte[] fileBytes = Files.readAllBytes(Paths.get(path));
                ByteBuffer fileData = MemoryUtil.memAlloc(fileBytes.length);
                fileData.put(fileBytes).flip();

                ShortBuffer pcm;
                int channels, sampleRate;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer channelsBuf   = stack.mallocInt(1);
                    IntBuffer sampleRateBuf = stack.mallocInt(1);
                    pcm = STBVorbis.stb_vorbis_decode_memory(fileData, channelsBuf, sampleRateBuf);
                    MemoryUtil.memFree(fileData);
                    if (pcm == null) return;
                    channels   = channelsBuf.get(0);
                    sampleRate = sampleRateBuf.get(0);
                }

                int format = (channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

                buffer = alGenBuffers();
                alBufferData(buffer, format, pcm, sampleRate);
                MemoryUtil.memFree(pcm);

                source = alGenSources();
                alSourcei(source, AL_BUFFER, buffer);
                alSourcef(source, AL_GAIN, 1.0f);
                alSourcePlay(source);
                activeSource = source;

                while (alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING) {
                    Thread.sleep(200);
                }

            } catch (Exception e) {
                // silently skip unplayable tracks
            } finally {
                activeSource = 0;
                if (source != 0) alDeleteSources(source);
                if (buffer != 0) alDeleteBuffers(buffer);
                playing = false;
            }
        }, "music-player");

        t.setDaemon(true);
        t.start();
    }

    private void resetCooldown() {
        cooldown = MIN_DELAY + rng.nextFloat() * (MAX_DELAY - MIN_DELAY);
    }

    private void reshuffleDay() {
        shuffled.clear();
        shuffled.addAll(dayTracks);
        Collections.shuffle(shuffled, rng);
        shuffleIndex = 0;
    }

    public void cleanup() {
        int src = activeSource;
        if (src != 0) alSourceStop(src);
    }
}
