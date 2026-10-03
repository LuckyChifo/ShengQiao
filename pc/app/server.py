#!/usr/bin/env python3
"""声桥 —— Windows 电脑和 iPhone 之间的双向音频桥。

电脑 → 手机：抓取电脑正在播放的声音（WASAPI 环回），手机上的“声桥”App 或 Safari 收听。
手机 → 电脑：iPhone 麦克风的声音写进 VB-CABLE（CABLE Input），其他软件选 CABLE Output 当麦克风。
"""
import os
import sys

if getattr(sys, 'frozen', False):                      # PyInstaller 打包后
    HERE = getattr(sys, '_MEIPASS', os.path.dirname(sys.executable))
else:
    HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import argparse
import asyncio
import base64
import hashlib
import hmac
import ipaddress
import json
import random
import secrets
import socket
import struct
import threading
import time
import urllib.parse
import uuid

import numpy as np

import capture
import denoise
import mic as micmod
from capture import log, IS_WINDOWS, LOG_LINES

try:
    import lameenc
except Exception:              # 没有 lameenc 时网页版的“锁屏也能听”不可用，其余正常
    lameenc = None

try:
    import qrcode
    from qrcode.image.svg import SvgPathImage
except Exception:
    qrcode = None

VERSION = '2.0.0'
APP_NAME = '声桥'
WEB_DIR = os.path.join(HERE, 'web')
DEFAULT_PORT = 23333
SERVICE_TYPE = '_shengqiao._tcp.local.'
RATE = capture.RATE
BLOCK = capture.BLOCK
MP3_KBPS = 256
WS_MAX_BACKLOG = 96 * 1024      # 每个手机最多积压约 0.5 秒，超出直接丢弃（保证低延迟）
MP3_MAX_BACKLOG = 2 * 1024 * 1024
KEY_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789'


def data_dir():
    """配置和日志放哪：exe 版放 %APPDATA%\\ShengQiao，源码运行放在 pc 目录。"""
    if getattr(sys, 'frozen', False):
        base = os.environ.get('APPDATA') or os.path.expanduser('~')
        d = os.path.join(base, 'ShengQiao')
    else:
        d = os.path.dirname(HERE)
    os.makedirs(d, exist_ok=True)
    return d


DATA_DIR = data_dir()
CONFIG_PATH = os.path.join(DATA_DIR, 'config.json')
RUNNING_PATH = os.path.join(DATA_DIR, 'running.json')


# ------------------------------------------------------------------ config

def load_config():
    cfg = {}
    try:
        with open(CONFIG_PATH, 'r', encoding='utf-8') as f:
            cfg = json.load(f)
    except Exception:
        pass
    changed = False
    if not isinstance(cfg.get('key'), str) or len(cfg['key']) < 4:
        cfg['key'] = ''.join(secrets.choice(KEY_ALPHABET) for _ in range(6))
        changed = True
    if not isinstance(cfg.get('id'), str) or len(cfg['id']) < 8:
        cfg['id'] = uuid.uuid4().hex[:12]
        changed = True
    if not isinstance(cfg.get('port'), int):
        cfg['port'] = DEFAULT_PORT
        changed = True
    cfg.setdefault('device', 'default')
    cfg.setdefault('micDevice', 'auto')
    if cfg.get('denoise') not in denoise.MODES:
        cfg['denoise'] = 'rnnoise'
    if changed:
        save_config(cfg)
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_PATH, 'w', encoding='utf-8') as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception as e:
        log('无法保存配置 %s：%s' % (CONFIG_PATH, e))


# ------------------------------------------------------------------ network

def candidate_ips():
    """找出手机可能访问得到的本机 IPv4 地址，按可能性排序。"""
    found = []
    route_ips = set()

    def add(ip, via_route=False):
        if ip and ip not in found:
            found.append(ip)
        if via_route:
            route_ips.add(ip)

    for target in ('192.168.1.1', '192.168.0.1', '10.255.255.255', '172.20.10.1', '223.5.5.5', '8.8.8.8'):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((target, 53))      # UDP connect 不发包，只用来查路由选出的网卡地址
            add(s.getsockname()[0], True)
        except OSError:
            pass
        finally:
            s.close()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            add(info[4][0])
    except OSError:
        pass

    def score(ip):
        try:
            a = ipaddress.ip_address(ip)
        except ValueError:
            return -1
        if a.is_loopback or a.is_link_local or a.is_unspecified or a.is_multicast:
            return -1
        if a in ipaddress.ip_network('198.18.0.0/15'):      # Clash 等代理的 TUN 虚拟网卡
            return -1
        if a in ipaddress.ip_network('192.168.0.0/16'):
            s = 50
        elif a in ipaddress.ip_network('172.16.0.0/12'):
            s = 40
        elif a in ipaddress.ip_network('10.0.0.0/8'):
            s = 35
        elif a in ipaddress.ip_network('100.64.0.0/10'):    # CGNAT / Tailscale
            s = 5
        else:
            s = 2
        if ip.endswith('.1'):                                # 多半是虚拟网卡 / 网关 / 热点主机
            s -= 20
        if a in ipaddress.ip_network('192.168.56.0/24'):     # VirtualBox
            s -= 30
        if ip in route_ips:
            s += 8
        return s

    return sorted((ip for ip in found if score(ip) >= 0), key=score, reverse=True)


