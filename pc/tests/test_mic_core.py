"""Simulation tests for mic.MicJitter: drift, Wi-Fi jitter, stalls, sample-rate conversion.

A pure sine goes in; the output is checked with the sinusoid recursion
x[n] - 2cos(w)x[n-1] + x[n-2] = 0, so any discontinuity (click) shows up as a residual.
Run: python tests/test_mic_core.py
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
from mic import MicJitter  # noqa: E402


def simulate(seconds=60, src_rate=48000, drift_ppm=0.0, jitter=None, stalls=(), target_ms=40,
             block=480, seed=1, freq=440.0, amp=0.3, frame_ms=10, consumer_stalls=()):
    rng = np.random.default_rng(seed)
    jb = MicJitter(src_rate=src_rate, target_ms=target_ms)
    chunk = int(src_rate * frame_ms / 1000)
    period = chunk / (src_rate * (1 + drift_ppm * 1e-6))
    arrivals = []
    hold = 0.0
    t, k = 0.0, 0
    while t < seconds:
        a = t + 0.004 + rng.random() * 0.002
        if jitter:
            p, max_ms = jitter
            if t >= hold and rng.random() < p:
                hold = t + rng.random() * max_ms / 1000
            if t < hold:
                a = max(a, hold + 0.002)
        for s0, d in stalls:
            if s0 <= t < s0 + d:
                a = max(a, s0 + d + 0.003)
        arrivals.append((a, k))
        t += period
        k += 1
    arrivals.sort()
    w = 2 * np.pi * freq / src_rate

    def gen(idx):
        n = np.arange(idx * chunk, (idx + 1) * chunk)
        return np.round(amp * 32767 * np.sin(w * n)).astype(np.int16)

    out_period = block / 48000
    wo = 2 * np.pi * freq / 48000
    c2 = 2 * np.cos(wo)
    ai = 0
    outs = []
    levels = []
    tt = out_period
    while tt < seconds:
        while ai < len(arrivals) and arrivals[ai][0] <= tt:
            jb.push(gen(arrivals[ai][1]))
            ai += 1
        if not any(s0 <= tt < s0 + d for s0, d in consumer_stalls):
            outs.append(jb.pull(block))
        if tt > 5:
            levels.append(jb.level())
        tt += out_period
    y = np.concatenate(outs).astype(np.float64)
    res = np.abs(y[2:] - c2 * y[1:-1] + y[:-2])
    mask = (np.abs(y[1:-1]) + np.abs(y[:-2]) > 0.02) & (np.abs(y[2:]) > 0)
    glitches = int(np.sum((res > 0.02) & mask))
    st = jb.stats()
    return {'underruns': st['underruns'], 'skips': st['skips'], 'glitches': glitches,
            'avgMs': round(1000 * float(np.mean(levels)), 1), 'maxMs': round(1000 * float(np.max(levels)), 1),
            'targetMs': st['targetMs']}


CASES = [
    ('clean, 48k', dict(), dict(underruns=0, glitches=0, max_avg=60)),
    ('phone clock +300ppm', dict(drift_ppm=300, seconds=300), dict(underruns=0, glitches=0, max_avg=60)),
    ('phone clock -300ppm', dict(drift_ppm=-300, seconds=300), dict(underruns=0, glitches=0, max_avg=60)),
    ('phone at 44.1k, 10ms frames', dict(src_rate=44100, seconds=120), dict(underruns=0, glitches=0, max_avg=60)),
    ('wifi jitter 60ms', dict(jitter=(0.03, 60), seconds=180), dict(glitches=0, max_avg=120)),
    ('wifi jitter 150ms', dict(jitter=(0.01, 150), seconds=180), dict(glitches=0, max_avg=250)),
    ('stall 700ms + burst', dict(stalls=[(20, 0.7)], seconds=60), dict(glitches=0, max_avg=80)),
    ('PC audio thread stalls 500ms', dict(consumer_stalls=[(20, 0.5), (40, 0.8)], seconds=60),
     dict(glitches=0, max_avg=80, min_skips=2)),
]

if __name__ == '__main__':
    fails = 0
    for name, kw, expect in CASES:
        r = simulate(**kw)
        ok = True
        if 'underruns' in expect and r['underruns'] > expect['underruns']:
            ok = False
        if r['glitches'] > expect['glitches']:
            ok = False
        if r['avgMs'] > expect['max_avg']:
            ok = False
        if r['skips'] < expect.get('min_skips', 0):
            ok = False
        fails += not ok
        print(('PASS ' if ok else 'FAIL ') + name.ljust(30) + str(r))
    # the click detector must catch a hard discontinuity
    jb = MicJitter()
    y = np.sin(2 * np.pi * 440 / 48000 * np.arange(4800)) * 0.3
    y[2400:] = np.sin(2 * np.pi * 440 / 48000 * (np.arange(2400) + 9999)) * 0.3
    c2 = 2 * np.cos(2 * np.pi * 440 / 48000)
    res = np.abs(y[2:] - c2 * y[1:-1] + y[:-2])
    det = int(np.sum(res > 0.02))
    print(('PASS ' if det > 0 else 'FAIL ') + 'detector sanity (hard jump flagged)'.ljust(30) + str(det))
    fails += det == 0
    print('%d failure(s)' % fails)
    sys.exit(1 if fails else 0)
