#define _POSIX_C_SOURCE 199309L
/* Concurrency stress test for sq_core.c: network thread pushes, audio thread renders,
 * UI thread pokes controls and reads stats; plus the mic SPSC ring.
 * Build & run with ThreadSanitizer:
 *   cc -std=c11 -O1 -g -fsanitize=thread stress_threads.c ../ShengQiao/Audio/sq_core.c -lpthread -lm && ./a.out */
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <math.h>
#include "../ShengQiao/Audio/sq_core.h"

static SQPlayer *P;
static SQRing *Q;
static atomic_int stop;
static long long ringWritten, ringRead;
static int ringErrors;

static void nap(long us) { struct timespec t = {0, us * 1000}; nanosleep(&t, NULL); }

static void *producer(void *a) {
    (void)a;
    int16_t buf[480 * 2];
    long n = 0;
    while (!atomic_load(&stop)) {
        for (int i = 0; i < 480; i++) { int16_t v = (int16_t)(8000 * sin(0.0576 * (n * 480 + i))); buf[2*i] = v; buf[2*i+1] = v; }
        sq_player_push(P, buf, 480, 2);
        n++;
        nap(rand() % 20000);
    }
    return NULL;
}

static void *consumer(void *a) {
    (void)a;
    float L[256], R[256];
    float mic[256];
    long seq = 0;
    while (!atomic_load(&stop)) {
        sq_player_render(P, L, R, 256);
        for (int i = 0; i < 256; i++) mic[i] = (float)((seq + i) % 1000);   /* recognisable ramp */
        int wr = sq_ring_write(Q, mic, 256);
        seq += wr;
        ringWritten += wr;
        nap(5000);
    }
    return NULL;
}

static void *sender(void *a) {
    (void)a;
    float out[480];
    long expect = 0;
    while (!atomic_load(&stop) || sq_ring_available(Q) > 0) {
        int n = sq_ring_read(Q, out, 480);
        for (int i = 0; i < n; i++) { if ((long)out[i] != (expect % 1000)) ringErrors++; expect++; }
        ringRead += n;
        nap(3000);
    }
    return NULL;
}

static void *ui(void *a) {
    (void)a;
    SQPlayerStats st;
    for (int i = 0; !atomic_load(&stop); i++) {
        sq_player_get_stats(P, &st);
        if (i % 7 == 0) sq_player_set_target_ms(P, 35 + (i % 3) * 50);
        if (i % 11 == 0) sq_player_set_gain(P, 0.5f + (i % 4) * 0.25f);
        if (i % 29 == 0) sq_player_request_reset(P);
        if (i % 31 == 0) sq_player_set_rates(P, 48000, (i % 2) ? 48000 : 44100);
        nap(7000);
    }
    return NULL;
}

int main(void) {
    P = sq_player_create(48000, 48000);
    Q = sq_ring_create(1 << 14);
    pthread_t t[4];
    pthread_create(&t[0], NULL, producer, NULL);
    pthread_create(&t[1], NULL, consumer, NULL);
    pthread_create(&t[2], NULL, sender, NULL);
    pthread_create(&t[3], NULL, ui, NULL);
    nap(900000); nap(900000); nap(900000);
    atomic_store(&stop, 1);
    for (int i = 0; i < 4; i++) pthread_join(t[i], NULL);
    SQPlayerStats st;
    sq_player_get_stats(P, &st);
    printf("player: underruns=%d skips=%d overflows=%d | ring: written=%lld read=%lld order-errors=%d\n",
           st.underruns, st.skips, st.overflows, ringWritten, ringRead, ringErrors);
    sq_player_destroy(P);
    sq_ring_destroy(Q);
    return (ringErrors == 0 && ringWritten == ringRead) ? 0 : 1;
}
