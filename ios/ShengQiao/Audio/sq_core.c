/* 声桥 · 音频核心实现。算法与电脑端 mic.py、网页版 player-core.js 相同。 */
#include "sq_core.h"

#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define CAP (1 << 17)            /* 131072 帧 ≈ 2.7 s @ 48 kHz */
#define MASK (CAP - 1)
#define FADE 128                 /* 淡入淡出 / 储备长度（帧） */
#define KP 0.1                   /* 变速比例系数 */
#define MAXADJ 0.005             /* 最大变速 ±0.5% */
#define EMA_TAU 1.0              /* 水位平滑时间常数（秒） */
#define RELAX_MARGIN 0.035       /* 15 秒内最低水位高于它才收回附加缓冲 */
#define MAX_EXTRA_MS 400.0

struct SQPlayer {
    float L[CAP];
    float R[CAP];
    _Atomic int64_t w;           /* 写指针（生产者） */
    _Atomic int64_t rPub;        /* 读指针发布值（消费者写，生产者读） */

    /* 控制（任意线程写，消费者读） */
    _Atomic double reqTargetMs;
    _Atomic int targetChanged;
    _Atomic float gain;
    _Atomic double reqSrcRate;
    _Atomic double reqDstRate;
    _Atomic int ratesChanged;
    _Atomic int resetRequested;

    /* 统计（消费者写，任意线程读） */
    _Atomic int32_t underruns, skips, overflows, state;
    _Atomic double sLevel, sEma, sTarget, sAdj;
    _Atomic float sRms;

    /* 仅消费者使用 */
    int64_t r;
    double frac;
    double srcRate, dstRate, ratio0;
    double targetMs, extraMs;
    int playing;
    float env;
    double ema, adj, clock;
    double lastUnderrunAt, lastRelaxAt, playStartAt, winMin;
    int forceTrim;
    float lastL, lastR;
};

static inline float catmull(float y0, float y1, float y2, float y3, float f) {
    return y1 + 0.5f * f * (y2 - y0 + f * (2.f * y0 - 5.f * y1 + 4.f * y2 - y3 + f * (3.f * (y1 - y2) + y3 - y0)));
}

static inline float softclip(float x) {
    if (x > 0.95f) return 0.95f + 0.05f * tanhf((x - 0.95f) / 0.05f);
    if (x < -0.95f) return -0.95f - 0.05f * tanhf((-x - 0.95f) / 0.05f);
    return x;
}

static void consumer_reset(SQPlayer *p, int64_t w) {
    p->r = w;                    /* 丢掉积压，从现在开始重新缓冲 */
    p->frac = 0;
    p->playing = 0;
    p->env = 0;
    p->ema = 0;
    p->adj = 0;
    p->lastUnderrunAt = -1e9;
    p->lastRelaxAt = p->clock;
    p->playStartAt = p->clock;
    p->winMin = 1e9;
    p->forceTrim = 0;
    atomic_store_explicit(&p->rPub, p->r, memory_order_release);
}

SQPlayer *sq_player_create(double srcRate, double dstRate) {
    SQPlayer *p = (SQPlayer *)calloc(1, sizeof(SQPlayer));
    if (!p) return NULL;
    atomic_init(&p->w, 2);
    atomic_init(&p->rPub, 2);
    atomic_init(&p->reqTargetMs, 70.0);
    atomic_init(&p->targetChanged, 0);
    atomic_init(&p->gain, 1.0f);
    atomic_init(&p->reqSrcRate, srcRate);
    atomic_init(&p->reqDstRate, dstRate);
    atomic_init(&p->ratesChanged, 0);
    atomic_init(&p->resetRequested, 0);
    p->srcRate = srcRate > 0 ? srcRate : 48000;
    p->dstRate = dstRate > 0 ? dstRate : 48000;
    p->ratio0 = p->srcRate / p->dstRate;
    p->targetMs = 70.0;
    p->extraMs = 0;
    p->clock = 0;
    p->lastL = p->lastR = 0;
    consumer_reset(p, 2);
    return p;
}

void sq_player_destroy(SQPlayer *p) { free(p); }

void sq_player_push(SQPlayer *p, const int16_t *pcm, int32_t n, int32_t ch) {
    if (n <= 0 || ch <= 0) return;
    int64_t w = atomic_load_explicit(&p->w, memory_order_relaxed);
    int64_t r = atomic_load_explicit(&p->rPub, memory_order_acquire);
    if (w + n - r > CAP - 16) {  /* 缓冲满（比如音频引擎停着）：丢弃新数据，恢复后由消费者跳到最新 */
        atomic_fetch_add_explicit(&p->overflows, 1, memory_order_relaxed);
        return;
    }
    const float s = 1.0f / 32768.0f;
    for (int32_t i = 0; i < n; i++) {
        int64_t k = (w + i) & MASK;
        float a = pcm[i * ch] * s;
        p->L[k] = a;
        p->R[k] = ch > 1 ? pcm[i * ch + 1] * s : a;
    }
    atomic_store_explicit(&p->w, w + n, memory_order_release);
}

