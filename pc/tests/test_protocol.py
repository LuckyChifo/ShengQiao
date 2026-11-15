"""Protocol tests for 声桥 server. Usage: python3 test_server.py HOST PORT KEY"""
import base64, http.client, json, os, socket, struct, subprocess, sys, time

HOST, PORT, KEY = sys.argv[1], int(sys.argv[2]), sys.argv[3]
SP = os.path.dirname(os.path.abspath(__file__))
fails = 0


def check(name, cond, extra=''):
    global fails
    print(('PASS ' if cond else 'FAIL ') + name + (('  ' + str(extra)) if extra else ''))
    if not cond:
        fails += 1


def req(host, method, path, body=None, headers=None):
    c = http.client.HTTPConnection(host, PORT, timeout=5)
    c.request(method, path, body=body, headers=headers or {})
    r = c.getresponse()
    data = r.read()
    c.close()
    return r.status, dict(r.getheaders()), data


# ---- HTTP routing / access control
s, h, d = req(HOST, 'GET', '/')
check('phone page served on LAN address', s == 200 and b'player-core.js' in d and b'player.js' in d and b'player.css' in d)
s, h, d = req(HOST, 'GET', '/player-core.js')
check('player-core.js served', s == 200 and b'PlayerCore' in d)
s, h, d = req(HOST, 'GET', '/player.js')
check('player.js served without legacy lock screen mode', s == 200 and b'keepawake' not in d and b'stream.mp3' not in d)
s, h, d = req(HOST, 'GET', '/player.css')
check('player.css served', s == 200 and b'--primary' in d)
s, h, d = req(HOST, 'GET', '/pc')
check('PC console blocked from LAN', s == 403, s)
s, h, d = req('127.0.0.1', 'GET', '/pc')
check('PC console served on localhost', s == 200)
s, h, d = req(HOST, 'GET', '/api/status')
check('status API blocked from LAN', s == 403, s)
s, h, d = req(HOST, 'GET', '/api/check')
check('check without key -> 403', s == 403, s)
s, h, d = req(HOST, 'GET', '/api/check?k=wrong1')
check('check wrong key -> 403', s == 403, s)
s, h, d = req(HOST, 'GET', '/api/check?k=' + KEY.upper())
check('check right key (case-insensitive) -> ok', s == 200 and json.loads(d)['ok'], d[:80])
s, h, d = req(HOST, 'GET', '/icon-512.png', headers={'Range': 'bytes=0-1'})
check('static Range bytes=0-1 -> 206', s == 206 and len(d) == 2 and h.get('Content-Range', '').startswith('bytes 0-1/'), (s, h.get('Content-Range')))
size = int(h['Content-Range'].split('/')[1])
s, h, d = req(HOST, 'GET', '/icon-512.png', headers={'Range': 'bytes=100-'})
check('static open-ended range', s == 206 and len(d) == size - 100)
s, h, d = req(HOST, 'GET', '/icon-512.png', headers={'Range': 'bytes=%d-' % (size + 5)})
check('unsatisfiable range -> 416', s == 416, s)
s, h, d = req(HOST, 'GET', '/keepawake.mp4')
check('legacy keep-awake video removed', s == 404, s)
s, h, d = req(HOST, 'GET', '/../server.py')
check('path traversal not served', s == 404, s)
s, h, d = req(HOST, 'GET', '/icon-180.png')
check('icon served', s == 200 and d[:4] == b'\x89PNG')


# ---- WebSocket
def ws_connect(key, path=None, header_key=False, ua='Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)'):
    sk = socket.create_connection((HOST, PORT), timeout=5)
    wkey = base64.b64encode(os.urandom(16)).decode()
    if path is None:
        path = '/ws?k=%s' % key
    extra = ('X-SQ-Key: %s\r\n' % key) if header_key else ''
    sk.sendall(('GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nUser-Agent: %s\r\n%s\r\n'
                % (path, HOST, PORT, wkey, ua, extra)).encode())
    buf = b''
    while b'\r\n\r\n' not in buf:
        buf += sk.recv(4096)
    head, rest = buf.split(b'\r\n\r\n', 1)
    return sk, head, rest


class Reader:
    def __init__(self, sk, rest):
        self.sk, self.buf = sk, rest

    def need(self, n):
        while len(self.buf) < n:
            chunk = self.sk.recv(65536)
            if not chunk:
                raise EOFError
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def frame(self):
        b0, b1 = self.need(2)
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack('!H', self.need(2))[0]
        elif n == 127:
            n = struct.unpack('!Q', self.need(8))[0]
        return b0 & 0x0F, self.need(n)


