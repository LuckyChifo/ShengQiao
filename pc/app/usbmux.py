"""声桥 · USB 直连（usbmux）。

iPhone 插上电脑后，苹果的 usbmuxd 服务（Windows 上是 iTunes / “Apple 设备”App 带的 Apple Mobile Device Service，
监听 127.0.0.1:27015）能把电脑上的一条 TCP 连接转到 iPhone 的某个本机端口。不用 Wi-Fi，也不用开个人热点。

方向和 Wi-Fi 相反：iPhone 上的声桥 App 在本机回环地址 23334 端口监听（WebSocket 服务端），
电脑端主动经 usbmux 连过去，当 WebSocket 客户端。握手完成后，协议和 Wi-Fi 完全一样（hello / config / 音频帧），
电脑端仍然是“推音频的一方”，所以 server.py 里的会话循环两条链路共用。

usbmuxd 协议（plist 版）：16 字节头 <长度, 版本=1, 类型=8, 标签> + XML plist。
    Listen         订阅设备插拔事件（Attached / Detached）
    Connect        {DeviceID, PortNumber(网络字节序)}；回复 Result=0 后这条 socket 就是到设备端口的透明通道
"""
import asyncio
import base64
import hashlib
import os
import plistlib
import socket
import struct

from capture import log, IS_WINDOWS

PHONE_PORT = 23334
WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
PROG = 'ShengQiao'


def default_address():
    """usbmuxd 的地址：Windows 是本机 TCP 27015；mac / Linux 是 unix socket。可用环境变量 USBMUXD_SOCKET_ADDRESS 覆盖。"""
    env = os.environ.get('USBMUXD_SOCKET_ADDRESS')
    if env:
        return env
    return '127.0.0.1:27015' if IS_WINDOWS else '/var/run/usbmuxd'


async def _open(address):
    if address.startswith('/'):
        return await asyncio.open_unix_connection(address)
    host, _, port = address.rpartition(':')
    return await asyncio.open_connection(host or '127.0.0.1', int(port))


def _packet(msg, tag):
    body = plistlib.dumps(msg)
    return struct.pack('<IIII', 16 + len(body), 1, 8, tag) + body


async def _read_packet(reader):
    head = await reader.readexactly(16)
    length = struct.unpack_from('<I', head, 0)[0]
    body = await reader.readexactly(length - 16) if length > 16 else b''
    return plistlib.loads(body) if body else {}


def _base_msg(kind):
    return {'MessageType': kind, 'ClientVersionString': PROG, 'ProgName': PROG, 'kLibUSBMuxVersion': 3}


async def connect_device(address, device_id, port, timeout=3.0):
    """经 usbmux 连到设备的 port，成功返回 (reader, writer)，失败抛 OSError。"""
    reader, writer = await asyncio.wait_for(_open(address), timeout)
    try:
        msg = _base_msg('Connect')
        msg['DeviceID'] = device_id
        msg['PortNumber'] = socket.htons(port)
        writer.write(_packet(msg, 1))
        reply = await asyncio.wait_for(_read_packet(reader), timeout)
        if reply.get('Number', -1) != 0:
            raise ConnectionRefusedError('usbmux 拒绝了连接（%s）' % reply.get('Number'))
        return reader, writer
    except BaseException:
        writer.close()
        raise


async def ws_client_handshake(reader, writer, host='localhost'):
    """在已经连通的通道上做 WebSocket 客户端握手。"""
    key = base64.b64encode(os.urandom(16)).decode('ascii')
    writer.write(('GET /ws HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                  'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n'
                  'User-Agent: ShengQiao-PC/USB\r\n\r\n' % (host, key)).encode('ascii'))
    head = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 5)
    lines = head.decode('latin-1').split('\r\n')
    if ' 101 ' not in lines[0]:
        raise ConnectionError('手机端拒绝了 WebSocket 握手：%s' % lines[0])
    want = base64.b64encode(hashlib.sha1((key + WS_GUID).encode('ascii')).digest()).decode('ascii')
    headers = {ln.split(':', 1)[0].strip().lower(): ln.split(':', 1)[1].strip()
               for ln in lines[1:] if ':' in ln}
    if headers.get('sec-websocket-accept') != want:
        raise ConnectionError('WebSocket 握手校验不通过')