void sq_player_set_target_ms(SQPlayer *p, double ms) {
    atomic_store(&p->reqTargetMs, ms);
    atomic_store(&p->targetChanged, 1);
}

void sq_player_set_gain(SQPlayer *p, float g) { atomic_store(&p->gain, g); }

void sq_player_set_rates(SQPlayer *p, double src, double dst) {
    atomic_store(&p->reqSrcRate, src);
    atomic_store(&p->reqDstRate, dst);
    atomic_store(&p->ratesChanged, 1);
}

void sq_player_request_reset(SQPlayer *p) { atomic_store(&p->resetRequested, 1); }

void sq_player_get_stats(SQPlayer *p, SQPlayerStats *o) {
    o->state = atomic_load(&p->state);
    o->levelMs = atomic_load(&p->sLevel);
    o->emaMs = atomic_load(&p->sEma);
    o->targetMs = atomic_load(&p->sTarget);
    o->adjust = atomic_load(&p->sAdj);
    o->underruns = atomic_load(&p->underruns);
    o->skips = atomic_load(&p->skips);
    o->overflows = atomic_load(&p->overflows);
    o->rms = atomic_load(&p->sRms);
}

static inline double level_sec(const SQPlayer *p, int64_t w) {
    double v = (double)(w - p->r) - p->frac;
    return v > 0 ? v / p->srcRate : 0;
}

/* 交叉淡化：旧位置淡出、新位置淡入，写 m 帧 */
static int32_t crossfade_to(SQPlayer *p, float *L, float *R, int64_t newR, double step, int32_t m, float g) {
    int64_t r1 = p->r, r2 = newR;
    double f1 = p->frac, f2 = 0;
    float env = p->env;
    for (int32_t i = 0; i < m; i++) {
        float a = (float)(i + 1) / (float)m;
        int64_t o0 = (r1 - 1) & MASK, o1 = r1 & MASK, o2 = (r1 + 1) & MASK, o3 = (r1 + 2) & MASK;
        int64_t n0 = (r2 - 1) & MASK, n1 = r2 & MASK, n2 = (r2 + 1) & MASK, n3 = (r2 + 2) & MASK;
        float lo = catmull(p->L[o0], p->L[o1], p->L[o2], p->L[o3], (float)f1);
        float ro = catmull(p->R[o0], p->R[o1], p->R[o2], p->R[o3], (float)f1);
        float ln = catmull(p->L[n0], p->L[n1], p->L[n2], p->L[n3], (float)f2);
        float rn = catmull(p->R[n0], p->R[n1], p->R[n2], p->R[n3], (float)f2);
        L[i] = softclip(((1 - a) * lo * env + a * ln) * g);
        R[i] = softclip(((1 - a) * ro * env + a * rn) * g);
        f1 += step; if (f1 >= 1) { int64_t k = (int64_t)f1; r1 += k; f1 -= (double)k; }
        f2 += step; if (f2 >= 1) { int64_t k = (int64_t)f2; r2 += k; f2 -= (double)k; }
    }
    p->r = r2;
    p->frac = f2;
    p->env = 1.0f;
    atomic_fetch_add_explicit(&p->skips, 1, memory_order_relaxed);
    return m;
}

static void tail(float *L, float *R, int32_t from, int32_t to, float *tl, float *tr) {
    float a = *tl, b = *tr;
    for (int32_t z = from; z < to; z++) {
        a *= 0.96f; b *= 0.96f;
        L[z] = a; R[z] = b;
    }
    if (fabsf(a) < 1e-6f) a = 0;
    if (fabsf(b) < 1e-6f) b = 0;
    *tl = a; *tr = b;
}

