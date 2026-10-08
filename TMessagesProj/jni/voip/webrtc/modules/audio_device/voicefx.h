// NXC Fighter voice effect v2 (header-only, C++11) - calls/VC mic path.
// Chain (pehli APK jaisa): HighPass -> LowShelf -> Presence -> HighShelf
//   -> Compressor1 (soft knee) -> Compressor2 -> Loudness -> Gain -> Saturation
//   -> Reverb(mix) -> Sustain -> Limiter
#pragma once
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <atomic>
#include <vector>
#ifdef __ANDROID__
#include <jni.h>
#endif

namespace nxcfx {

struct Settings {
    bool  enabled     = true;
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
    // fixed (pehli APK wale)
    float knee1 = 40.f;
    float comp2ThrDb = -10.f, comp2Knee = 5.f, comp2Ratio = 12.f, comp2Atk = 0.001f, comp2Rel = 0.05f;
    bool  sustain = true; float sustainTargetDb = -8.f, sustainMax = 12.f;
    bool  reverb = true;  float reverbDelay = 0.035f, reverbFeedback = 0.18f, reverbWet = 0.08f;
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

inline float db2lin(float db) { return std::pow(10.f, db / 20.f); }

// soft-knee feed-forward compressor
struct Comp {
    float g = 0.f;  // smoothed gain (dB, <= 0)
    float run(float x, float thr, float knee, float ratio, float atkC, float relC) {
        float xdb = 20.f * std::log10(std::max(std::fabs(x), 1e-6f));
        float over = xdb - thr, ydb;
        if (knee > 0.f && 2.f * over < -knee) ydb = xdb;
        else if (knee > 0.f && 2.f * std::fabs(over) <= knee) {
            float t = over + knee * 0.5f;
            ydb = xdb + (1.f / ratio - 1.f) * t * t / (2.f * knee);
        } else if (over < 0.f) ydb = xdb;
        else ydb = thr + over / ratio;
        float target = ydb - xdb;
        float c = target < g ? atkC : relC;
        g = c * g + (1.f - c) * target;
        return x * db2lin(g);
    }
};

class Processor {
public:
    // data: interleaved int16, frames = samples per channel.
    void process(int16_t* data, size_t frames, int channels, int sampleRate) {
        const Settings& s = settings();
        if (!s.enabled || channels < 1 || channels > 2 || frames == 0 || sampleRate < 8000) return;
        if (sampleRate != sr_ || channels != ch_) { sr_ = sampleRate; ch_ = channels; reset(s); setFilters(s); }
        else if (dirty().exchange(false)) setFilters(s);

        const float sr = (float)sr_;
        const float gain = db2lin(s.gainDb);
        const float k = std::max(0.0001f, s.drive * 100.f);
        const float a1 = std::exp(-1.f / (std::max(s.attackSec, 1e-5f) * sr));
        const float r1 = std::exp(-1.f / (std::max(s.releaseSec, 1e-4f) * sr));
        const float a2 = std::exp(-1.f / (s.comp2Atk * sr));
        const float r2 = std::exp(-1.f / (s.comp2Rel * sr));
        const float aL = std::exp(-1.f / (0.0001f * sr));
        const float rL = std::exp(-1.f / (0.01f * sr));
        const int blockLen = (int)(0.12f * sr);
        const float wet = s.reverb ? s.reverbWet : 0.f;
        const float fb = s.reverb ? s.reverbFeedback : 0.f;

        for (size_t i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                float x = data[i * channels + c] / 32768.f;
                x = f_[c][0].run(x); x = f_[c][1].run(x); x = f_[c][2].run(x); x = f_[c][3].run(x);
                x = c1_[c].run(x, s.thresholdDb, s.knee1, s.ratio, a1, r1);
                x = c2_[c].run(x, s.comp2ThrDb, s.comp2Knee, s.comp2Ratio, a2, r2);
                x *= s.loudness * gain;
                x = (float(M_PI) + k) * x / (float(M_PI) + k * std::fabs(x));   // saturation
                float dry = x;
                if (!dl_[c].empty()) {                                           // reverb
                    float out = dl_[c][dp_];
                    dl_[c][dp_] = x + fb * out;
                    dry += wet * out;
                }
                dry *= sg_;                                                      // sustain
                dry = lim_[c].run(dry, s.limiterDb, 0.f, 20.f, aL, rL);          // limiter
                dry = std::max(-0.999f, std::min(0.999f, dry));
                sum_ += dry * dry; cnt_++;
                data[i * channels + c] = (int16_t)(dry * 32767.f);
            }
            if (++dp_ >= dlen_) dp_ = 0;
            if (s.sustain) {
                sg_ += (sgTarget_ - sg_) * 0.002f;
                if (cnt_ >= (size_t)blockLen * channels) {
                    float rms = std::sqrt(sum_ / (float)cnt_);
                    float db = 20.f * std::log10(std::max(rms, 0.00001f));
                    if (db < s.sustainTargetDb) {
                        float lift = 1.f + std::min(1.2f, std::max(0.02f, (s.sustainTargetDb - db) * 0.035f));
                        sgCur_ = std::min(s.sustainMax, sgCur_ * lift);
                    } else sgCur_ = std::max(1.f, sgCur_ * 0.82f);
                    sgTarget_ = sgCur_;
                    sum_ = 0; cnt_ = 0;
                }
            } else { sg_ = 1.f; sgTarget_ = 1.f; sgCur_ = 1.f; sum_ = 0; cnt_ = 0; }
        }
    }
private:
    void reset(const Settings& s) {
        for (int c = 0; c < 2; c++) {
            for (auto& b : f_[c]) b.z1 = b.z2 = 0;
            c1_[c].g = c2_[c].g = lim_[c].g = 0.f;
            dlen_ = std::max(1, (int)(s.reverbDelay * sr_));
            dl_[c].assign(dlen_, 0.f);
        }
        dp_ = 0; sg_ = sgTarget_ = sgCur_ = 1.f; sum_ = 0; cnt_ = 0;
    }
    void setFilters(const Settings& s) {
        for (int c = 0; c < 2; c++) {
            f_[c][0].highpass(sr_, 75, 0.7);
            f_[c][1].lowShelf(sr_, 200, s.bassDb);
            f_[c][2].peaking(sr_, 3200, 1.5, s.presenceDb);
            f_[c][3].highShelf(sr_, 6000, s.trebleDb);
        }
    }
    int sr_ = 0, ch_ = 0, dlen_ = 1, dp_ = 0;
    Biquad f_[2][4];
    Comp c1_[2], c2_[2], lim_[2];
    std::vector<float> dl_[2];
    float sg_ = 1.f, sgTarget_ = 1.f, sgCur_ = 1.f, sum_ = 0.f;
    size_t cnt_ = 0;
};

inline Processor& processor() { static Processor p; return p; }

} // namespace nxcfx

#ifdef __ANDROID__
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
