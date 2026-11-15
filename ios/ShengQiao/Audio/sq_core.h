/* 声桥 · 音频核心（C，实时线程安全：渲染路径里没有锁、没有内存分配）
 *
 * SQPlayer：电脑 → 手机的播放缓冲。
 *   - 网络线程调用 sq_player_push()（唯一生产者）
 *   - 音频渲染线程调用 sq_player_render()（唯一消费者）
 *   - 自适应抖动缓冲 + 漂移补偿重采样（Catmull-Rom），断流淡出/恢复淡入，积压时交叉淡化跳到最新
 *
 * SQRing：手机麦克风 → 网络发送线程的单生产者单消费者环形缓冲。
 */
#ifndef SQ_CORE_H
#define SQ_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct SQPlayer SQPlayer;

typedef struct {
    int32_t state;          /* 0 = 缓冲中, 1 = 播放中 */
    double levelMs;         /* 当前缓冲水位 */
    double emaMs;           /* 平滑后的水位 */
    double targetMs;        /* 当前目标（含自适应附加） */
    double adjust;          /* 当前变速比例，例如 0.001 = 快 0.1% */
    int32_t underruns;      /* 断流次数 */
    int32_t skips;          /* 跳过积压次数 */
    int32_t overflows;      /* 缓冲满丢弃次数 */
    float rms;              /* 最近一次渲染的有效值（给电平表） */
} SQPlayerStats;

SQPlayer *sq_player_create(double srcRate, double dstRate);
void sq_player_destroy(SQPlayer *p);

/* 生产者：交错的 16 位 PCM */
void sq_player_push(SQPlayer *p, const int16_t *interleaved, int32_t frames, int32_t channels);

/* 消费者：输出 n 帧到 L、R（各自 n 个 float） */
void sq_player_render(SQPlayer *p, float *L, float *R, int32_t n);

/* 任意线程 */
void sq_player_set_target_ms(SQPlayer *p, double ms);
void sq_player_set_gain(SQPlayer *p, float gain);
void sq_player_set_rates(SQPlayer *p, double srcRate, double dstRate);
void sq_player_request_reset(SQPlayer *p);
void sq_player_get_stats(SQPlayer *p, SQPlayerStats *out);

typedef struct SQRing SQRing;

SQRing *sq_ring_create(int32_t capacityPow2);
void sq_ring_destroy(SQRing *r);
int32_t sq_ring_write(SQRing *r, const float *data, int32_t n);   /* 写不下的部分丢弃，返回实际写入数 */
/* 从交错多声道数据里取第一个声道写入（stride = 声道数） */
int32_t sq_ring_write_strided(SQRing *r, const float *data, int32_t n, int32_t stride);
int32_t sq_ring_read(SQRing *r, float *out, int32_t n);           /* 返回实际读出数 */
int32_t sq_ring_available(SQRing *r);
void sq_ring_discard(SQRing *r);                                  /* 消费者调用：丢掉全部积压 */

/* float → int16（带增益和削波），返回峰值 */
float sq_float_to_int16(const float *in, int16_t *out, int32_t n, float gain);

#ifdef __cplusplus
}
#endif

#endif