def set_socket_opts(writer, sndbuf=None):
    sock = writer.get_extra_info('socket')
    if sock is None:
        return
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except OSError:
        pass
    if sndbuf:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, sndbuf)
        except OSError:
            pass


def short_ua(ua):
    ua = ua or ''
    if 'ShengQiao' in ua:
        return 'iPhone（App）'
    for key, name in (('iPhone', 'iPhone'), ('iPad', 'iPad'), ('Android', 'Android'),
                      ('Macintosh', 'Mac'), ('Windows', 'Windows'), ('Linux', 'Linux')):
        if key in ua:
            return name
    return '未知设备'


# ------------------------------------------------------------------ bonjour

class Advertiser:
    """在局域网里用 Bonjour 广播“这台电脑开着声桥”，App 打开就能看到。"""

    def __init__(self, cfg, hostname):
        self.cfg = cfg
        self.hostname = hostname
        self.zc = None
        self.info = None
        self.ips = []
        self.port = None
        self.lock = threading.Lock()

    def _make_info(self, ips, port):
        from zeroconf import ServiceInfo
        label = ('声桥 %s' % self.hostname).encode('utf-8')[:60].decode('utf-8', 'ignore')
        return ServiceInfo(
            SERVICE_TYPE, '%s.%s' % (label, SERVICE_TYPE),
            addresses=[socket.inet_aton(ip) for ip in ips], port=port,
            properties={'id': self.cfg['id'], 'name': self.hostname, 'v': '2'},
            server='shengqiao-%s.local.' % self.cfg['id'][:8])

    def update(self, ips, port):
        ips = [ip for ip in ips if ip][:4]
        if not ips or (ips == self.ips and port == self.port):
            return
        threading.Thread(target=self._update, args=(ips, port), daemon=True).start()

    def _update(self, ips, port):
        with self.lock:
            try:
                from zeroconf import Zeroconf, IPVersion
                info = self._make_info(ips, port)
                if self.zc is None:
                    self.zc = Zeroconf(ip_version=IPVersion.V4Only)
                    self.zc.register_service(info, allow_name_change=True)
                else:
                    info = self._make_info(ips, port)
                    info._name = self.info.name                     # 保持注册时（可能被改过）的名字
                    self.zc.update_service(info)
                self.info = info
                self.ips, self.port = ips, port
            except Exception as e:
                log('局域网自动发现（Bonjour）不可用，App 里可以扫码或手动输入地址：%s' % e)

    def close(self):
        with self.lock:
            try:
                if self.zc is not None:
                    if self.info is not None:
                        self.zc.unregister_service(self.info)
                    self.zc.close()
            except Exception:
                pass


# ------------------------------------------------------------------ websocket

WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG = 0x1, 0x2, 0x8, 0x9, 0xA


def ws_accept(key):
    return base64.b64encode(hashlib.sha1((key + WS_GUID).encode('ascii')).digest()).decode('ascii')


def ws_frame(opcode, payload):
    n = len(payload)
    if n < 126:
        head = struct.pack('!BB', 0x80 | opcode, n)
    elif n < 65536:
        head = struct.pack('!BBH', 0x80 | opcode, 126, n)
    else:
        head = struct.pack('!BBQ', 0x80 | opcode, 127, n)
    return head + payload


def ws_unmask(data, mask):
    n = len(data)
    m = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(data, 'little') ^ int.from_bytes(m, 'little')).to_bytes(n, 'little')


async def ws_read_frame(reader):
    """读一条完整消息（处理分片）。返回 (opcode, payload)。"""
    opcode = None
    parts = []
    while True:
        b0, b1 = await reader.readexactly(2)
        fin = b0 & 0x80
        op = b0 & 0x0F
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack('!H', await reader.readexactly(2))[0]
        elif n == 127:
            n = struct.unpack('!Q', await reader.readexactly(8))[0]
        if n > 1 << 18:
            raise ValueError('frame too large')
        mask = await reader.readexactly(4) if b1 & 0x80 else None
        data = await reader.readexactly(n) if n else b''
        if mask and data:
            data = ws_unmask(data, mask)
        if op >= 0x8:                    # 控制帧可以夹在分片中间
            return op, data
        if op != 0:
            opcode = op
        parts.append(data)
        if fin:
            return opcode, b''.join(parts)


# ------------------------------------------------------------------ clients

class WSClient:
    """一个手机连接（网页或 App）。网页默认只收听；App 用 config 消息开关收听和麦克风。"""
    _next = 1

    def __init__(self, app, writer, ip, ua):
        self.id = WSClient._next
        WSClient._next += 1
        self.app = app
        self.writer = writer
        self.ip = ip
        self.ua = ua
        self.since = time.time()
        self.sent = 0
        self.dropped = 0
        self.stats = {}
        self.held = []
        self.hold_until = 0.0
        self.listen = True
        self.mic = False
        self.mic_rate = 48000
        self.mic_seq = -1
        self.mic_lost = 0
        self.mic_frames = 0
        self.is_app = 'ShengQiao' in (ua or '')

    def _write(self, frame):
        tr = self.writer.transport
        if tr.is_closing():
            return
        if tr.get_write_buffer_size() > WS_MAX_BACKLOG:
            self.dropped += 1
            return
        tr.write(frame)
        self.sent += 1

    def send_audio(self, frame):
        jitter = self.app.args.sim_jitter
        if not jitter:
            self._write(frame)
            return
        now = time.monotonic()           # 调试用：模拟 Wi-Fi 抖动 / 突发
        if now < self.hold_until:
            self.held.append(frame)
            return
        if self.held:
            held, self.held = self.held, []
            for f in held:
                self._write(f)
        r = random.random()
        if r < 0.02:
            self.hold_until = now + random.uniform(0, jitter) / 1000.0
        elif r < 0.021:
            self.hold_until = now + 3 * jitter / 1000.0
        self._write(frame)

    def send_json(self, obj):
        tr = self.writer.transport
        if not tr.is_closing():
            tr.write(ws_frame(OP_TEXT, json.dumps(obj, ensure_ascii=False).encode('utf-8')))

    def info(self):
        st = self.stats
        return {
            'id': self.id, 'ip': self.ip, 'device': short_ua(self.ua), 'mode': 'live',
            'listen': self.listen, 'mic': self.mic and self.app.mic.owner is self,
            'since': self.since, 'dropped': self.dropped,
            'latency': st.get('lat'), 'buffer': st.get('buf'), 'target': st.get('tgt'),
            'underruns': st.get('und'), 'rtt': st.get('rtt'),
        }


