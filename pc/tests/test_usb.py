"""USB 直连（usbmux）端到端测试，不需要真的 iPhone 或 iTunes。

    python3 pc/tests/test_usb.py

本脚本同时扮演两个角色：
  * 假的 usbmuxd：说 plist 协议，回应 Listen / Connect，把 Connect 转发到“假手机”
  * 假的 iPhone App：在一个本机端口上当 WebSocket 服务端（和真 App 里 NWListener + WebSocket 一样），
    检查电脑发来的帧是不是带掩码的客户端帧，收听音频，再发麦克风数据回去
然后启动真正的 server.py（--usbmux 指向假 usbmuxd），看它能不能自己连上、传音频、收麦克风、断线重连。
"""
import asyncio
import base64
import hashlib
import json
import os
import plistlib
import socket
import struct
import subprocess
import sys
import time
import urllib.request

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, '..', 'app', 'server.py')
WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
WEB_PORT = 23555
fails = 0


def check(name, cond, extra=''):
    global fails
    print(('PASS ' if cond else 'FAIL ') + name + (('  ' + str(extra)) if extra else ''), flush=True)
    if not cond:
        fails += 1


# ------------------------------------------------------------------ 假手机（WebSocket 服务端）
class FakePhone:
    def __init__(self):
        self.port = 0
        self.server = None
        self.sessions = 0
        self.refuse = True            # True 时模拟“手机上声桥没打开”：usbmux Connect 被拒绝
        self.hello = None
        self.audio = []               # (seq, flags)
        self.unmasked_from_pc = 0     # 电脑发来的没掩码的帧数（应为 0）
        self.texts = []
        self.pong = False
        self.writer = None

    async def start(self):
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def read_frame(self, reader):
        b0, b1 = await reader.readexactly(2)
        op, n, masked = b0 & 0xF, b1 & 0x7F, bool(b1 & 0x80)
        if n == 126:
            n = struct.unpack('!H', await reader.readexactly(2))[0]
        elif n == 127:
            n = struct.unpack('!Q', await reader.readexactly(8))[0]
        mask = await reader.readexactly(4) if masked else b''
        data = await reader.readexactly(n) if n else b''
        if masked and data:
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        return op, data, masked

    @staticmethod
    def frame(op, payload):
        n = len(payload)
        head = struct.pack('!BB', 0x80 | op, n) if n < 126 else struct.pack('!BBH', 0x80 | op, 126, n)
        return head + payload                      # 服务端帧不掩码

    async def handle(self, reader, writer):
        try:
            head = await reader.readuntil(b'\r\n\r\n')
            key = [l.split(':', 1)[1].strip() for l in head.decode().split('\r\n') if l.lower().startswith('sec-websocket-key')][0]
            acc = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
            writer.write(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                          'Sec-WebSocket-Accept: %s\r\n\r\n' % acc).encode())
            self.sessions += 1
            self.writer = writer
            while True:
                op, data, masked = await self.read_frame(reader)
                if not masked:
                    self.unmasked_from_pc += 1
                if op == 0x1:
                    msg = json.loads(data)
                    self.texts.append(msg)
                    if msg.get('type') == 'hello':
                        self.hello = msg
                        # 像真 App 一样：回 config，打开收听 + 麦克风
                        writer.write(self.frame(0x1, json.dumps({'type': 'config', 'listen': True, 'mic': True,
                                                                   'micRate': 48000, 'denoise': 'off'}).encode()))
                        writer.write(self.frame(0x9, b'hi'))                      # 服务端 ping，电脑应回 pong
                    elif msg.get('type') == 'pong':
                        pass
                elif op == 0x2:
                    if len(data) >= 8 and data[0] == 1:
                        self.audio.append((struct.unpack_from('<I', data, 4)[0], data[1]))
                elif op == 0xA:
                    self.pong = True
                elif op == 0x8:
                    break
        except (asyncio.IncompleteReadError, ConnectionError, OSError, IndexError):
            pass
        finally:
            writer.close()
            self.writer = None

    async def send_mic_tone(self, seconds=3.0, freq=1000.0):
        """每 10 ms 一帧；按绝对时间追赶（Windows 上 asyncio.sleep 的精度只有 ~15 ms）。"""
        w = self.writer
        t0 = time.monotonic()
        sent = 0
        total = int(seconds * 100)
        while sent < total:
            due = min(total, int((time.monotonic() - t0) / 0.01) + 1)
            while sent < due:
                t = (np.arange(480) + sent * 480) / 48000.0
                pcm = (0.5 * 32767 * np.sin(2 * np.pi * freq * t)).astype('<i2').tobytes()
                w.write(self.frame(0x2, struct.pack('<BBHI', 2, 0, 0, sent) + pcm))
                sent += 1
            await asyncio.sleep(0.005)


