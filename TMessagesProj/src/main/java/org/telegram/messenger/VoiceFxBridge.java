package org.telegram.messenger;

/** Panel ki values ko Java (voice message) aur C++ (calls/VC) dono tak pahunchata hai. */
public class VoiceFxBridge {
    private static native void nativeSet(float gain, float loudness, float drive, float thr,
                                         float presence, float bass, float treble, float limiter);

    private static native void nativeEnable(boolean on);
    private static native void nativeNoise(float n);
    private static native void nativeMode(int m);
    private static native void nativeBot(float vol, float gain, float over, float echoDelay, float echoDecay);
    public static int mode = 1;   // 0 = fighter, 1 = bot jaisi awaaz
    // bot: volume(level), gain, overdrive, echo delay (ms), echo decay
    public static final float[] botDef = {100f, 0f, 0f, 70f, 0.32f};
    public static final float[] bot = botDef.clone();
    public static float noise = 0.003f;   // halka smooth noise level (0..0.01)
    public static boolean fxOn = true;   // false = normal awaaz (effect band)

    // Order: 0 Gain, 1 Loudness, 2 Drive, 3 Threshold, 4 Presence, 5 Bass, 6 Treble, 7 Limiter
    public static final float[] DEF = {24f, 4f, 0.28f, -38f, 8f, 3f, 6f, -2f};
    public static final float[] cur = DEF.clone();

    public static void apply() {
        VoiceFx.gainDb = cur[0];
        VoiceFx.loudness = cur[1];
        VoiceFx.drive = cur[2];
        VoiceFx.thresholdDb = cur[3];
        VoiceFx.presenceDb = cur[4];
        VoiceFx.bassDb = cur[5];
        VoiceFx.trebleDb = cur[6];
        VoiceFx.limiterDb = cur[7];
        VoiceFx.enabled = fxOn;
        try { nativeEnable(fxOn); } catch (Throwable ignore) {}
        try { nativeNoise(noise); } catch (Throwable ignore) {}
        try { nativeMode(mode); nativeBot(bot[0], bot[1], bot[2], bot[3], bot[4]); } catch (Throwable ignore) {}
        try {
            nativeSet(cur[0], cur[1], cur[2], cur[3], cur[4], cur[5], cur[6], cur[7]);
        } catch (Throwable ignore) {
        }
    }
}