class Mp3Client:
    """网页版“锁屏也能听”：每个连接一个 MP3 编码器。"""
    _next = 1

    def __init__(self, writer, ip, ua):
        self.id = Mp3Client._next
        Mp3Client._next += 1
        self.writer = writer
        self.ip = ip
        self.ua = ua
        self.since = time.time()
        enc = lameenc.Encoder()
        enc.set_bit_rate(MP3_KBPS)
        enc.set_in_sample_rate(RATE)
        enc.set_channels(2)
        enc.set_quality(5)
        self.enc = enc

    def feed(self, pcm):
        tr = self.writer.transport
        if tr.is_closing():
            return
        data = self.enc.encode(pcm)
        if data:
            if tr.get_write_buffer_size() > MP3_MAX_BACKLOG:
                tr.close()
                return
            tr.write(bytes(data))

    def info(self):
        return {'id': 'mp3-%d' % self.id, 'ip': self.ip, 'device': short_ua(self.ua),
                'mode': 'bg', 'listen': True, 'mic': False, 'since': self.since}


# ------------------------------------------------------------------ hub

def make_chime():
    """“叮咚”测试音（float32, (n, 2)）。"""
    n = int(RATE * 1.3)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for start, f in ((0.0, 659.25), (0.45, 523.25)):
        dt = t - start
        env = np.where(dt >= 0, np.exp(-np.clip(dt, 0, None) / 0.35) * np.clip(dt / 0.004, 0, 1), 0.0)
        out += 0.3 * env * (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t))
    return np.stack([out, out], axis=1).astype(np.float32)


class Hub:
    """把 10 ms 一块的音频广播给所有在收听的连接；采集断流时补静音，保持时间轴连续。"""

    def __init__(self, app):
        self.app = app
        self.ws = set()
        self.mp3 = set()
        self.seq = 0
        self.last_real = 0.0
        self.fill_t = None
        self.peak = 0.0
        self.chime = None
        self.chime_pos = 0
        self.zero = np.zeros((BLOCK, 2), np.float32)

    def has_listeners(self):
        return bool(self.mp3) or any(c.listen for c in self.ws)

    def on_capture_block(self, blk):
        self.last_real = time.monotonic()
        self.fill_t = None
        self.publish(blk)

    def play_chime(self):
        self.chime = make_chime()
        self.chime_pos = 0

    def publish(self, blk):
        if self.chime is not None:
            seg = self.chime[self.chime_pos:self.chime_pos + BLOCK]
            blk = blk.copy()
            blk[:len(seg)] += seg
            self.chime_pos += BLOCK
            if self.chime_pos >= len(self.chime):
                self.chime = None
        x = np.clip(blk, -1.0, 1.0)
        peak = float(np.max(np.abs(x)))
        if peak > self.peak:
            self.peak = peak
        if not self.has_listeners():
            return
        pcm = (x * 32767.0).astype('<i2').tobytes()
        flags = 0 if peak > 1e-4 else 1
        frame = ws_frame(OP_BIN, struct.pack('<BBHI', 1, flags, 0, self.seq) + pcm)
        self.seq = (self.seq + 1) & 0xFFFFFFFF
        for c in tuple(self.ws):
            if c.listen:
                c.send_audio(frame)
        for c in tuple(self.mp3):
            c.feed(pcm)

    async def filler(self):
        """采集源在没声音时可能不出数据（或出数据不均匀），这里按真实时间补静音。"""
        while True:
            await asyncio.sleep(0.02)
            if not self.has_listeners():
                self.fill_t = None
                continue
            now = time.monotonic()
            if now - self.last_real < 0.1:
                continue
            if self.fill_t is None:
                self.fill_t = max(self.last_real, now - 0.15)
            n = int((now - self.fill_t) / 0.01)
            if n <= 0:
                continue
            for _ in range(min(n, 50)):
                self.publish(self.zero)
            self.fill_t += n * 0.01
            if now - self.fill_t > 0.5:
                self.fill_t = now


# ------------------------------------------------------------------ http