class UsbManager:
    """盯着 usbmuxd 里的 iPhone，一有设备就反复尝试连它的声桥 App，连上后交给 App.run_usb_session。

    status() 给控制页用：
        available  usbmuxd 服务是否在运行（没装 iTunes / Apple 设备 App 就是 False）
        devices    [{id, serial, state}]，state = 'connected'（已连上声桥）/ 'waiting'（手机上没开声桥）
    """

    def __init__(self, app, address=None):
        self.app = app
        self.address = address or default_address()
        self.available = False
        self.devices = {}              # DeviceID -> {'serial': str, 'state': str}
        self._tasks = {}
        self._stop = False

    # ---------------------------------------------------------- 状态
    def status(self):
        return {'available': self.available,
                'devices': [{'id': i, 'serial': d['serial'], 'state': d['state']} for i, d in sorted(self.devices.items())]}

    # ---------------------------------------------------------- 主循环
    async def run(self):
        while not self._stop:
            try:
                reader, writer = await asyncio.wait_for(_open(self.address), 3)
            except (OSError, asyncio.TimeoutError):
                self.available = False
                self._drop_all()
                await asyncio.sleep(4)
                continue
            try:
                writer.write(_packet(_base_msg('Listen'), 1))
                reply = await asyncio.wait_for(_read_packet(reader), 3)
                if reply.get('Number', -1) != 0:
                    raise ConnectionError('Listen 被拒绝')
                if not self.available:
                    log('检测到 USB 服务（usbmuxd），插上 iPhone 即可 USB 连接')
                self.available = True
                while True:
                    ev = await _read_packet(reader)
                    kind = ev.get('MessageType')
                    if kind == 'Attached':
                        props = ev.get('Properties') or {}
                        if props.get('ConnectionType', 'USB') == 'USB':          # 不管 Wi-Fi 同步的设备
                            self._attach(ev.get('DeviceID'), props.get('SerialNumber', ''))
                    elif kind == 'Detached':
                        self._detach(ev.get('DeviceID'))
            except (asyncio.IncompleteReadError, ConnectionError, OSError, asyncio.TimeoutError, ValueError):
                pass
            except Exception as e:
                log('USB 服务出错：%r' % (e,))
            finally:
                writer.close()
                self.available = False
                self._drop_all()
            await asyncio.sleep(2)

    def stop(self):
        self._stop = True
        self._drop_all()

    # ---------------------------------------------------------- 设备
    def _attach(self, did, serial):
        if did is None or did in self.devices:
            return
        self.devices[did] = {'serial': serial, 'state': 'waiting'}
        log('检测到 USB 设备（iPhone）%s' % (serial[-4:] if serial else ''))
        self._tasks[did] = asyncio.ensure_future(self._serve_device(did))

    def _detach(self, did):
        if self.devices.pop(did, None) is not None:
            log('USB 设备已拔出')
        t = self._tasks.pop(did, None)
        if t:
            t.cancel()

    def _drop_all(self):
        for did in list(self.devices):
            self._detach(did)

    async def _serve_device(self, did):
        """对一台 iPhone：反复尝试连它的声桥 App；连上就跑会话，断了再试。"""
        try:
            while did in self.devices:
                try:
                    reader, writer = await connect_device(self.address, did, PHONE_PORT)
                except (OSError, asyncio.TimeoutError):
                    self.devices[did]['state'] = 'waiting'          # 手机上声桥没开（端口没人听）
                    await asyncio.sleep(1.5)
                    continue
                try:
                    await ws_client_handshake(reader, writer)
                except (OSError, ConnectionError, asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
                    writer.close()
                    await asyncio.sleep(1.5)
                    continue
                if did in self.devices:
                    self.devices[did]['state'] = 'connected'
                try:
                    await self.app.run_usb_session(reader, writer, self.devices.get(did, {}).get('serial', ''))
                finally:
                    try:
                        writer.close()
                    except Exception:
                        pass
                if did in self.devices:
                    self.devices[did]['state'] = 'waiting'
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            pass
