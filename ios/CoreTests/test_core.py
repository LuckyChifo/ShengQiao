"""Tests for ios/ShengQiao/Audio/sq_core.c (compiled as a shared library, driven via ctypes).

Same scenarios as the PC mic tests: drift, Wi-Fi jitter, stalls, consumer stalls, target changes.
Output is checked for clicks with the sinusoid recursion residual.
Run: python3 ios/CoreTests/test_core.py   (builds the library with cc first)
"""
import ctypes
import os
import shlex
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'ShengQiao', 'Audio', 'sq_core.c')
LIB = os.path.join(tempfile.gettempdir(), 'sqcore_test.dll' if os.name == 'nt' else 'libsqcore_test.so')
compiler = shlex.split(os.environ.get('SQ_CC', 'cc'))
flags = ['-Wl,--export-all-symbols'] if os.name == 'nt' else ['-fPIC']
subprocess.check_call(compiler + ['-O2', '-std=c11', '-Wall', '-Wextra', '-Werror', '-shared']
                      + flags + [SRC, '-o', LIB, '-lm'])
lib = ctypes.CDLL(LIB)


class Stats(ctypes.Structure):
    _fields_ = [('state', ctypes.c_int32), ('levelMs', ctypes.c_double), ('emaMs', ctypes.c_double),
                ('targetMs', ctypes.c_double), ('adjust', ctypes.c_double), ('underruns', ctypes.c_int32),
                ('skips', ctypes.c_int32), ('overflows', ctypes.c_int32), ('rms', ctypes.c_float)]


lib.sq_player_create.restype = ctypes.c_void_p
lib.sq_player_create.argtypes = [ctypes.c_double, ctypes.c_double]
lib.sq_player_push.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32]
lib.sq_player_render.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32]
lib.sq_player_set_target_ms.argtypes = [ctypes.c_void_p, ctypes.c_double]
lib.sq_player_get_stats.argtypes = [ctypes.c_void_p, ctypes.POINTER(Stats)]
lib.sq_player_destroy.argtypes = [ctypes.c_void_p]
lib.sq_player_request_reset.argtypes = [ctypes.c_void_p]


def simulate(seconds=60, dst=48000, drift_ppm=0.0, spn=480, target=70, jitter=None, stalls=(),
             consumer_stalls=(), target_changes=(), seed=1, freq=440.0, amp=0.25, resets=()):
    rng = np.random.default_rng(seed)
    p = lib.sq_player_create(48000.0, float(dst))
    lib.sq_player_set_target_ms(p, float(target))
    CH = 480
    period = CH / (48000 * (1 + drift_ppm * 1e-6))
    arrivals, hold, t, k = [], 0.0, 0.0, 0
    while t < seconds:
        a = t + 0.004 + rng.random() * 0.002
        if jitter:
            pr, mx = jitter
            if t >= hold and rng.random() < pr:
                hold = t + rng.random() * mx / 1000
            if t < hold:
                a = max(a, hold + 0.002)
        for s0, d in stalls:
            if s0 <= t < s0 + d:
                a = max(a, s0 + d + 0.003)
        arrivals.append((a, k))
        t += period
        k += 1
    arrivals.sort()
    w = 2 * np.pi * freq / 48000

    def chunk(i):
        x = np.round(amp * 32767 * np.sin(w * np.arange(i * CH, (i + 1) * CH))).astype(np.int16)
        return np.ascontiguousarray(np.stack([x, x], axis=1))

    L = np.zeros(spn, np.float32)
    R = np.zeros(spn, np.float32)
    outs, levels = [], []
    ai = 0
    cb = spn / dst
    tt = cb
    tc = list(target_changes)
    rs = list(resets)
    st = Stats()
    while tt < seconds:
        while ai < len(arrivals) and arrivals[ai][0] <= tt:
            c = chunk(arrivals[ai][1])
            lib.sq_player_push(p, c.ctypes.data, CH, 2)
            ai += 1
        while tc and tt >= tc[0][0]:
            lib.sq_player_set_target_ms(p, float(tc.pop(0)[1]))
        while rs and tt >= rs[0]:
            rs.pop(0)
            lib.sq_player_request_reset(p)
        if not any(s0 <= tt < s0 + d for s0, d in consumer_stalls):
            lib.sq_player_render(p, L.ctypes.data, R.ctypes.data, spn)
            outs.append(L.copy())
            if tt > 5:
                lib.sq_player_get_stats(p, ctypes.byref(st))
                levels.append(st.levelMs)
        tt += cb
    lib.sq_player_get_stats(p, ctypes.byref(st))
    lib.sq_player_destroy(p)
    y = np.concatenate(outs).astype(np.float64)
    wo = 2 * np.pi * freq / dst
    c2 = 2 * np.cos(wo)
    res = np.abs(y[2:] - c2 * y[1:-1] + y[:-2])
    mask = (np.abs(y[1:-1]) + np.abs(y[:-2]) > 0.02) & (np.abs(y[2:]) > 0)
    return {'underruns': st.underruns, 'skips': st.skips, 'overflows': st.overflows,
            'glitches': int(np.sum((res > 0.02) & mask)), 'avgMs': round(float(np.mean(levels)), 1),
            'maxMs': round(float(np.max(levels)), 1), 'targetMs': st.targetMs, 'adjPct': round(st.adjust * 100, 4)}


CASES = [
    ('clean', dict(), dict(und=0, avg=80)),
    ('PC clock +300ppm', dict(drift_ppm=300, seconds=300), dict(und=0, avg=80)),
    ('PC clock -300ppm', dict(drift_ppm=-300, seconds=300), dict(und=0, avg=80)),
    ('iPhone engine at 44.1k, 512-frame callbacks', dict(dst=44100, spn=512, drift_ppm=100, seconds=200), dict(und=0, avg=80)),
    ('wifi jitter 60ms', dict(jitter=(0.03, 60), seconds=200), dict(avg=130)),
    ('wifi jitter 150ms', dict(jitter=(0.01, 150), seconds=200), dict(avg=230)),
    ('low preset 35ms + mild jitter', dict(target=35, jitter=(0.02, 30), seconds=200), dict(avg=90)),
    ('stall 600ms + burst', dict(stalls=[(30, 0.6)], seconds=90), dict(avg=90)),
    ('render thread stalls (app paused)', dict(consumer_stalls=[(20, 0.5), (40, 1.0)], seconds=60), dict(avg=90, skips=2)),
    ('target 400 -> 50 mid-play', dict(target=400, target_changes=[(30, 50)], seconds=60), dict(und=0, skips=1)),
    ('reset requested mid-play', dict(resets=[20, 35], seconds=50), dict(avg=90)),
    ('5ms IO buffer (240 frames)', dict(spn=240, seconds=120, jitter=(0.01, 40)), dict(avg=110)),
]

if __name__ == '__main__':
    fails = 0
    for name, kw, ex in CASES:
        r = simulate(**kw)
        ok = r['glitches'] == 0
        if 'und' in ex and r['underruns'] > ex['und']:
            ok = False
        if 'avg' in ex and r['avgMs'] > ex['avg']:
            ok = False
        if 'skips' in ex and r['skips'] < ex['skips']:
            ok = False
        fails += not ok
        print(('PASS ' if ok else 'FAIL ') + name.ljust(44) + str(r))
    print('%d failure(s)' % fails)
    sys.exit(1 if fails else 0)