STATIC = {
    '/': ('index.html', 'text/html; charset=utf-8'),
    '/index.html': ('index.html', 'text/html; charset=utf-8'),
    '/player-core.js': ('player-core.js', 'application/javascript; charset=utf-8'),
    '/icon-180.png': ('icon-180.png', 'image/png'),
    '/icon-512.png': ('icon-512.png', 'image/png'),
    '/favicon.ico': ('icon-180.png', 'image/png'),
    '/keepawake.mp4': ('keepawake.mp4', 'video/mp4'),
}
LOCAL_ONLY_STATIC = {
    '/pc': ('pc.html', 'text/html; charset=utf-8'),
}
REASONS = {200: 'OK', 204: 'No Content', 206: 'Partial Content', 400: 'Bad Request', 403: 'Forbidden',
           404: 'Not Found', 405: 'Method Not Allowed', 416: 'Range Not Satisfiable', 503: 'Service Unavailable'}


def http_response(status, body=b'', ctype='text/plain; charset=utf-8', extra=None):
    if isinstance(body, str):
        body = body.encode('utf-8')
    lines = ['HTTP/1.1 %d %s' % (status, REASONS.get(status, 'OK')),
             'Content-Type: ' + ctype,
             'Content-Length: %d' % len(body),
             'Cache-Control: no-cache, no-store, must-revalidate',
             'Connection: close']
    for k, v in (extra or {}).items():
        lines.append('%s: %s' % (k, v))
    return ('\r\n'.join(lines) + '\r\n\r\n').encode('latin-1') + body


def json_response(obj, status=200):
    return http_response(status, json.dumps(obj, ensure_ascii=False), 'application/json; charset=utf-8')


def wav_bytes(x, rate=48000):
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    head = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36 + len(pcm), b'WAVE', b'fmt ', 16, 1, 1, rate,
                       rate * 2, 2, 16, b'data', len(pcm))
    return head + pcm


# ------------------------------------------------------------------ app