# ------------------------------------------------------------------ 假 usbmuxd
class FakeMux:
    def __init__(self, phone):
        self.phone = phone
        self.port = 0
        self.server = None
        self.listeners = []
        self.connects = 0

    async def start(self):
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]

    @staticmethod
    def pack(msg, tag):
        body = plistlib.dumps(msg)
        return struct.pack('<IIII', 16 + len(body), 1, 8, tag) + body

    async def attach(self, did=7):
        for w in self.listeners:
            w.write(self.pack({'MessageType': 'Attached', 'DeviceID': did,
                               'Properties': {'ConnectionType': 'USB', 'DeviceID': did, 'SerialNumber': '00008110-FAKE1234'}}, 0))

    async def detach(self, did=7):
        for w in self.listeners:
            w.write(self.pack({'MessageType': 'Detached', 'DeviceID': did}, 0))

    async def handle(self, reader, writer):
        try:
            head = await reader.readexactly(16)
            length, _, _, tag = struct.unpack('<IIII', head)
            msg = plistlib.loads(await reader.readexactly(length - 16))
            kind = msg.get('MessageType')
            if kind == 'Listen':
                writer.write(self.pack({'MessageType': 'Result', 'Number': 0}, tag))
                self.listeners.append(writer)
                await self.attach()                         # 订阅时设备已经插着
                await reader.read()                          # 保持到对方关闭
                return
            if kind == 'Connect':
                self.connects += 1
                port = socket.ntohs(msg['PortNumber'])
                if self.phone.refuse or port != 23334:
                    writer.write(self.pack({'MessageType': 'Result', 'Number': 3}, tag))   # 3 = 端口没人听
                    await writer.drain()
                    return
                writer.write(self.pack({'MessageType': 'Result', 'Number': 0}, tag))
                pr, pw = await asyncio.open_connection('127.0.0.1', self.phone.port)

                async def pipe(r, w):
                    try:
                        while True:
                            d = await r.read(65536)
                            if not d:
                                break
                            w.write(d)
                            await w.drain()
                    except (ConnectionError, OSError):
                        pass
                    finally:
                        w.close()
                await asyncio.gather(pipe(reader, pw), pipe(pr, writer))
        except (asyncio.IncompleteReadError, ConnectionError, OSError):
            pass
        finally:
            if writer in self.listeners:
                self.listeners.remove(writer)
            writer.close()


# ------------------------------------------------------------------ 测试
def api(path):
    with urllib.request.urlopen('http://127.0.0.1:%d%s' % (WEB_PORT, path), timeout=5) as r:
        return r.read()


async def until(cond, timeout=10.0, step=0.1):
    t = time.monotonic()
    while time.monotonic() - t < timeout:
        if cond():
            return True
        await asyncio.sleep(step)
    return False


def status():
    return json.loads(api('/api/status'))