def ws_send(sk, payload, opcode):
    mask = os.urandom(4)
    m = (mask * (len(payload) // 4 + 1))[:len(payload)]
    masked = (int.from_bytes(payload, 'little') ^ int.from_bytes(m, 'little')).to_bytes(len(payload), 'little') if payload else b''
    n = len(payload)
    if n < 126:
        head = struct.pack('!BB', 0x80 | opcode, 0x80 | n)
    elif n < 65536:
        head = struct.pack('!BBH', 0x80 | opcode, 0x80 | 126, n)
    else:
        head = struct.pack('!BBQ', 0x80 | opcode, 0x80 | 127, n)
    sk.sendall(head + mask + masked)


def ws_send_text(sk, text):
    payload = text.encode()
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    n = len(payload)
    head = struct.pack('!BB', 0x81, 0x80 | n) if n < 126 else struct.pack('!BBH', 0x81, 0x80 | 126, n)
    sk.sendall(head + mask + masked)


sk, head, rest = ws_connect('bogus')
r = Reader(sk, rest)
op, payload = r.frame()
check('ws wrong key -> close 4001', op == 0x8 and struct.unpack('!H', payload[:2])[0] == 4001, (op, payload[:12]))
sk.close()

sk, head, rest = ws_connect(KEY)
check('ws upgrade 101', head.startswith(b'HTTP/1.1 101'))
r = Reader(sk, rest)
op, payload = r.frame()
hello = json.loads(payload)
check('hello message', op == 1 and hello['type'] == 'hello' and hello['rate'] == 48000 and hello['channels'] == 2, hello)
t0 = time.time()
frames, seqs, peak, nonsilent = 0, [], 0, 0
ws_send_text(sk, json.dumps({'type': 'ping', 't': 12345.5}))
got_pong = False
while time.time() - t0 < 4.0:
    op, payload = r.frame()
    if op == 1:
        m = json.loads(payload)
        if m.get('type') == 'pong' and m.get('t') == 12345.5:
            got_pong = True
        continue
    ver, flags, _, seq = struct.unpack('<BBHI', payload[:8])
    pcm = payload[8:]
    assert ver == 1 and len(pcm) == 480 * 4, (ver, len(pcm))
    seqs.append(seq)
    frames += 1
    import array
    a = array.array('h', pcm)
    pk = max(abs(x) for x in a)
    peak = max(peak, pk)
    if not flags & 1:
        nonsilent += 1
el = time.time() - t0
rate = frames / el
check('audio frames ~100/s', 90 <= rate <= 110, '%.1f frames/s' % rate)
check('sequence numbers contiguous', all(b - a == 1 for a, b in zip(seqs, seqs[1:])))
check('frames carry audio (tone)', peak > 3000 and nonsilent > 50, (peak, nonsilent))
check('ping -> pong', got_pong)
ws_send_text(sk, json.dumps({'type': 'stats', 'lat': 123, 'buf': 70, 'tgt': 70, 'und': 2, 'rtt': 8}))
time.sleep(0.3)
s, h, d = req('127.0.0.1', 'GET', '/api/status')
st = json.loads(d)
ls = [c for c in st['listeners'] if c['mode'] == 'live']
check('status lists live listener with stats', len(ls) == 1 and ls[0]['latency'] == 123 and ls[0]['device'] == 'iPhone', ls)
check('status urls include LAN address', any(HOST in u and ('k=' + KEY) in u for u in st['urls']), st['urls'])

# ---- chime + device APIs
local_json = {'Content-Type': 'application/json', 'Origin': f'http://127.0.0.1:{PORT}'}
s, h, d = req('127.0.0.1', 'POST', '/api/chime', body=b'{}', headers=local_json)
check('chime API', s == 200)
s, h, d = req('127.0.0.1', 'GET', '/api/devices')
check('devices API', s == 200 and len(json.loads(d)['devices']) >= 1, d[:120])
s, h, d = req('127.0.0.1', 'POST', '/api/device', body=json.dumps({'id': 'default'}).encode(),
              headers=local_json)
check('set device API', s == 200)

# ---- MP3 stream (while the WS client is still connected: two listeners at once)
c = http.client.HTTPConnection(HOST, PORT, timeout=5)
c.request('GET', '/stream.mp3?k=' + KEY, headers={'User-Agent': 'AppleCoreMedia/1.0 (iPhone)'})
resp = c.getresponse()
check('mp3 stream 200 audio/mpeg', resp.status == 200 and resp.getheader('Content-Type') == 'audio/mpeg')
mp3 = b''
t0 = time.time()
while time.time() - t0 < 4.0:
    mp3 += resp.read1(65536)
resp.close()
c.close()
import tempfile
path = os.path.join(tempfile.gettempdir(), 'shengqiao_stream_test.mp3')
open(path, 'wb').write(mp3)
kbps = len(mp3) * 8 / 4.0 / 1000
check('mp3 bitrate ~256 kbps', 200 < kbps < 300, '%.0f kbps' % kbps)
probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name,sample_rate,channels',
                        '-of', 'compact', path], capture_output=True, text=True).stdout.strip()
