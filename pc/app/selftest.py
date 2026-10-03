"""声桥 · Windows 自检：检查声卡采集和 VB-CABLE 麦克风链路。

    start.bat selftest

1. 列出输出/输入设备，找 VB-CABLE
2. 麦克风链路：模拟手机发来 1 kHz 测试音 → 写进 CABLE Input → 从 CABLE Output 录回来，检查音高、断续和延迟
3. 采集链路：在默认扬声器上放 1.5 秒小声的 440 Hz 提示音 → 用环回采集录下来检查
4. 网络：本机局域网地址
结果同时写到 selftest.txt。
"""
import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np

RESULTS = []
LINES = []


def out(msg=''):
    print(msg, flush=True)
    LINES.append(msg)


def result(name, ok, detail=''):
    RESULTS.append((name, ok))
    out(('  [通过] ' if ok else '  [失败] ') + name + ('：' + detail if detail else ''))


def dominant_freq(x, rate):
    if len(x) < 1024:
        return 0.0, 0.0
    w = np.hanning(len(x))
    spec = np.abs(np.fft.rfft(x * w))
    k = int(np.argmax(spec[5:])) + 5
    return k * rate / len(x), float(np.sqrt(np.mean(x ** 2)))


def gaps(x, rate, freq):
    """检测断续：正弦递推残差 + 长段静音。"""
    c2 = 2 * np.cos(2 * np.pi * freq / rate)
    res = np.abs(x[2:] - c2 * x[1:-1] + x[:-2])
    amp = np.max(np.abs(x)) or 1
    clicks = int(np.sum(res > 0.15 * amp))
    env = np.convolve(np.abs(x), np.ones(240) / 240, mode='same')
    silent = int(np.sum(env < 0.1 * amp))
    return clicks, silent


