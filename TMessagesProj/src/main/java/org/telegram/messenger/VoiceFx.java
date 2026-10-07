package org.telegram.messenger;

/**
 * NXC Fighter voice effect for Telegram voice messages.
 * Chain: HighPass -> LowShelf -> Presence -> HighShelf -> Compressor
 *        -> Loudness -> Gain -> Saturation -> Limiter
 * Input: 16-bit mono PCM (Telegram records voice at 16000 Hz).
 */
public class VoiceFx {
    public static boolean enabled = true;
    public static final int SR = 16000;

    // ---- Settings (app wale values comment me hain) ----
    public static float gainDb = 6f;          // app: 24
    public static float loudness = 1f;        // app: 4 (linear)
    public static float drive = 0.28f;        // app: 0.28
    public static float thresholdDb = -38f;   // app: -38
    public static float ratio = 12f;          // app: 12
    public static float attackSec = 0.003f;   // app: 0.0001
    public static float releaseSec = 0.05f;   // app: 0.03
    public static float presenceDb = 8f;      // app: 8
    public static float bassDb = 4f;          // app: 4
    public static float trebleDb = 6f;        // app: 6
    public static float limiterDb = -2f;      // app: -2

    private static final Biquad hp = new Biquad();
    private static final Biquad low = new Biquad();
    private static final Biquad pres = new Biquad();
    private static final Biquad high = new Biquad();
    private static float env = 0f;

    /** Recording shuru hone par ek baar call karo. */
    public static synchronized void reset() {
        hp.highpass(75, 0.7f);
        low.lowShelf(200, bassDb);
        pres.peaking(3200, 1.5f, presenceDb);
        high.highShelf(6000, trebleDb);
        hp.clear(); low.clear(); pres.clear(); high.clear();
        env = 0f;
    }

    /** buf me len samples (short) ko in-place process karta hai. */
    public static synchronized void process(short[] buf, int len) {
        if (!enabled) return;
        float gain = db2lin(gainDb);
        float thr = thresholdDb;
        float atk = (float) Math.exp(-1.0 / (attackSec * SR));
        float rel = (float) Math.exp(-1.0 / (releaseSec * SR));
        float ceil = db2lin(limiterDb);
        float k = Math.max(0.0001f, drive * 100f);

        for (int i = 0; i < len; i++) {
            float x = buf[i] / 32768f;
            x = hp.run(x);
            x = low.run(x);
            x = pres.run(x);
            x = high.run(x);

            // compressor
            float a = Math.abs(x);
            env = a > env ? atk * env + (1 - atk) * a : rel * env + (1 - rel) * a;
            float levelDb = 20f * (float) Math.log10(Math.max(env, 1e-6f));
            if (levelDb > thr) {
                float outDb = thr + (levelDb - thr) / ratio;
                x *= db2lin(outDb - levelDb);
            }

            x *= loudness;
            x *= gain;

            // saturation
            x = (float) ((Math.PI + k) * x / (Math.PI + k * Math.abs(x)));

            // limiter (hard ceiling)
            if (x > ceil) x = ceil;
            if (x < -ceil) x = -ceil;

            buf[i] = (short) (x * 32767f);
        }
    }

    private static float db2lin(float db) { return (float) Math.pow(10, db / 20.0); }

    private static class Biquad {
        float b0 = 1, b1, b2, a1, a2, z1, z2;
        void clear() { z1 = z2 = 0; }
        float run(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
        void set(float nb0, float nb1, float nb2, float a0, float na1, float na2) {
            b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0;
        }
        void highpass(float f, float q) {
            double w = 2 * Math.PI * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            set((float) ((1 + c) / 2), (float) -(1 + c), (float) ((1 + c) / 2),
                    (float) (1 + al), (float) (-2 * c), (float) (1 - al));
        }
        void peaking(float f, float q, float db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / SR, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            set((float) (1 + al * A), (float) (-2 * c), (float) (1 - al * A),
                    (float) (1 + al / A), (float) (-2 * c), (float) (1 - al / A));
        }
        void lowShelf(float f, float db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / SR, c = Math.cos(w), s = Math.sin(w);
            double al = s / 2 * Math.sqrt(2), t = 2 * Math.sqrt(A) * al;
            set((float) (A * ((A + 1) - (A - 1) * c + t)), (float) (2 * A * ((A - 1) - (A + 1) * c)),
                    (float) (A * ((A + 1) - (A - 1) * c - t)), (float) ((A + 1) + (A - 1) * c + t),
                    (float) (-2 * ((A - 1) + (A + 1) * c)), (float) ((A + 1) + (A - 1) * c - t));
        }
        void highShelf(float f, float db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / SR, c = Math.cos(w), s = Math.sin(w);
            double al = s / 2 * Math.sqrt(2), t = 2 * Math.sqrt(A) * al;
            set((float) (A * ((A + 1) + (A - 1) * c + t)), (float) (-2 * A * ((A - 1) + (A + 1) * c)),
                    (float) (A * ((A + 1) + (A - 1) * c - t)), (float) ((A + 1) - (A - 1) * c + t),
                    (float) (2 * ((A - 1) - (A + 1) * c)), (float) ((A + 1) - (A - 1) * c - t));
        }
    }
}
