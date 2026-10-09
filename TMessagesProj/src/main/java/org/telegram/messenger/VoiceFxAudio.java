package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.widget.Toast;

import org.telegram.ui.ActionBar.BaseFragment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** UseDeviceAudio: phone ki audio file call/VC me bajti hai (mic ON hone par). */
public class VoiceFxAudio {
    private static final int REQ = 48011;
    private static final long MAX_OUT_SAMPLES = 48000L * 60 * 10; // 10 minute

    public static volatile boolean enabled = false;

    private static native void nativeClipClear();
    private static native void nativeClipAppend(short[] data, int len);
    private static native void nativeClipPlay(boolean play);

    /** Settings row tap: Enabled hai to band karo, warna file chunne ka picker kholo. */
    public static void onSettingsClick(BaseFragment fragment, Runnable refresh) {
        if (enabled) {
            enabled = false;
            try { nativeClipPlay(false); nativeClipClear(); } catch (Throwable ignore) {}
            if (refresh != null) refresh.run();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("audio/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            fragment.startActivityForResult(i, REQ);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Fragment ke onActivityResultFragment se call karo. */
    public static boolean handleResult(Activity act, int req, int res, Intent data, final Runnable refresh) {
        if (req != REQ) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    nativeClipPlay(false);
                    nativeClipClear();
                    decodeToNative(ApplicationLoader.applicationContext, uri);
                    nativeClipPlay(true);
                    ok = true;
                } catch (Throwable t) {
                    FileLog.e(t);
                    try { nativeClipClear(); } catch (Throwable ignore) {}
                }
                enabled = ok;
                final boolean fok = ok;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override public void run() {
                        if (!fok) {
                            Toast.makeText(ApplicationLoader.applicationContext, "Audio file load nahi hui", Toast.LENGTH_SHORT).show();
                        }
                        if (refresh != null) refresh.run();
                    }
                });
            }
        }, "nxc-audio-decode").start();
        return true;
    }

    // ---- decoder: file -> 48 kHz mono 16-bit PCM -> native ----
    private static short[] outBuf = new short[48000];
    private static int outLen = 0;
    private static long outTotal = 0;

    private static void emit(float v) {
        int s = Math.round(v * 32767f);
        if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
        outBuf[outLen++] = (short) s;
        outTotal++;
        if (outLen == outBuf.length) flush();
    }

    private static void flush() {
        if (outLen > 0) nativeClipAppend(outBuf, outLen);
        outLen = 0;
    }

    private static void decodeToNative(Context ctx, Uri uri) throws Exception {
        outLen = 0; outTotal = 0;
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("no audio track");
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            double step = inRate / 48000.0;
            long outIdx = 0;
            float[] tail = new float[0];
            long tailBase = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone && outTotal < MAX_OUT_SAMPLES) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.asShortBuffer();
                        int shorts = sb.remaining();
                        int frames = shorts / ch;
                        float[] mono = new float[frames];
                        for (int f = 0; f < frames; f++) {
                            float acc = 0;
                            for (int c = 0; c < ch; c++) acc += sb.get(f * ch + c);
                            mono[f] = acc / ch / 32768f;
                        }
                        // resample (linear) to 48 kHz
                        float[] w = new float[tail.length + mono.length];
                        System.arraycopy(tail, 0, w, 0, tail.length);
                        System.arraycopy(mono, 0, w, tail.length, mono.length);
                        long wEnd = tailBase + w.length;
                        while (true) {
                            double t = outIdx * step;
                            long i0 = (long) t;
                            if (i0 + 1 >= wEnd) break;
                            float a = w[(int) (i0 - tailBase)];
                            float b = w[(int) (i0 + 1 - tailBase)];
                            emit(a + (b - a) * (float) (t - i0));
                            outIdx++;
                            if (outTotal >= MAX_OUT_SAMPLES) break;
                        }
                        long keepFrom = Math.min((long) (outIdx * step), wEnd - 1);
                        int off = (int) (keepFrom - tailBase);
                        if (off < 0) off = 0;
                        float[] nt = new float[w.length - off];
                        System.arraycopy(w, off, nt, 0, nt.length);
                        tail = nt;
                        tailBase = tailBase + off;
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int nr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int nc = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (nr != inRate) {
                        // naye rate pe position ko theek rakho
                        double tSec = outIdx / 48000.0;
                        inRate = nr; step = inRate / 48000.0;
                        tail = new float[0];
                        tailBase = (long) (tSec * inRate);
                    }
                    ch = nc;
                }
            }
            flush();
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignore) {}
            ex.release();
        }
        if (outTotal == 0) throw new Exception("empty audio");
    }
}
