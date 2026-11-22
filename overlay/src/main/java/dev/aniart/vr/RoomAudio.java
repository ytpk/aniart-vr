package dev.aniart.vr;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Stereo rendering of a virtual 7.1 speaker layout and first room reflections. */
final class RoomAudio {
    private static final int CHANNELS = 8;
    private static final int PATHS = 5;
    private static final float SPEED_OF_SOUND = 343f;
    // PCM order: front L/R, center, LFE, rear L/R, side L/R. Positive z is forward.
    private static final float[] X = {-2.45f, 2.45f, 0f, 0f, -2.55f, 2.55f, -3.45f, 3.45f};
    private static final float[] Z = {4.75f, 4.75f, 4.90f, 0f, -2.75f, -2.75f, .12f, .12f};
    private static final float[] LEVEL = {.67f, .67f, .56f, .42f, .46f, .46f, .51f, .51f};
    private static final float INV_PCM = 1f / 32768f;
    private final int rate;
    private final int size, mask;
    private final float[][] history = new float[CHANNELS][];
    private final float[][] late = new float[2][];
    private final float[][] gain = new float[2][CHANNELS * PATHS];
    private final float[][] targetGain = new float[2][CHANNELS * PATHS];
    private final float[][] delay = new float[2][CHANNELS * PATHS];
    private final float[][] targetDelay = new float[2][CHANNELS * PATHS];
    private final float[][] directLow = new float[2][CHANNELS];
    private final float[][] shadow = new float[2][CHANNELS];
    private final float[] speakers = new float[CHANNELS];
    private final float[] damped = new float[CHANNELS];
    private final int[] tailTaps;
    private float bass, spreadLow;
    private int cursor;
    private boolean initialized;

    RoomAudio(int sampleRate) {
        rate = sampleRate;
        int capacity = 1;
        while (capacity < sampleRate / 8 + 4) capacity <<= 1;
        size = capacity;
        mask = capacity - 1;
        for (int i = 0; i < history.length; i++) history[i] = new float[size];
        late[0] = new float[size];
        late[1] = new float[size];
        tailTaps = new int[]{Math.round(rate * .049f), Math.round(rate * .067f)};
    }

    void updatePose(float yaw) {
        if (!Float.isFinite(yaw)) yaw = 0;
        float cosine = (float) Math.cos(yaw), sine = (float) Math.sin(yaw);
        for (int channel = 0; channel < CHANNELS; channel++) {
            if (channel == 3) continue;
            float x = X[channel], z = Z[channel];
            float distance = hypotenuse(x, z);
            imagePath(channel, 0, x, z, 0f, distance, LEVEL[channel], cosine, sine);
            imagePath(channel, 1, -7.70f - x, z, 0f, distance, .16f * LEVEL[channel], cosine, sine);
            imagePath(channel, 2, 7.70f - x, z, 0f, distance, .16f * LEVEL[channel], cosine, sine);
            imagePath(channel, 3, x, -6.40f - z, .6f, distance, .12f * LEVEL[channel], cosine, sine);
            imagePath(channel, 4, x, z, 5.3f, distance, .095f * LEVEL[channel], cosine, sine);
        }
        if (!initialized) {
            for (int ear = 0; ear < 2; ++ear) {
                System.arraycopy(targetGain[ear], 0, gain[ear], 0, gain[ear].length);
                System.arraycopy(targetDelay[ear], 0, delay[ear], 0, delay[ear].length);
            }
            initialized = true;
        }
    }

    private void imagePath(int channel, int path, float x, float z, float y, float reference,
                           float level, float cosine, float sine) {
        float distance = (float) Math.sqrt(x*x + z*z + y*y);
        float lateral = (x*cosine - z*sine) / Math.max(.05f, distance);
        float side = Math.max(-1, Math.min(1, lateral));
        float after = Math.max(0, distance - reference) / SPEED_OF_SOUND;
        float reflectionLoss = path == 0 ? 1 : Math.min(1, reference / distance);
        int index = channel * PATHS + path;
        for (int ear = 0; ear < 2; ear++) {
            float facing = (ear == 0 ? -1 : 1) * side;
            targetGain[ear][index] = level * reflectionLoss * (float) Math.sqrt(.5f + .43f * facing);
            targetDelay[ear][index] = Math.min(size - 2, rate * (after + .00035f * (1 - facing)));
            if (path == 0) shadow[ear][channel] = .44f * Math.max(0, -facing);
        }
    }

