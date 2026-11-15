"""WebSocket fragmentation, message limits and malformed input regressions."""
import asyncio
import os
import sys
import unittest
from types import SimpleNamespace

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
import server
import usbmux


def frame(op, data, fin=True):
    result = bytearray(server.ws_frame(op, data))
    if not fin:
        result[0] &= 0x7f
    return bytes(result)


class ReaderTests(unittest.IsolatedAsyncioTestCase):
    def reader(self, data):
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        return server.WSMessageReader(reader)

    async def test_ping_between_fragments_preserves_message(self):
        reader = self.reader(frame(1, b'{"type":', False) + frame(9, b'ping')
                             + frame(0, b'"config"}') + frame(1, b'next'))
        self.assertEqual(await reader.read(), (9, b'ping'))
        self.assertEqual(await reader.read(), (1, b'{"type":"config"}'))
        self.assertEqual(await reader.read(), (1, b'next'))

    async def test_total_fragment_size_is_bounded(self):
        reader = self.reader(frame(2, bytes(200000), False) + frame(0, bytes(100000)))
        with self.assertRaisesRegex(ValueError, 'message too large'):
            await reader.read()

    async def test_invalid_frames_are_rejected(self):
        for data in (frame(0, b'orphan'), frame(9, b'ping', False),
                     frame(9, bytes(126)), frame(1, b'a', False) + frame(2, b'b')):
            with self.subTest(data=data[:8]), self.assertRaises(ValueError):
                await self.reader(data).read()

    async def test_usb_handshake_requires_accept_header(self):
        reader = asyncio.StreamReader()
        reader.feed_data(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n')
        reader.feed_eof()
        with self.assertRaises(ConnectionError):
            await usbmux.ws_client_handshake(reader, SimpleNamespace(write=lambda _: None))

    def test_non_object_json_is_ignored(self):
        for value in (b'[]', b'null', b'42', b'"hello"'):
            server.App.on_ws_text(None, None, value)


if __name__ == '__main__':
    unittest.main()
