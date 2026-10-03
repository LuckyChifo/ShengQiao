"""End-to-end denoise check: a fake phone sends speech + fan noise as mic audio over the network;
the server (started with --mic-out fake --debug) runs it through jitter buffer + denoiser; we fetch
what reached the (fake) CABLE Input and measure the pauses and the speech level for every mode.
Run: python tests/test_denoise_e2e.py HOST PORT KEY
"""
import base64, http.client, json, os, socket, struct, sys, threading, time
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_denoise as T  # noqa: E402

HOST, PORT, KEY = sys.argv[1], int(sys.argv[2]), sys.argv[3]
clean = T.speech()[:48000 * 7]
noisy = clean + T.noises(len(clean))['fan'] * 0.03


def ws():
    sk = socket.create_connection((HOST, PORT))
    sk.sendall(('GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\n'
                'Sec-WebSocket-Version: 13\r\nUser-Agent: ShengQiao/2.0\r\nX-SQ-Key: %s\r\n\r\n'
                % (base64.b64encode(os.urandom(16)).decode(), KEY)).encode())

    def rd():
        try:
            while sk.recv(65536):
                pass
        except OSError:
            pass
    threading.Thread(target=rd, daemon=True).start()
    return sk


def send(sk, payload, op):
    mask = os.urandom(4)
    n = len(payload)
    m = (mask * (n // 4 + 1))[:n]
    masked = (int.from_bytes(payload, 'little') ^ int.from_bytes(m, 'little')).to_bytes(n, 'little')
    head = struct.pack('!BB', 0x80 | op, 0x80 | n) if n < 126 else struct.pack('!BBH', 0x80 | op, 0x80 | 126, n)
    sk.sendall(head + mask + masked)


def wav():
    c = http.client.HTTPConnection('127.0.0.1', PORT)
    c.request('GET', '/api/debug/mic.wav')
    d = c.getresponse().read()
    return np.frombuffer(d[44:], '<i2').astype(float) / 32767


def locate(ref, rec):
    n = 1 << int(np.ceil(np.log2(len(ref) + len(rec))))
    c = np.fft.irfft(np.fft.rfft(rec, n) * np.conj(np.fft.rfft(ref, n)), n)
    return int(np.argmax(c[:len(rec)]))


env = np.convolve(np.abs(clean), np.ones(2400) / 2400, mode='same')
quiet = np.convolve((env < 1e-4).astype(float), np.ones(9601) / 9601, mode='same') > 0.999   # pause centres
quiet[:48000] = False
speechm = env > 0.05 * env.max()
db = lambda a: 10 * np.log10(np.mean(a ** 2) + 1e-20)
res = {}
for mode in ('off', 'light', 'rnnoise', 'strong'):
    sk = ws()
    time.sleep(0.3)
    send(sk, json.dumps({'type': 'config', 'listen': False, 'mic': True, 'micRate': 48000, 'denoise': mode}).encode(), 1)
    t0 = time.time()
    for i in range(len(noisy) // 480):
        pcm = np.round(np.clip(noisy[i * 480:(i + 1) * 480], -1, 1) * 32767).astype('<i2').tobytes()
        send(sk, struct.pack('<BBHI', 2, 0, 0, i) + pcm, 2)
        time.sleep(max(0, t0 + (i + 1) * 0.01 - time.time()))
    time.sleep(0.6)
    rec = wav()[-int(48000 * 8):]
    sk.close()
    time.sleep(0.6)
    lag = locate(clean, rec)
    y = rec[lag:lag + len(clean)]
    y = np.pad(y, (0, len(clean) - len(y)))
    res[mode] = (db(y[quiet]) - db(noisy[quiet]), db(y[speechm]) - db(clean[speechm]))
    print('%-8s network -> jitter buffer -> denoise -> CABLE: pauses %6.1f dB, speech level %+5.1f dB' % ((mode,) + res[mode]))
ok = (abs(res['off'][0]) < 1 and res['light'][0] < -6 and res['rnnoise'][0] < -20 and res['strong'][0] < -20
      and all(abs(v[1]) < 2 for v in res.values()))
print(('PASS' if ok else 'FAIL') + ' end-to-end denoise')
sys.exit(0 if ok else 1)