check('ffprobe: mp3 48 kHz stereo', 'codec_name=mp3' in probe and 'sample_rate=48000' in probe and 'channels=2' in probe, probe)
vol = subprocess.run(['ffmpeg', '-hide_banner', '-i', path, '-af', 'volumedetect', '-f', 'null', '-'],
                     capture_output=True, text=True).stderr
mv = [l for l in vol.splitlines() if 'max_volume' in l]
check('mp3 decodes with audible content', mv and float(mv[0].split(':')[1].split()[0]) > -20, mv)
s, h, d = req(HOST, 'GET', '/stream.mp3?k=nope')
check('mp3 wrong key -> 403', s == 403)


# =================================================================== protocol v2 (App)
import numpy as np
import queue
import threading
APP_UA = 'ShengQiao/2.0 (iPhone; iOS 18.0)'


def start_reader(r):
    q = queue.Queue()

    def run():
        try:
            while True:
                q.put(r.frame())
        except Exception:
            q.put((None, None))
    threading.Thread(target=run, daemon=True).start()
    return q


def drain(q, seconds):
    out = []
    t_end = time.time() + seconds
    while time.time() < t_end:
        try:
            out.append(q.get(timeout=max(0.01, t_end - time.time())))
        except queue.Empty:
            break
    return out
sk.close()
time.sleep(0.3)

# key via header, websocket on "/" (what Network.framework sends for a Bonjour endpoint)
ska, head, rest = ws_connect(KEY, path='/', header_key=True, ua=APP_UA)
check('v2: ws on / with X-SQ-Key header', head.startswith(b'HTTP/1.1 101'))
ska.settimeout(None)
ra = Reader(ska, rest)
op, payload = ra.frame()
hello = json.loads(payload)
qa = start_reader(ra)
check('v2: hello has id + mic feature', 'mic' in hello.get('features', []) and len(hello.get('id', '')) >= 8, hello)

# listen off -> no audio frames, but status messages
ws_send_text(ska, json.dumps({'type': 'config', 'listen': False, 'mic': False,
                              'deviceName': '小明的 iPhone'}))
drain(qa, 0.3)
audio = 0; statuses = []
for op, payload in drain(qa, 2.5):
    if op == 2:
        audio += 1
    elif op == 1:
        statuses.append(json.loads(payload))
check('v2: listen=false -> no audio frames', audio == 0, audio)
check('v2: periodic status messages to app', any(m.get('type') == 'status' for m in statuses), statuses[-1:] if statuses else None)

# mic on at 44.1 kHz; send a 1 kHz tone for 4 s in 10 ms frames, paced in real time
ws_send_text(ska, json.dumps({'type': 'config', 'listen': True, 'mic': True, 'micRate': 44100, 'denoise': 'off'}))  # 纯音会被降噪当成噪声
SR, F = 44100, 1000.0
w = 2 * np.pi * F / SR
seq = 0
t_start = time.time()
for i in range(400):
    n = np.arange(i * 441, (i + 1) * 441)
    pcm = np.round(0.4 * 32767 * np.sin(w * n)).astype('<i2').tobytes()
    ws_send(ska, struct.pack('<BBHI', 2, 0, 0, seq) + pcm, 0x2)
    seq += 1
    target = t_start + (i + 1) * 0.01
    delay = target - time.time()
    if delay > 0:
        time.sleep(delay)
time.sleep(0.4)
s_, h_, d_ = req('127.0.0.1', 'GET', '/api/status')
st = json.loads(d_)
check('v2: PC status shows mic running', st['mic']['active'] and st['mic']['state'] == 'running', st['mic'])
check('v2: PC status uses reported device name',
      any(c['device'] == '小明的 iPhone' for c in st['listeners']), st['listeners'])
lst = [c for c in st['listeners'] if c['device'] == '小明的 iPhone']
check('v2: listener row shows listen+mic roles', any(c['mic'] and c['listen'] for c in lst), lst)
s_, h_, wav = req('127.0.0.1', 'GET', '/api/debug/mic.wav')
x = np.frombuffer(wav[44:], dtype='<i2').astype(np.float64) / 32767
nz = np.nonzero(np.abs(x) > 0.05)[0]
check('v2: mic audio reached the (fake) CABLE Input', len(nz) > 48000 * 3, len(nz))
if len(nz) > 48000:
    seg = x[nz[0] + 4800: nz[-1] - 4800]
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    f_peak = np.argmax(spec) * 48000 / len(seg)
    check('v2: resampled 44.1k -> 48k, tone still 1 kHz', abs(f_peak - 1000) < 3, round(f_peak, 2))
    c2 = 2 * np.cos(2 * np.pi * f_peak / 48000)
    res = np.abs(seg[2:] - c2 * seg[1:-1] + seg[:-2])
    clicks = int(np.sum(res > 0.03))
    check('v2: mic stream has no clicks/dropouts', clicks == 0, clicks)
    rms = np.sqrt(np.mean(seg ** 2))
    check('v2: mic level preserved (0.4 amplitude)', abs(rms * np.sqrt(2) - 0.4) < 0.02, round(rms * np.sqrt(2), 3))