async def main():
    phone = FakePhone()
    await phone.start()
    mux = FakeMux(phone)
    await mux.start()

    env = dict(os.environ, PYTHONIOENCODING='utf-8')
    proc = subprocess.Popen([sys.executable, SERVER, '--no-tray', '--no-browser', '--source', 'tone', '--mic-out', 'fake',
                             '--debug', '--no-bonjour', '--port', str(WEB_PORT), '--usbmux', '127.0.0.1:%d' % mux.port],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    loop = asyncio.get_running_loop()
    try:
        ok = await until(lambda: _http_ok(), 15)
        check('server started', ok)
        if not ok:
            return

        # 1. usbmuxd 在线，设备出现，但手机上没开声桥 → waiting
        ok = await until(lambda: status()['usb']['available'] and status()['usb']['devices'], 8)
        st = status()['usb']
        check('usbmuxd detected and device listed', ok, st)
        check('device waits while the phone app is closed', ok and st['devices'][0]['state'] == 'waiting', st)
        check('server keeps retrying Connect', await until(lambda: mux.connects >= 2, 6), mux.connects)

        # 2. 手机上打开声桥 → 自动连上
        phone.refuse = False
        check('connects by itself once the app listens', await until(lambda: phone.hello is not None, 8))
        check('hello carries the PC identity', phone.hello and phone.hello.get('type') == 'hello' and phone.hello.get('rate') == 48000, phone.hello)
        check('state becomes connected', await until(lambda: status()['usb']['devices'][0]['state'] == 'connected', 5))
        ls = status()['listeners']
        check('listener is marked as usb', len(ls) == 1 and ls[0].get('via') == 'usb', ls)

        # 3. 音频：连续、带掩码、约 100 帧/秒
        n0 = len(phone.audio)
        await asyncio.sleep(1.5)
        got = phone.audio[n0:]
        seqs = [s for s, _ in got]
        check('audio frames arrive ~100/s over USB', 120 <= len(got) <= 180, len(got))
        check('sequence numbers contiguous', all((b - a) & 0xFFFFFFFF == 1 for a, b in zip(seqs, seqs[1:])), seqs[:5])
        check('every PC->phone frame is masked (client frame)', phone.unmasked_from_pc == 0, phone.unmasked_from_pc)
        check('PC answers server ping with pong', await until(lambda: phone.pong, 3))

        # 4. 手机麦克风 → 电脑
        check('mic enabled via config', await until(lambda: status()['mic']['active'], 5), status()['mic'])
        await phone.send_mic_tone(3.0)
        await asyncio.sleep(0.5)
        wav = api('/api/debug/mic.wav')
        x = np.frombuffer(wav[44:], dtype='<i2').astype(np.float32) / 32768.0
        nz = np.nonzero(np.abs(x) > 0.05)[0]
        f, rms = 0.0, 0.0
        if len(nz) > 48000:
            seg = x[nz[0] + 4800: nz[-1] - 4800]
            spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
            f = np.argmax(spec) * 48000.0 / len(seg)
            rms = float(np.sqrt(np.mean(seg ** 2)))
        check('mic audio reached the PC (1 kHz, level kept)', abs(f - 1000) < 5 and abs(rms * np.sqrt(2) - 0.5) < 0.03,
              '%.1f Hz, amp %.3f' % (f, rms * np.sqrt(2)))

        # 5. 断开后自动重连
        s0 = phone.sessions
        phone.writer.close()
        check('listener disappears after the phone drops', await until(lambda: not status()['listeners'], 5))
        check('server reconnects by itself', await until(lambda: phone.sessions > s0 and phone.hello is not None and status()['usb']['devices'][0]['state'] == 'connected', 10))

        # 6. 拔线：设备消失，连接清掉
        await mux.detach()
        check('unplug removes the device', await until(lambda: not status()['usb']['devices'], 5), status()['usb'])
        check('unplug drops the session', await until(lambda: not status()['listeners'], 5))
    finally:
        proc.terminate()
        try:
            proc.wait(5)
        except subprocess.TimeoutExpired:
            proc.kill()
        if fails:
            print('--- server output ---')
            print(proc.stdout.read().decode('utf-8', 'replace')[-3000:])


def _http_ok():
    try:
        api('/api/status')
        return True
    except Exception:
        return False


asyncio.run(main())
print('%d failure(s)' % fails)
sys.exit(1 if fails else 0)