class App:
    def __init__(self, args, cfg, source, mic_output):
        self.args = args
        self.cfg = cfg
        self.source = source
        self.hub = Hub(self)
        dn = cfg.get('denoise', 'rnnoise')
        if dn not in denoise.available_modes():
            dn = 'light'
        self.mic = micmod.MicSink(mic_output, cfg.get('micDevice') or 'auto', dn)
        self.loop = None
        self.port = None
        self.urls = []
        self.local_url = ''
        self.capture = None
        self.last_console = 0.0
        self.idle_since = time.monotonic()
        self.hostname = socket.gethostname()
        self.advertiser = Advertiser(cfg, self.hostname) if not args.no_bonjour else None
        self.ready = threading.Event()
        self.stopping = False

    # ---------------------------------------------------------- helpers
    def key_ok(self, given):
        if self.args.no_key:
            return True
        given = (given or '').strip().lower()
        return hmac.compare_digest(given.encode(), self.cfg['key'].encode())

    def phone_url(self, ip):
        q = '' if self.args.no_key else '?k=' + self.cfg['key']
        return 'http://%s:%d/%s' % (ip, self.port, q)

    def refresh_urls(self):
        ips = candidate_ips()
        self.urls = [self.phone_url(ip) for ip in ips]
        host = self.hostname
        self.local_url = self.phone_url(host.lower() + '.local') if host and host.isascii() and ' ' not in host else ''
        if self.advertiser is not None:
            self.advertiser.update(ips, self.port)

    def capture_conflict(self):
        """采集的输出设备和麦克风写入的设备是同一个 → 手机会听到自己的声音。"""
        if self.mic.owner is None or not self.mic.device_name:
            return False
        dn = (self.capture.device_name or '').lower()
        return bool(dn) and (dn in self.mic.device_name.lower() or self.mic.device_name.lower() in dn)

    def status(self):
        listeners = [c.info() for c in self.hub.ws] + [c.info() for c in self.hub.mp3]
        peak, self.hub.peak = self.hub.peak, 0.0
        return {
            'app': APP_NAME, 'version': VERSION, 'hostname': self.hostname, 'id': self.cfg['id'],
            'port': self.port, 'key': '' if self.args.no_key else self.cfg['key'],
            'urls': self.urls, 'localUrl': self.local_url,
            'source': self.capture.source.label, 'sourceKind': self.capture.source.kind,
            'device': self.capture.device_name, 'devicePref': self.capture.device_pref or 'default',
            'state': self.capture.state, 'error': self.capture.error, 'active': self.capture.active,
            'level': round(peak, 4), 'mp3': lameenc is not None, 'listeners': listeners,
            'mic': self.mic.status(), 'conflict': self.capture_conflict(),
            'log': list(LOG_LINES)[-60:],
        }

    def mic_status_for(self, client):
        st = self.mic.status()
        return {'type': 'status', 'mic': {
            'state': st['state'], 'device': st['device'], 'error': st['error'],
            'mine': self.mic.owner is client, 'latencyMs': st['latencyMs'], 'underruns': st['underruns'],
            'busy': self.mic.owner is not None and self.mic.owner is not client,
            'denoise': st['denoise'], 'denoiseModes': st['denoiseModes']}, 'listeners': len(self.hub.ws)}

    def set_denoise(self, mode, source):
        if mode not in denoise.available_modes() or mode == self.mic.denoise_mode:
            return False
        self.mic.set_denoise(mode)
        self.cfg['denoise'] = mode
        save_config(self.cfg)
        log('降噪改为：%s（%s）' % (denoise.MODE_NAMES.get(mode, mode), source))
        return True

    # ---------------------------------------------------------- periodic
    async def manage_capture(self):
        """有人在听（或电脑端控制页开着）才采集，没人听 15 秒后停止，让电脑可以正常睡眠。"""
        while True:
            await asyncio.sleep(0.5)
            now = time.monotonic()
            want = self.hub.has_listeners() or (now - self.last_console < 5.0)
            if want:
                self.idle_since = now
                if not self.capture.active:
                    self.capture.activate(True)
            elif self.capture.active and now - self.idle_since > 15.0:
                self.capture.activate(False)
                log('没有手机在听，暂停采集')

    async def push_status(self):
        while True:
            await asyncio.sleep(1.0)
            for c in tuple(self.hub.ws):
                if c.is_app or c.mic:
                    try:
                        c.send_json(self.mic_status_for(c))
                    except Exception:
                        pass
            # 开着麦的手机突然没了数据（比如手机锁屏后被系统挂起）
            if self.mic.owner is not None and time.monotonic() - self.mic.last_feed > 10:
                log('超过 10 秒没收到麦克风数据，关闭麦克风输出')
                self.mic.stop()

    async def refresh_urls_task(self):
        while True:
            await asyncio.sleep(10)
            try:
                old = list(self.urls)
                self.refresh_urls()
                if self.urls and old and self.urls[0] != old[0]:
                    log('电脑的局域网地址变了，新地址：%s' % self.urls[0])
            except Exception:
                pass

    # ---------------------------------------------------------- http
    async def handle(self, reader, writer):
        peer = writer.get_extra_info('peername') or ('?', 0)
        ip = peer[0]
        if ip.startswith('::ffff:'):
            ip = ip[7:]
        try:
            try:
                head = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 15)
            except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError,
                    ConnectionError, OSError):
                return
            lines = head.decode('latin-1').split('\r\n')
            try:
                method, target, _ = lines[0].split(' ', 2)
            except ValueError:
                return
            headers = {}
            for ln in lines[1:]:
                if ':' in ln:
                    k, v = ln.split(':', 1)
                    headers[k.strip().lower()] = v.strip()
            path, _, qs = target.partition('?')
            path = urllib.parse.unquote(path)
            query = urllib.parse.parse_qs(qs)
            local = ip in ('127.0.0.1', '::1')

            if 'websocket' in headers.get('upgrade', '').lower():
                await self.serve_ws(reader, writer, headers, query, ip)
                return
            if path in ('/stream.mp3', '/live.mp3'):
                await self.serve_mp3(reader, writer, method, query, ip, headers)
                return

            body = b''
            if method == 'POST':
                n = int(headers.get('content-length') or 0)
                if 0 < n <= 65536:
                    body = await reader.readexactly(n)
            resp = await self.route(method, path, query, body, local, headers)
            if method == 'HEAD':
                resp = resp.split(b'\r\n\r\n', 1)[0] + b'\r\n\r\n'
            writer.write(resp)
            await writer.drain()
        except (ConnectionError, OSError, asyncio.IncompleteReadError):
            pass
        except Exception as e:
            log('处理请求出错：%r' % (e,))
        finally:
            try:
                writer.close()
            except Exception:
                pass

    def static(self, name, ctype, headers=None):
        try:
            with open(os.path.join(WEB_DIR, name), 'rb') as f:
                data = f.read()
        except OSError:
            return http_response(404, 'not found')
        rng = (headers or {}).get('range', '')          # iPhone Safari 播放 <video> 需要 Range 支持
        if rng.startswith('bytes=') and ',' not in rng:
            a, _, b = rng[6:].strip().partition('-')
            size = len(data)
            try:
                if a == '':
                    start, end = max(0, size - int(b)), size - 1
                else:
                    start = int(a)
                    end = min(int(b), size - 1) if b else size - 1
            except ValueError:
                start, end = 0, size - 1
            if start >= size or start > end:
                return http_response(416, b'', ctype, {'Content-Range': 'bytes */%d' % size})
            return http_response(206, data[start:end + 1], ctype,
                                 {'Content-Range': 'bytes %d-%d/%d' % (start, end, size), 'Accept-Ranges': 'bytes'})
        return http_response(200, data, ctype, {'Accept-Ranges': 'bytes'})

    async def route(self, method, path, query, body, local, headers=None):
        if method not in ('GET', 'HEAD', 'POST'):
            return http_response(405, 'method not allowed')
        if path in STATIC:
            return self.static(*STATIC[path], headers=headers)
        if path == '/api/check':
            k = (query.get('k') or [''])[0] or (headers or {}).get('x-sq-key', '')
            if not self.key_ok(k):
                return json_response({'ok': False}, 403)
            return json_response({'ok': True, 'name': self.hostname, 'id': self.cfg['id'],
                                  'mp3': lameenc is not None, 'version': VERSION, 'features': ['mic']})
        if path in LOCAL_ONLY_STATIC or path.startswith('/api/'):
            if not local:
                return http_response(403, '只能在运行声桥的这台电脑上打开')
            if path in LOCAL_ONLY_STATIC:
                return self.static(*LOCAL_ONLY_STATIC[path])
            return await self.api(method, path, query, body)
        return http_response(404, 'not found')

    async def api(self, method, path, query, body):
        if path == '/api/status':
            self.last_console = time.monotonic()
            return json_response(self.status())
        if path == '/api/qr.svg':
            url = (query.get('u') or [''])[0]
            if not url or qrcode is None:
                return http_response(404, 'no qr')
            qr = qrcode.QRCode(border=2, box_size=10, image_factory=SvgPathImage,
                               error_correction=qrcode.constants.ERROR_CORRECT_M)
            qr.add_data(url)
            qr.make(fit=True)
            return http_response(200, qr.make_image().to_string(encoding='unicode'), 'image/svg+xml; charset=utf-8')
        if path == '/api/devices':
            try:
                devs = await self.loop.run_in_executor(None, self.capture.source.list_devices)
            except Exception as e:
                return json_response({'devices': [], 'error': str(e)})
            return json_response({'devices': devs, 'selected': self.capture.device_pref or 'default'})
        if path == '/api/mic-devices':
            devs = await self.loop.run_in_executor(None, self.mic.list_devices)
            cable = micmod.find_cable(devs)
            return json_response({'devices': devs, 'selected': self.mic.device_pref,
                                  'cable': cable['id'] if cable else None})
        if path in ('/api/device', '/api/mic-device') and method == 'POST':
            try:
                dev = json.loads(body.decode('utf-8') or '{}').get('id') or ''
            except Exception:
                return http_response(400, 'bad json')
            if path == '/api/device':
                dev = dev or 'default'
                self.cfg['device'] = dev
                save_config(self.cfg)
                if dev != (self.capture.device_pref or 'default'):
                    self.capture.set_device(dev)
                    log('采集设备改为：%s' % ('跟随系统默认' if dev == 'default' else dev))
            else:
                dev = dev or 'auto'
                self.cfg['micDevice'] = dev
                save_config(self.cfg)
                if dev != self.mic.device_pref:
                    self.mic.set_device(dev)
                    log('麦克风输出设备改为：%s' % ('自动（VB-CABLE）' if dev == 'auto' else dev))
            return json_response({'ok': True})
        if path == '/api/denoise' and method == 'POST':
            try:
                mode = json.loads(body.decode('utf-8') or '{}').get('mode') or ''
            except Exception:
                return http_response(400, 'bad json')
            self.set_denoise(mode, '电脑端控制页')
            return json_response({'ok': True, 'mode': self.mic.denoise_mode})
        if path == '/api/chime' and method == 'POST':
            self.hub.play_chime()
            return json_response({'ok': True})
        if path == '/api/debug/mic.wav' and self.args.debug:
            out = getattr(self.mic.output, 'recording', None)
            x = out() if out else np.zeros(0, np.float32)
            return http_response(200, wav_bytes(x), 'audio/wav')
        return http_response(404, 'not found')

    # ---------------------------------------------------------- websocket
    async def serve_ws(self, reader, writer, headers, query, ip):
        key = headers.get('sec-websocket-key')
        if not key:
            writer.write(http_response(400, 'websocket only'))
            await writer.drain()
            return
        writer.write(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n'
                      'Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n' % ws_accept(key)).encode('ascii'))
        set_socket_opts(writer, sndbuf=64 * 1024)
        given = (query.get('k') or [''])[0] or headers.get('x-sq-key', '')
        if not self.key_ok(given):
            writer.write(ws_frame(OP_CLOSE, struct.pack('!H', 4001) + b'bad key'))
            await writer.drain()
            return
        client = WSClient(self, writer, ip, headers.get('user-agent', ''))
        client.send_json({'type': 'hello', 'rate': RATE, 'channels': 2, 'name': self.hostname,
                          'id': self.cfg['id'], 'version': VERSION, 'mp3': lameenc is not None,
                          'features': ['mic']})
        self.hub.ws.add(client)
        log('%s %s 连上了' % (short_ua(client.ua), ip))
        try:
            while True:
                opcode, data = await asyncio.wait_for(ws_read_frame(reader), 20)
                if opcode == OP_CLOSE:
                    writer.write(ws_frame(OP_CLOSE, data[:2]))
                    break
                if opcode == OP_PING:
                    writer.write(ws_frame(OP_PONG, data))
                elif opcode == OP_TEXT:
                    self.on_ws_text(client, data)
                elif opcode == OP_BIN:
                    self.on_ws_binary(client, data)
        except (asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionError, OSError, ValueError):
            pass
        finally:
            self.hub.ws.discard(client)
            if self.mic.owner is client:
                self.mic.stop(client)
                log('%s 的麦克风已关闭' % short_ua(client.ua))
            log('%s %s 断开' % (short_ua(client.ua), ip))

    def on_ws_text(self, client, data):
        try:
            msg = json.loads(data.decode('utf-8'))
        except Exception:
            return
        t = msg.get('type')
        if t == 'ping':
            client.send_json({'type': 'pong', 't': msg.get('t')})
        elif t == 'stats':
            client.stats = {k: msg.get(k) for k in ('lat', 'buf', 'tgt', 'und', 'rtt')}
        elif t == 'config':
            client.is_app = True
            if 'listen' in msg:
                client.listen = bool(msg['listen'])
            if isinstance(msg.get('denoise'), str):
                self.set_denoise(msg['denoise'], short_ua(client.ua))
            if 'micRate' in msg:
                try:
                    client.mic_rate = max(8000, min(192000, int(msg['micRate'])))
                except (TypeError, ValueError):
                    pass
            if 'mic' in msg:
                on = bool(msg['mic'])
                if on and (not client.mic or self.mic.owner is not client):
                    if self.mic.owner is not None and self.mic.owner is not client:
                        log('麦克风从另一台手机切换到 %s' % short_ua(client.ua))
                    client.mic_seq = -1
                    self.mic.start(client, client.mic_rate)
                    log('%s 开始当麦克风（%d Hz）' % (short_ua(client.ua), client.mic_rate))
                elif not on and client.mic:
                    self.mic.stop(client)
                    log('%s 关闭了麦克风' % short_ua(client.ua))
                elif on and client.mic_rate != int(self.mic.jitter.src_rate):
                    self.mic.jitter.set_src_rate(client.mic_rate)
                client.mic = on
            client.send_json(self.mic_status_for(client))

    def on_ws_binary(self, client, data):
        # 手机 → 电脑：[2][flags][0][0][seq u32 LE] + 16 位单声道 PCM
        if len(data) < 10 or data[0] != 2 or not client.mic:
            return
        seq = struct.unpack_from('<I', data, 4)[0]
        if client.mic_seq >= 0 and seq != client.mic_seq:
            client.mic_lost += (seq - client.mic_seq) & 0xFFFFFFFF
        client.mic_seq = (seq + 1) & 0xFFFFFFFF
        client.mic_frames += 1
        n = (len(data) - 8) // 2
        self.mic.feed(client, np.frombuffer(data, dtype='<i2', count=n, offset=8))

    # ---------------------------------------------------------- mp3
    async def serve_mp3(self, reader, writer, method, query, ip, headers):
        if not self.key_ok((query.get('k') or [''])[0]):
            writer.write(http_response(403, 'bad key'))
            await writer.drain()
            return
        if lameenc is None:
            writer.write(http_response(503, 'mp3 encoder not available'))
            await writer.drain()
            return
        writer.write(('HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\n'
                      'Cache-Control: no-cache, no-store\r\nConnection: close\r\n'
                      'Access-Control-Allow-Origin: *\r\n\r\n').encode('ascii'))
        if method == 'HEAD':
            await writer.drain()
            return
        set_socket_opts(writer)
        client = Mp3Client(writer, ip, headers.get('user-agent', ''))
        self.hub.mp3.add(client)
        log('%s %s 开始收听（网页锁屏模式）' % (short_ua(client.ua), ip))
        try:
            while True:
                if not await reader.read(1024):
                    break
        except (ConnectionError, OSError):
            pass
        finally:
            self.hub.mp3.discard(client)
            log('%s %s 断开（网页锁屏模式）' % (short_ua(client.ua), ip))

    # ---------------------------------------------------------- start
    async def start(self):
        self.loop = asyncio.get_running_loop()
        loop = self.loop
        fallback = capture.PyAudioSource if self.source.kind == 'soundcard' else None
        self.capture = capture.CaptureRunner(
            self.source,
            lambda blk: loop.call_soon_threadsafe(self.hub.on_capture_block, blk),
            device_pref=self.cfg.get('device') or 'default', fallback=fallback)
        server = None
        last_err = None
        base = self.args.port or self.cfg['port']
        for port in range(base, base + 10):
            try:
                server = await asyncio.start_server(self.handle, self.args.bind, port, backlog=64)
                self.port = port
                break
            except OSError as e:
                last_err = e
        if server is None:
            raise RuntimeError('端口 %d~%d 都被占用：%s' % (base, base + 9, last_err))
        if self.port != base:
            log('端口 %d 被占用，改用 %d' % (base, self.port))
        try:
            with open(RUNNING_PATH, 'w', encoding='utf-8') as f:
                json.dump({'port': self.port, 'pid': os.getpid()}, f)
        except Exception:
            pass
        self.refresh_urls()
        tasks = [asyncio.ensure_future(self.hub.filler()),
                 asyncio.ensure_future(self.manage_capture()),
                 asyncio.ensure_future(self.push_status()),
                 asyncio.ensure_future(self.refresh_urls_task())]
        print_banner(self)
        self.ready.set()
        if not self.args.no_browser:
            open_browser(self.console_url())
        async with server:
            await server.serve_forever()
        for t in tasks:
            t.cancel()

    def console_url(self):
        return 'http://127.0.0.1:%d/pc' % self.port


