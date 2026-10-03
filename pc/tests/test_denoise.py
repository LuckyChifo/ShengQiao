"""Quality tests for app/denoise.py on synthetic speech + noise.

Speech: espeak-ng TTS (Chinese + English) if available, else a voiced-speech-like synthetic signal.
For each noise type and mode, reports:
  noise_db   how much quieter the pauses get (negative = quieter)
  speech_db  how much the speech parts change in level (close to 0 = kept)
  snr_gain   segmental SNR improvement against the clean speech (dB)
  ms/frame   CPU time per 10 ms frame
Run: python tests/test_denoise.py
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time
import wave

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
import denoise  # noqa: E402

SR = 48000
rng = np.random.default_rng(7)


def load_wav(path):
    with wave.open(path) as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float64) / 32768
        if w.getnchannels() > 1:
            x = x.reshape(-1, w.getnchannels())[:, 0]
    t_new = np.arange(int(len(x) * SR / sr)) / SR
    return np.interp(t_new, np.arange(len(x)) / sr, x)


def speech():
    parts = []
    tts = shutil.which('espeak-ng')
    if tts:
        d = tempfile.mkdtemp()
        for i, (v, text) in enumerate([('cmn', '今天我们来测试一下声桥的降噪效果，看看风扇和键盘的噪声能不能去掉。'),
                                       ('en', 'The quick brown fox jumps over the lazy dog. Testing noise reduction.')]):
            p = os.path.join(d, '%d.wav' % i)
            subprocess.run([tts, '-v', v, '-w', p, text], check=True, capture_output=True)
            parts.append(load_wav(p))
    else:   # fallback: harmonic bursts with moving formants
        for k in range(8):
            n = int(SR * 0.6)
            t = np.arange(n) / SR
            f0 = 120 + 40 * np.sin(2 * np.pi * 1.5 * t + k)
            ph = 2 * np.pi * np.cumsum(f0) / SR
            v = sum(np.sin(h * ph) / h for h in range(1, 30))
            parts.append(v * np.hanning(n) * 0.3)
    out = [np.zeros(SR * 2)]
    for p in parts:
        out += [p / (np.max(np.abs(p)) + 1e-9) * 0.5, np.zeros(SR)]
    return np.concatenate(out)


def noises(n):
    white = rng.standard_normal(n)
    pink = np.cumsum(rng.standard_normal(n))
    pink -= np.convolve(pink, np.ones(4800) / 4800, mode='same')        # roughly 1/f, no drift
    t = np.arange(n) / SR
    fan = sum(np.sin(2 * np.pi * 120 * h * t + h) / h for h in range(1, 8)) + 0.6 * pink / np.std(pink)
    keys = np.zeros(n)
    for pos in rng.integers(0, n - 2000, size=int(n / SR * 6)):        # ~6 key presses per second
        click = rng.standard_normal(600) * np.exp(-np.arange(600) / 80)
        keys[pos:pos + 600] += click
    out = {}
    for name, x in (('white', white), ('fan', fan), ('keyboard', keys)):
        out[name] = x / np.sqrt(np.mean(x ** 2))
    return out


def run(mode, x):
    d = denoise.Denoiser(mode)
    y = np.zeros_like(x, dtype=np.float32)
    t0 = time.perf_counter()
    nf = len(x) // denoise.FRAME
    for i in range(nf):
        s = slice(i * denoise.FRAME, (i + 1) * denoise.FRAME)
        y[s] = d.process(x[s].astype(np.float32))
    ms = (time.perf_counter() - t0) / nf * 1000
    return y[:nf * denoise.FRAME].astype(np.float64), ms


def align(clean, y):
    """denoisers delay by up to a couple of frames: find the lag by cross-correlation."""
    best, lag = -1, 0
    for L in range(0, 2 * denoise.FRAME + 1, 16):
        c = np.dot(clean[:len(y) - L], y[L:])
        if c > best:
            best, lag = c, L
    for L in range(max(0, lag - 16), lag + 17):
        c = np.dot(clean[:len(y) - L], y[L:])
        if c > best:
            best, lag = c, L
    return y[lag:], lag


def metrics(clean, noisy, y):
    y, lag = align(clean, y)
    n = len(y)
    clean, noisy = clean[:n], noisy[:n]
    env = np.convolve(np.abs(clean), np.ones(2400) / 2400, mode='same')
    speech_mask = env > 0.05 * np.max(env)
    quiet_mask = env < 1e-4
    quiet_mask[:SR // 2] = False                      # 前 0.5 秒是启动阶段，单独看
    db = lambda a: 10 * np.log10(np.mean(a ** 2) + 1e-20)
    noise_db = db(y[quiet_mask]) - db(noisy[quiet_mask])
    speech_db = db(y[speech_mask]) - db(clean[speech_mask])
    # segmental SNR with best scalar gain
    def snr(z):
        a = np.dot(z, clean) / (np.dot(clean, clean) + 1e-20)
        return 10 * np.log10(np.sum((a * clean) ** 2) / (np.sum((z - a * clean) ** 2) + 1e-20))
    return noise_db, speech_db, snr(y) - snr(noisy), lag


if __name__ == '__main__':
    clean = speech()
    modes = denoise.available_modes()
    print('available modes:', modes)
    fails = 0
    expect = {  # minimum quality bar per mode (averaged over noise types)
        'light': dict(noise=-8, speech=-2, snr=4),
        'rnnoise': dict(noise=-20, speech=-2, snr=6),
        'strong': dict(noise=-26, speech=-2, snr=6),
    }
    summary = {m: [] for m in modes if m != 'off'}
    for nname, nz in noises(len(clean)).items():
        noisy = clean + nz * 0.03                   # noise at about -30 dBFS RMS
        for mode in modes:
            if mode == 'off':
                continue
            y, ms = run(mode, noisy)
            nd, sd, sg, lag = metrics(clean, noisy, y)
            if not (mode == 'light' and nname == 'keyboard'):      # 频谱减法本来就不针对突发噪声
                summary[mode].append((nd, sd, sg))
            ok = np.all(np.isfinite(y)) and np.max(np.abs(y)) < 2.0 and ms < 2.0
            fails += not ok
            print('%-9s %-8s noise %6.1f dB   speech %+5.1f dB   SNR gain %+5.1f dB   delay %3d smp   %.3f ms/frame%s'
                  % (nname, mode, nd, sd, sg, lag, ms, '' if ok else '   <-- FAIL'))
    print()
    for mode, rows in summary.items():
        nd, sd, sg = np.mean(rows, axis=0)
        e = expect[mode]
        ok = nd <= e['noise'] and sd >= e['speech'] and sg >= e['snr']
        fails += not ok
        print(('PASS ' if ok else 'FAIL ') + '%-8s avg noise %6.1f dB, speech %+5.1f dB, SNR gain %+5.1f dB' % (mode, nd, sd, sg))
    # regression: digital silence first (mic just opened / jitter buffer filling) must not poison the noise estimate
    for mode in [m for m in modes if m != 'off']:
        x = np.concatenate([np.zeros(SR // 2), 0.03 * rng.standard_normal(SR * 3)]).astype(np.float32)
        d = denoise.Denoiser(mode)
        y = np.concatenate([d.process(x[i * 480:(i + 1) * 480]) for i in range(len(x) // 480)])
        att = 20 * np.log10(np.std(y[-SR:]) / np.std(x[-SR:]))
        ok = att < -6
        fails += not ok
        print(('PASS ' if ok else 'FAIL ') + '%-8s after leading silence: noise %.1f dB' % (mode, att))
    # switching modes on the fly must not crash or blow up
    d = denoise.Denoiser('off')
    x = (clean[:SR * 3] + 0.03 * rng.standard_normal(SR * 3)).astype(np.float32)
    out = []
    for i in range(len(x) // 480):
        if i % 50 == 0:
            d.set_mode(modes[(i // 50) % len(modes)])
        out.append(d.process(x[i * 480:(i + 1) * 480]))
    y = np.concatenate(out)
    ok = np.all(np.isfinite(y)) and np.max(np.abs(y)) < 2.0
    fails += not ok
    print(('PASS ' if ok else 'FAIL ') + 'switching modes every 0.5 s')
    print('%d failure(s)' % fails)
    sys.exit(1 if fails else 0)