void sq_player_render(SQPlayer *p, float *L, float *R, int32_t n) {
    if (n <= 0) return;
    if (atomic_exchange(&p->ratesChanged, 0)) {
        p->srcRate = atomic_load(&p->reqSrcRate);
        p->dstRate = atomic_load(&p->reqDstRate);
        if (p->srcRate <= 0) p->srcRate = 48000;
        if (p->dstRate <= 0) p->dstRate = 48000;
        p->ratio0 = p->srcRate / p->dstRate;
    }
    if (atomic_exchange(&p->targetChanged, 0)) {
        p->targetMs = atomic_load(&p->reqTargetMs);
        p->extraMs = 0;
        p->forceTrim = 1;
    }
    int64_t w = atomic_load_explicit(&p->w, memory_order_acquire);
    if (atomic_exchange(&p->resetRequested, 0)) consumer_reset(p, w);

    const float g = atomic_load_explicit(&p->gain, memory_order_relaxed);
    const double sr = p->srcRate;
    const double dt = (double)n / p->dstRate;
    p->clock += dt;
    double target = (p->targetMs + p->extraMs) / 1000.0;
    double level = level_sec(p, w);
    p->ema += (level - p->ema) * (1.0 - exp(-dt / EMA_TAU));

    /* 长时间稳定、且最差时刻也有余量，才慢慢收回附加缓冲 */
    double since = p->clock - (p->lastUnderrunAt > p->lastRelaxAt ? p->lastUnderrunAt : p->lastRelaxAt);
    if (since > 15) {
        if (p->extraMs > 0 && p->winMin > RELAX_MARGIN) p->extraMs = p->extraMs > 10 ? p->extraMs - 10 : 0;
        p->lastRelaxAt = p->clock;
        p->winMin = 1e9;
    }

    if (!p->playing) {
        if (level >= target && w - p->r >= 4) {
            if (level - target > 0.02) {         /* 积压太多：从目标水位开始（反正是淡入） */
                int64_t keep = (int64_t)llround(target * sr);
                if (keep < 4) keep = 4;
                p->r = w - keep;
                p->frac = 0;
                level = level_sec(p, w);
            }
            p->playing = 1;
            p->env = 0;
            p->ema = level;
            p->playStartAt = p->clock;
        } else {
            tail(L, R, 0, n, &p->lastL, &p->lastR);
            atomic_store_explicit(&p->rPub, p->r, memory_order_release);
            atomic_store(&p->state, 0);
            atomic_store(&p->sLevel, level * 1000);
            atomic_store(&p->sEma, p->ema * 1000);
            atomic_store(&p->sTarget, target * 1000);
            atomic_store(&p->sRms, 0.0f);
            return;
        }
    }

    double adj = (p->ema - target) * KP;
    if (adj > MAXADJ) adj = MAXADJ; else if (adj < -MAXADJ) adj = -MAXADJ;
    p->adj = adj;
    const double step = p->ratio0 * (1.0 + adj);

    int32_t start = 0;
    double excessEma = p->ema - target, excessNow = level - target;
    int trim = p->forceTrim && excessNow > 0.03;
    p->forceTrim = 0;
    if (trim || excessEma > (target > 0.12 ? target : 0.12) || excessNow > 0.4 + target) {
        int64_t newR = w - (int64_t)llround(target * sr);
        int32_t m = n < FADE ? n : FADE;
        if (newR > p->r + 2 * FADE && (double)(w - newR) >= m * step + 8) {
            start = crossfade_to(p, L, R, newR, step, m, g);
            p->ema = level_sec(p, w);
        }
    }

    /* 渲染剩下的部分；正常时留 FADE 帧做储备，一旦快断流，总能完整淡出 */
    const int32_t m = n - start;
    int64_t avail = w - p->r;
    int64_t maxIdx = avail - 3;                  /* floor(frac + i*step) 的上限 */
    int64_t canAll = 0;
    if (maxIdx >= 0) {
        canAll = (int64_t)floor(((double)maxIdx - p->frac) / step) + 1;
        if (canAll < 0) canAll = 0;
        if (canAll > m + FADE) canAll = m + FADE;
    }
    const int starving = canAll < m + FADE;
    const int32_t ok = starving ? (int32_t)(canAll < m ? canAll : m) : m;
    const int32_t fl = ok < FADE ? ok : FADE;
    const int32_t fadeStart = starving ? ok - fl : ok + 1;

    int64_t r = p->r;
    double f = p->frac;
    float env = p->env;
    const float envStep = 1.0f / FADE;
    double sum = 0;
    for (int32_t i = 0; i < ok; i++) {
        int64_t i0 = (r - 1) & MASK, i1 = r & MASK, i2 = (r + 1) & MASK, i3 = (r + 2) & MASK;
        float l = catmull(p->L[i0], p->L[i1], p->L[i2], p->L[i3], (float)f);
        float rr = catmull(p->R[i0], p->R[i1], p->R[i2], p->R[i3], (float)f);
        if (env < 1) { env += envStep; if (env > 1) env = 1; }
        float e = env;
        if (i >= fadeStart) e *= (float)(ok - i) / (float)fl;
        float lo = softclip(l * e * g), ro = softclip(rr * e * g);
        L[start + i] = lo;
        R[start + i] = ro;
        sum += (double)lo * lo + (double)ro * ro;
        f += step;
        if (f >= 1) { int64_t k = (int64_t)f; r += k; f -= (double)k; }
    }
    p->r = r;
    p->frac = f;
    p->env = env;

    int32_t z = start + ok;
    if (z < n) {
        float tl = z > 0 ? L[z - 1] : p->lastL, tr = z > 0 ? R[z - 1] : p->lastR;
        tail(L, R, z, n, &tl, &tr);
    }
    if (starving) {
        p->playing = 0;
        atomic_fetch_add_explicit(&p->underruns, 1, memory_order_relaxed);
        if (p->clock - p->playStartAt > 1.5) {
            p->extraMs += 25;
            if (p->extraMs > MAX_EXTRA_MS) p->extraMs = MAX_EXTRA_MS;
        }
        p->lastUnderrunAt = p->clock;
        p->winMin = 1e9;
    } else {
        double after = level_sec(p, w);
        if (after < p->winMin) p->winMin = after;
    }
    p->lastL = L[n - 1];
    p->lastR = R[n - 1];
    atomic_store_explicit(&p->rPub, p->r, memory_order_release);

    atomic_store(&p->state, p->playing);
    atomic_store(&p->sLevel, level_sec(p, w) * 1000);
    atomic_store(&p->sEma, p->ema * 1000);
    atomic_store(&p->sTarget, (p->targetMs + p->extraMs));
    atomic_store(&p->sAdj, p->adj);
    atomic_store(&p->sRms, ok > 0 ? (float)sqrt(sum / (2.0 * ok)) : 0.0f);
}