# ------------------------------------------------------------------ console / startup

def setup_console():
    if not IS_WINDOWS or sys.stdout is None:
        return
    try:
        import ctypes
        k32 = ctypes.windll.kernel32
        k32.SetConsoleTitleW('%s（关闭此窗口即退出）' % APP_NAME)
        h = k32.GetStdHandle(-10)        # 关闭“快速编辑模式”：否则在窗口里点一下程序就会卡住
        mode = ctypes.c_uint32()
        if k32.GetConsoleMode(h, ctypes.byref(mode)):
            k32.SetConsoleMode(h, (mode.value & ~0x0040) | 0x0080)
    except Exception:
        pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors='replace')
        except Exception:
            pass


def open_browser(url):
    def run():
        try:
            if IS_WINDOWS:
                import ctypes
                ctypes.windll.ole32.CoInitializeEx(None, 0x2)
            import webbrowser
            webbrowser.open(url)
        except Exception:
            pass
    threading.Thread(target=run, daemon=True).start()


def console_qr_ok():
    if sys.stdout is None:
        return False
    if not IS_WINDOWS or os.environ.get('WT_SESSION'):
        return True
    try:
        import ctypes
        return ctypes.windll.kernel32.GetConsoleOutputCP() == 65001 or \
            ctypes.windll.kernel32.GetACP() not in (932, 936, 949, 950)
    except Exception:
        return False