def main():
    out('声桥自检  %s' % time.strftime('%Y-%m-%d %H:%M:%S'))
    out('Python %s  %s' % (sys.version.split()[0], sys.executable))
    out()
    try:
        import soundcard as sc
    except Exception as e:
        result('加载 soundcard 声卡库', False, repr(e))
        return finish()

    # ---------------------------------------------------------------- devices
    out('1. 音频设备')
    try:
        default = sc.default_speaker()
        out('  默认输出：%s' % default.name)
    except Exception as e:
        default = None
        out('  默认输出：取不到（%s）' % e)
    speakers = sc.all_speakers()
    mics = sc.all_microphones()
    for s in speakers:
        out('  输出：%s' % s.name)
    for m in mics:
        out('  输入：%s' % m.name)
    cable_in = next((s for s in speakers if 'cable input' in s.name.lower()), None)
    cable_out = next((m for m in mics if 'cable output' in m.name.lower()), None)
    result('找到 VB-CABLE（CABLE Input / CABLE Output）', bool(cable_in and cable_out),
           '' if cable_in and cable_out else '没装 VB-CABLE 的话，手机当麦克风用不了')
    if default is not None and cable_in is not None and default.id == cable_in.id:
        result('默认输出不是 CABLE Input', False,
               '现在 Windows 默认输出是 CABLE Input：电脑声音会混进麦克风，手机也听不到正常声音。请改回扬声器/耳机')
    out()

    # ---------------------------------------------------------------- mic path
    out('2. 麦克风链路（手机 → 声桥 → CABLE Input → CABLE Output）')
    if cable_in and cable_out:
        try:
            import mic as micmod
            sink = micmod.MicSink(micmod.SoundcardOutput(), 'auto')
            owner = object()
            sink.start(owner, 48000)
            rec_holder = {}

            def record():
                with cable_out.recorder(samplerate=48000, channels=1, blocksize=4800) as r:
                    rec_holder['t0'] = time.monotonic()
                    rec_holder['x'] = r.record(numframes=48000 * 4)[:, 0]
            th = threading.Thread(target=record)
            th.start()
            time.sleep(0.2)
            t_start = time.monotonic()
            click_at = None
            for i in range(300):                   # 3 秒，每 10 ms 一块，像手机一样按实时节奏发
                n = np.arange(i * 480, (i + 1) * 480)
                x = 0.3 * np.sin(2 * np.pi * 1000 / 48000 * n)
                if i == 150:
                    x[:24] = 0.95                      # 一个“咔”，用来量延迟
                    click_at = time.monotonic()
                sink.feed(owner, np.round(x * 32767).astype('<i2'))
                time.sleep(max(0, t_start + (i + 1) * 0.01 - time.monotonic()))
            th.join()
            sink.stop(owner)
            x = rec_holder.get('x', np.zeros(0))
            a0 = int(0.6 * 48000)
            body = x[a0: a0 + 2 * 48000]
            f, rms = dominant_freq(body, 48000)
            result('CABLE Output 录到了声音', rms > 0.02, 'RMS %.3f' % rms)
            result('音高正确（应为 1000 Hz）', abs(f - 1000) < 5, '%.1f Hz' % f)
            peak_idx = int(np.argmax(np.abs(x))) if len(x) else 0
            ci = peak_idx - a0                     # “咔”在 body 里的位置；前后各 50 ms 不参与断续检测
            parts = [body[:max(0, ci - 2400)], body[max(0, ci + 2400):]] if 0 <= ci < len(body) else [body]
            clicks = silent = 0
            for part in parts:
                if len(part) > 4800:
                    c_, s_ = gaps(part, 48000, 1000)
                    clicks += c_
                    silent += s_
            result('没有断续/爆音', clicks < 5 and silent < 480, '爆音点 %d，静音样本 %d' % (clicks, silent))
            if click_at is not None and 't0' in rec_holder:
                lat = (rec_holder['t0'] + peak_idx / 48000 - click_at) * 1000
                ok = 0 < lat < 400
                result('电脑端麦克风延迟', ok, '约 %d 毫秒（含 VB-CABLE 自身缓冲）' % lat)
        except Exception as e:
            result('麦克风链路', False, repr(e))
    else:
        out('  跳过（没有 VB-CABLE）')
    out()

    # ---------------------------------------------------------------- capture path
    out('3. 采集链路（电脑正在播放的声音 → 声桥）——会听到 1.5 秒小声的“嘟”')
    try:
        import capture
        src = capture.SoundcardSource(keepalive=True)
        got = []
        stop = threading.Event()
        opened = threading.Event()

        def run():
            try:
                src.run('default', lambda b: got.append(b.copy()), stop, lambda name: opened.set())
            except Exception as e:
                got.append(e)
        th = threading.Thread(target=run, daemon=True)
        th.start()
        opened.wait(5)
        time.sleep(0.3)
        n_before = sum(len(b) for b in got if isinstance(b, np.ndarray))
        spk = sc.default_speaker()
        t = np.arange(int(48000 * 1.5)) / 48000
        tone = (0.08 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
        tone[:480] *= np.linspace(0, 1, 480)
        tone[-480:] *= np.linspace(1, 0, 480)
        spk.play(np.stack([tone, tone], axis=1), samplerate=48000)
        time.sleep(0.4)
        stop.set()
        th.join(3)
        errs = [b for b in got if isinstance(b, Exception)]
        if errs:
            result('环回采集', False, repr(errs[0]))
        else:
            y = np.concatenate([b for b in got if isinstance(b, np.ndarray)])[:, 0] if got else np.zeros(0)
            secs = len(y) / 48000
            result('环回采集在出数据', secs > 1.0, '采到 %.1f 秒' % secs)
            seg = y[n_before:] if len(y) > n_before else y
            f, rms = dominant_freq(seg, 48000)
            result('采到了刚才放的提示音（440 Hz）', abs(f - 440) < 5 and rms > 0.002,
                   '%.1f Hz，RMS %.4f%s' % (f, rms, '；如果电脑静音了这一项会失败' if rms <= 0.002 else ''))
    except Exception as e:
        result('采集链路', False, repr(e))
    out()

    # ---------------------------------------------------------------- network
    out('4. 网络')
    try:
        import server
        ips = server.candidate_ips()
        result('找到局域网地址', bool(ips), '、'.join(ips[:4]) if ips else '请连上 Wi-Fi 或手机热点')
    except Exception as e:
        result('网络', False, repr(e))
    try:
        import zeroconf  # noqa: F401
        result('Bonjour（App 自动发现电脑）组件', True)
    except Exception as e:
        result('Bonjour（App 自动发现电脑）组件', False, repr(e))
    return finish()


def finish():
    out()
    bad = [n for n, ok in RESULTS if not ok]
    out('结果：%d 项通过，%d 项失败' % (len(RESULTS) - len(bad), len(bad)))
    if bad:
        out('失败的项目：' + '；'.join(bad))
    path = os.path.join(os.path.dirname(HERE), 'selftest.txt')
    try:
        with open(path, 'w', encoding='utf-8-sig') as f:
            f.write('\n'.join(LINES) + '\n')
        out('报告已保存：%s' % path)
    except Exception:
        pass
    return 1 if bad else 0


if __name__ == '__main__':
    code = main()
    sys.exit(code)