# denoise mode: set from the App, reported back in status and on the PC page; PC page can change it too
ws_send_text(ska, json.dumps({'type': 'config', 'denoise': 'strong'}))
time.sleep(1.3)
st_msgs = [json.loads(p) for op, p in drain(qa, 1.2) if op == 1]
st_last = [m for m in st_msgs if m.get('type') == 'status'][-1:]
check('denoise: App sets strong, status reports it', st_last and st_last[0]['mic']['denoise'] == 'strong'
      and 'rnnoise' in st_last[0]['mic']['denoiseModes'], st_last)
s_, h_, d_ = req('127.0.0.1', 'POST', '/api/denoise', body=json.dumps({'mode': 'light'}).encode(), headers=local_json)
time.sleep(1.2)
st_msgs = [json.loads(p) for op, p in drain(qa, 1.2) if op == 1]
st_last = [m for m in st_msgs if m.get('type') == 'status'][-1:]
check('denoise: PC page sets light, App is told', st_last and st_last[0]['mic']['denoise'] == 'light', st_last)
ws_send_text(ska, json.dumps({'type': 'config', 'denoise': 'bogus-mode'}))
time.sleep(0.3)
s_, h_, d_ = req('127.0.0.1', 'GET', '/api/status')
check('denoise: unknown mode ignored', json.loads(d_)['mic']['denoise'] == 'light')

# second phone takes over the mic -> first one is told it is busy
skb, head, rest = ws_connect(KEY, header_key=True, ua=APP_UA)
skb.settimeout(None)
rb = Reader(skb, rest)
rb.frame()
qb = start_reader(rb)
ws_send_text(skb, json.dumps({'type': 'config', 'listen': False, 'mic': True, 'micRate': 48000}))
time.sleep(1.3)
last_a = None
for op, payload in drain(qa, 1.5):
    if op == 1:
        m = json.loads(payload)
        if m.get('type') == 'status':
            last_a = m
check('v2: mic handover -> first phone sees busy', last_a and last_a['mic']['busy'] and not last_a['mic']['mine'], last_a)
skb.close()
time.sleep(0.5)
s_, h_, d_ = req('127.0.0.1', 'GET', '/api/status')
check('v2: mic released when its phone disconnects', not json.loads(d_)['mic']['active'], json.loads(d_)['mic'])
ska.close()
time.sleep(0.5)

skd, head, rest = ws_connect(KEY, header_key=True, ua='ShengQiao/2.0 (Android)')
check('v2: Android app websocket connects', head.startswith(b'HTTP/1.1 101'))
Reader(skd, rest).frame()
time.sleep(0.3)
s_, h_, d_ = req('127.0.0.1', 'GET', '/api/status')
listeners = json.loads(d_)['listeners']
check('v2: Android app is labelled Android in status API',
      any(c['device'] == 'Android（App）' for c in listeners), listeners)
skd.close()
time.sleep(0.3)

s, h, d = req('127.0.0.1', 'GET', '/api/status')
s2, h2, d2 = req('127.0.0.1', 'GET', '/api/mic-devices')
md = json.loads(d2)
check('mic devices API finds CABLE', md.get('cable') is not None, md)

# Bonjour advertisement (only when the server runs with it enabled)
if os.environ.get('SQ_TEST_BONJOUR'):
    from zeroconf import Zeroconf, ServiceBrowser
    found = {}

    class L:
        def add_service(self, zc, t, name):
            info = zc.get_service_info(t, name, 3000)
            if info:
                found[name] = info

        def update_service(self, *a):
            pass

        def remove_service(self, *a):
            pass
    zc = Zeroconf()
    ServiceBrowser(zc, '_shengqiao._tcp.local.', L())
    t0 = time.time()
    while not found and time.time() - t0 < 6:
        time.sleep(0.2)
    info = next(iter(found.values()), None)
    props = {k.decode(): v.decode() for k, v in (info.properties.items() if info else [])}
    check('bonjour: service discovered with id/port', info is not None and info.port == PORT and props.get('id') == hello['id'],
          (list(found), props, info and info.port))
    zc.close()

time.sleep(0.5)
s, h, d = req('127.0.0.1', 'GET', '/api/status')
st = json.loads(d)
check('listeners removed after disconnect', len(st['listeners']) == 0, st['listeners'])
print('\n%d failure(s)' % fails)
sys.exit(1 if fails else 0)