def print_banner(app):
    log('%s v%s 已启动，端口 %d' % (APP_NAME, VERSION, app.port))
    if app.urls:
        log('手机收听地址：%s' % app.urls[0])
    else:
        log('没找到局域网地址：请先连上 Wi-Fi 或手机热点')
    log('采集：%s；麦克风输出：%s' % (app.capture.source.label,
                                   (micmod.find_cable(app.mic.list_devices()) or {}).get('name', '未找到 VB-CABLE')))
    if sys.stdout is None:
        return
    if app.urls and qrcode is not None and not app.args.no_qr and console_qr_ok():
        try:
            qr = qrcode.QRCode(border=1, error_correction=qrcode.constants.ERROR_CORRECT_L)
            qr.add_data(app.urls[0])
            qr.make(fit=True)
            qr.print_ascii(out=sys.stdout, invert=True)
        except Exception:
            pass
    sys.stdout.flush()


def already_running():
    """Windows：同一时间只运行一个声桥。已经在运行就打开它的控制页，然后退出。"""
    if not IS_WINDOWS:
        return False
    try:
        import ctypes
        k32 = ctypes.windll.kernel32
        already_running.handle = k32.CreateMutexW(None, False, 'Local\\ShengQiao-single-instance')
        if k32.GetLastError() != 183:          # ERROR_ALREADY_EXISTS
            return False
    except Exception:
        return False
    try:
        with open(RUNNING_PATH, 'r', encoding='utf-8') as f:
            port = json.load(f)['port']
        import webbrowser
        webbrowser.open('http://127.0.0.1:%d/pc' % port)
    except Exception:
        pass
    return True


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description='%s：电脑声音传到 iPhone，iPhone 麦克风传到电脑' % APP_NAME)
    ap.add_argument('--port', type=int, default=0, help='监听端口（默认 %d）' % DEFAULT_PORT)
    ap.add_argument('--bind', default='0.0.0.0', help='监听地址')
    ap.add_argument('--source', default='auto', choices=['auto', 'soundcard', 'pyaudio', 'tone', 'sine'],
                    help='电脑声音来源：auto=自动；tone/sine=测试音')
    ap.add_argument('--mic-out', default='auto', choices=['auto', 'fake'], help='麦克风输出：auto=WASAPI；fake=测试用')
    ap.add_argument('--device', default=None, help='要采集的输出设备（名称的一部分）')
    ap.add_argument('--list-devices', action='store_true', help='列出可采集的输出设备后退出')
    ap.add_argument('--no-key', action='store_true', help='不使用访问码')
    ap.add_argument('--no-browser', action='store_true', help='启动后不自动打开控制页')
    ap.add_argument('--background', action='store_true', help='开机自启时用：不打开控制页')
    ap.add_argument('--no-tray', action='store_true', help='不显示托盘图标（在命令行里运行）')
    ap.add_argument('--no-qr', action='store_true', help='不在命令行打印二维码')
    ap.add_argument('--no-bonjour', action='store_true', help='不在局域网广播（App 里就需要扫码/手动输入）')
    ap.add_argument('--no-keepalive', action='store_true', help='不播放静音保活流')
    ap.add_argument('--debug', action='store_true', help=argparse.SUPPRESS)
    ap.add_argument('--sim-jitter', type=float, default=0, help=argparse.SUPPRESS)
    ap.add_argument('--sim-drift', type=float, default=0, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    if args.background:
        args.no_browser = True
    return args


def build(args):
    """在主线程创建采集和麦克风输出（soundcard 要求在其他 COM 调用之前、在主线程初始化）。"""
    source = capture.make_source(args.source, keepalive=not args.no_keepalive, drift_ppm=args.sim_drift)
    mic_output = None
    if args.mic_out == 'fake' or not IS_WINDOWS:
        mic_output = micmod.FakeOutput(keep_seconds=30 if args.debug else 0)
    else:
        try:
            mic_output = micmod.SoundcardOutput()
        except Exception as e:
            log('麦克风输出不可用：%s' % e)
    return source, mic_output


def main(argv=None):
    args = parse_args(argv)
    frozen = getattr(sys, 'frozen', False)
    if sys.stdout is None or frozen:
        capture.set_log_file(os.path.join(DATA_DIR, 'log.txt'))
    setup_console()
    if args.list_devices:
        source, _ = build(args)
        for d in source.list_devices():
            print(('* ' if d.get('default') else '  ') + d['name'])
        return 0
    if already_running():
        return 0
    try:
        source, mic_output = build(args)
    except Exception as e:
        log(str(e))
        return 2
    cfg = load_config()
    if args.device:
        cfg['device'] = args.device
    app = App(args, cfg, source, mic_output)

    use_tray = IS_WINDOWS and not args.no_tray
    if not use_tray:
        try:
            asyncio.run(app.start())
        except KeyboardInterrupt:
            pass
        except Exception as e:
            log('启动失败：%s' % e)
            return 1
        finally:
            if app.advertiser:
                app.advertiser.close()
            log('已退出')
        return 0

    # 托盘模式：网络服务在后台线程，托盘图标在主线程
    errors = []

    def run_server():
        try:
            asyncio.run(app.start())
        except Exception as e:
            errors.append(e)
            log('启动失败：%s' % e)
            app.ready.set()
    threading.Thread(target=run_server, name='server', daemon=True).start()
    app.ready.wait(15)
    if errors:
        try:
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, '声桥启动失败：%s\n\n日志：%s' % (errors[0], DATA_DIR),
                                             APP_NAME, 0x10)
        except Exception:
            pass
        return 1
    import tray
    tray.run(app)
    if app.advertiser:
        app.advertiser.close()
    log('已退出')
    os._exit(0)


if __name__ == '__main__':
    sys.exit(main())