/* ------------------------------------------------------------------ SPSC ring */

struct SQRing {
    int32_t cap, mask;
    _Atomic int64_t w, r;
    float data[];
};

SQRing *sq_ring_create(int32_t capPow2) {
    int32_t cap = 1;
    while (cap < capPow2) cap <<= 1;
    SQRing *q = (SQRing *)calloc(1, sizeof(SQRing) + sizeof(float) * (size_t)cap);
    if (!q) return NULL;
    q->cap = cap;
    q->mask = cap - 1;
    atomic_init(&q->w, 0);
    atomic_init(&q->r, 0);
    return q;
}

void sq_ring_destroy(SQRing *q) { free(q); }

int32_t sq_ring_write(SQRing *q, const float *d, int32_t n) {
    int64_t w = atomic_load_explicit(&q->w, memory_order_relaxed);
    int64_t r = atomic_load_explicit(&q->r, memory_order_acquire);
    int64_t space = q->cap - (w - r);
    if (n > space) n = (int32_t)space;
    for (int32_t i = 0; i < n; i++) q->data[(w + i) & q->mask] = d[i];
    atomic_store_explicit(&q->w, w + n, memory_order_release);
    return n;
}

int32_t sq_ring_write_strided(SQRing *q, const float *d, int32_t n, int32_t stride) {
    if (stride <= 1) return sq_ring_write(q, d, n);
    int64_t w = atomic_load_explicit(&q->w, memory_order_relaxed);
    int64_t r = atomic_load_explicit(&q->r, memory_order_acquire);
    int64_t space = q->cap - (w - r);
    if (n > space) n = (int32_t)space;
    for (int32_t i = 0; i < n; i++) q->data[(w + i) & q->mask] = d[(int64_t)i * stride];
    atomic_store_explicit(&q->w, w + n, memory_order_release);
    return n;
}

int32_t sq_ring_read(SQRing *q, float *out, int32_t n) {
    int64_t r = atomic_load_explicit(&q->r, memory_order_relaxed);
    int64_t w = atomic_load_explicit(&q->w, memory_order_acquire);
    int64_t avail = w - r;
    if (n > avail) n = (int32_t)avail;
    for (int32_t i = 0; i < n; i++) out[i] = q->data[(r + i) & q->mask];
    atomic_store_explicit(&q->r, r + n, memory_order_release);
    return n;
}

int32_t sq_ring_available(SQRing *q) {
    return (int32_t)(atomic_load_explicit(&q->w, memory_order_acquire) - atomic_load_explicit(&q->r, memory_order_relaxed));
}

void sq_ring_discard(SQRing *q) {
    atomic_store_explicit(&q->r, atomic_load_explicit(&q->w, memory_order_acquire), memory_order_release);
}

float sq_float_to_int16(const float *in, int16_t *out, int32_t n, float gain) {
    float peak = 0;
    for (int32_t i = 0; i < n; i++) {
        float x = in[i] * gain;
        if (x > 1) x = 1; else if (x < -1) x = -1;
        float a = fabsf(x);
        if (a > peak) peak = a;
        out[i] = (int16_t)lrintf(x * 32767.0f);
    }
    return peak;
}
