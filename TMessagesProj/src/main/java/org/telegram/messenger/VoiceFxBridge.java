package org.telegram.messenger;

/** Panel ki values ko Java (voice message) aur C++ (calls/VC) dono tak pahunchata hai. */
public class VoiceFxBridge {
    private static native void nativeSet(float gain, float loudness, float drive, float thr,
                                         float presence, float bass, float treble, float limiter);

    // Order: 0 Gain, 1 Loudness, 2 Drive, 3 Threshold, 4 Presence, 5 Bass, 6 Treble, 7 Limiter
    public static final float[] DEF = {18f, 4f, 0.28f, -38f, 8f, 3f, 6f, -2f};
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
        try {
            nativeSet(cur[0], cur[1], cur[2], cur[3], cur[4], cur[5], cur[6], cur[7]);
        } catch (Throwable ignore) {
        }
    }
}