    private static float hypotenuse(float x, float y) { return (float) Math.sqrt(x*x + y*y); }

    void renderFrame(ByteBuffer input, ByteBuffer output, int channels) {
        if (channels == 6 || channels == 8) {
            for (int i = 0; i < channels; i++) speakers[i] = input.getShort() * INV_PCM;
            if (channels == 6) speakers[6] = speakers[7] = 0f;
        } else {
            float left = input.getShort() * INV_PCM, right = channels == 1 ? left : input.getShort() * INV_PCM;
            float center = (left + right) * .5f;
            float difference = (left - right) * .5f;
            spreadLow += .075f * (difference - spreadLow);
            float spread = difference - .70f * spreadLow;
            speakers[0] = left * .90f;
            speakers[1] = right * .90f;
            speakers[2] = center * .33f;
            speakers[3] = center * .24f;
            speakers[4] = spread * .24f;
            speakers[5] = -spread * .24f;
            speakers[6] = spread * .39f;
            speakers[7] = -spread * .39f;
        }

        float send = 0;
        for (int i = 0; i < CHANNELS; ++i) {
            history[i][cursor] = speakers[i];
            damped[i] += .15f * (speakers[i] - damped[i]);
            if (i != 3) send += damped[i];
        }
        bass += Math.min(.08f, 700f / rate) * (speakers[3] - bass);
        float tailLeft = late[0][(cursor - tailTaps[0]) & mask];
        float tailRight = late[1][(cursor - tailTaps[1]) & mask];
        late[0][cursor] = send * .012f + tailRight * .30f;
        late[1][cursor] = send * .012f + tailLeft * .26f;

        for (int ear = 0; ear < 2; ++ear) {
            float sample = bass * LEVEL[3] + (ear == 0 ? tailLeft : tailRight);
            for (int channel = 0; channel < CHANNELS; channel++) {
                if (channel == 3) continue;
                int base = channel * PATHS;
                float direct = tap(ear, channel, base);
                float low = directLow[ear][channel];
                low += .18f * (direct - low);
                directLow[ear][channel] = low;
                sample += gain[ear][base] * (direct + (low - direct) * shadow[ear][channel]);
                for (int path = 1; path < PATHS; path++) {
                    int index = base + path;
                    sample += tap(ear, channel, index) * gain[ear][index];
                }
            }
            output.putShort(pcm(sample));
        }
        cursor = (cursor + 1) & mask;
    }

    private float tap(int ear, int channel, int path) {
        gain[ear][path] += .003f * (targetGain[ear][path] - gain[ear][path]);
        float time = delay[ear][path];
        time += .0015f * (targetDelay[ear][path] - time);
        delay[ear][path] = time;
        int whole = (int) time;
        int index = (cursor - whole) & mask;
        float first = history[channel][index];
        return first + (time - whole) * (history[channel][(index - 1) & mask] - first);
    }

    private static short pcm(float value) {
        float magnitude = Math.abs(value);
        if (magnitude > .82f) {
            float excess = magnitude - .82f;
            value = Math.copySign(.82f + .17f * excess / (.17f + excess), value);
        }
        return (short) Math.round(value * 32767f);
    }

    void reset() {
        for (float[] channel : history) Arrays.fill(channel, 0);
        for (float[] ear : late) Arrays.fill(ear, 0);
        for (float[] ear : directLow) Arrays.fill(ear, 0);
        Arrays.fill(damped, 0);
        cursor = 0;
        bass = spreadLow = 0;
        initialized = false;
    }
}
