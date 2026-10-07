// NXC Fighter voice effect (header-only, C++11). Calls/VC mic path ke liye.
// Chain: HighPass -> LowShelf -> Presence -> HighShelf -> Compressor
//        -> Loudness -> Gain -> Saturation -> Limiter
#pragma once
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <atomic>
#ifdef __ANDROID__
#include <jni.h>
#endif

namespace nxcfx {

struct Settings {
    bool  enabled     = true;
    bool  testMode    = true;     // TEST: awaaz robot jaisi katne wali aayegi. Theek hone par false kar do.
    float gainDb      = 24.f;
    float loudness    = 4.f;
    float drive       = 0.28f;
    float thresholdDb = -38.f;
    float ratio       = 12.f;
    float attackSec   = 0.0001f;
    float releaseSec  = 0.03f;
    float presenceDb  = 8.f;
    float bassDb      = 4.f;
    float trebleDb    = 6.f;
    float limiterDb   = -2.f;
};

inline Settings& settings() { static Settings s; return s; }
inline std::atomic<bool>& dirty() { static std::atomic<bool> d(false); return d; }

struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    void set(double nb0, double nb1, double nb2, double a0, double na1, double na2) {
        b0 = float(nb0 / a0); b1 = float(nb1 / a0); b2 = float(nb2 / a0);
        a1 = float(na1 / a0); a2 = float(na2 / a0);
    }
    float run(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void highpass(double sr, double f, double q) {
        double w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q);
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }
    void peaking(double sr, double f, double q, double db) {
        double A = std::pow(10, db / 40), w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q);
        set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }
    void lowShelf(double sr, double f, double db) {
        double A = std::pow(10, db / 40), w = 2 * M_PI * f / sr, c = std::cos(w), s = std::sin(w);
        double al = s / 2 * std::sqrt(2.0), t = 2 * std::sqrt(A) * al;
        set(A * ((A + 1) - (A - 1) * c + t), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - t),
            (A + 1) + (A - 1) * c + t, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - t);
    }
    void highShelf(double sr, double f, double db) {
        double A = std::pow(10, db / 40), w = 2 * M_PI * f / sr, c = std::cos(w), s = std::sin(w);
        double al = s / 2 * std::sqrt(2.0), t = 2 * std::sqrt(A) * al;
        set(A * ((A + 1) + (A - 1) * c + t), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - t),
            (A + 1) - (A - 1) * c + t, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - t);
    }
};

class Processor {
public:
    // data: interleaved int16 samples, frames = samples per channel.
    void process(int16_t* data, size_t frames, int channels, int sampleRate) {
        const Settings& s = settings();
        if (!s.enabled || channels < 1 || channels > 2 || frames == 0) return;
        if (sampleRate != sr_ || channels != ch_ || dirty().exchange(false)) init(sampleRate, channels, s);

        const float gain = db2lin(s.gainDb);
        const float ceil = db2lin(s.limiterDb);
        const float k = std::max(0.0001f, s.drive * 100.f);
        const float atk = std::exp(-1.f / (s.attackSec * sr_));
        const float rel = std::exp(-1.f / (s.releaseSec * sr_));

        for (size_t i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                float x = data[i * channels + c] / 32768.f;
                x = f_[c][0].run(x);
                x = f_[c][1].run(x);
                x = f_[c][2].run(x);
                x = f_[c][3].run(x);

                float a = std::fabs(x);
                env_[c] = a > env_[c] ? atk * env_[c] + (1 - atk) * a
                                      : rel * env_[c] + (1 - rel) * a;
                float lvl = 20.f * std::log10(std::max(env_[c], 1e-6f));
                if (lvl > s.thresholdDb) {
                    float out = s.thresholdDb + (lvl - s.thresholdDb) / s.ratio;
                    x *= db2lin(out - lvl);
                }
                x *= s.loudness * gain;
                if (s.testMode) {
                    phase_ += 1.0 / sr_;
                    if (phase_ > 1.0) phase_ -= 1.0;
                    x *= (std::sin(2.0 * M_PI * 20.0 * phase_) > 0 ? 1.0f : 0.05f);
                }
                x = (float(M_PI) + k) * x / (float(M_PI) + k * std::fabs(x));
                x = std::max(-ceil, std::min(ceil, x));
                data[i * channels + c] = int16_t(x * 32767.f);
            }
        }
    }
private:
    static float db2lin(float db) { return std::pow(10.f, db / 20.f); }
    void init(int sr, int ch, const Settings& s) {
        sr_ = sr; ch_ = ch;
        for (int c = 0; c < 2; c++) {
            f_[c][0].highpass(sr, 75, 0.7);
            f_[c][1].lowShelf(sr, 200, s.bassDb);
            f_[c][2].peaking(sr, 3200, 1.5, s.presenceDb);
            f_[c][3].highShelf(sr, 6000, s.trebleDb);
            for (auto& b : f_[c]) b.z1 = b.z2 = 0;
            env_[c] = 0;
        }
    }
    int sr_ = 0, ch_ = 0;
    double phase_ = 0;
    Biquad f_[2][4];
    float env_[2] = {0, 0};
};

inline Processor& processor() { static Processor p; return p; }

} // namespace nxcfx

#ifdef __ANDROID__
// Java (VoiceFxBridge) se live slider values yahan aati hain.
extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_VoiceFxBridge_nativeSet(JNIEnv*, jclass,
        jfloat gain, jfloat loudness, jfloat drive, jfloat thr,
        jfloat presence, jfloat bass, jfloat treble, jfloat limiter) {
    nxcfx::Settings& s = nxcfx::settings();
    s.gainDb = gain; s.loudness = loudness; s.drive = drive;
    s.thresholdDb = thr; s.presenceDb = presence; s.bassDb = bass;
    s.trebleDb = treble; s.limiterDb = limiter;
    nxcfx::dirty().store(true);
}
#endif
