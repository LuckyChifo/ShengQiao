"""USB adapter detection, pairing changes and local HTTP access control."""
import asyncio
import ipaddress
import json
import os
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
import netinfo
import server


def adapter(desc, ip='192.168.42.150', prefix=24, up=True):
    return {'name': 'Ethernet 2', 'desc': desc, 'type': 6, 'up': up, 'ips': [ip],
            'networks': [str(ipaddress.ip_network('%s/%d' % (ip, prefix), strict=False))]}


class UsbNetworkTests(unittest.TestCase):
    def test_phone_drivers_and_non_phone_adapters(self):
        for name in ['Remote NDIS Compatible Device', 'SAMSUNG Mobile USB Remote NDIS Network Device',
                     'USB RNDIS6 Adapter', 'Remote NDIS based Internet Sharing Device',
                     'USB NCM', 'Android USB Ethernet']:
            with self.subTest(name=name):
                self.assertTrue(netinfo.is_android_usb(adapter(name)))
        for name in ['Realtek USB GbE Family Controller', 'Intel Ethernet',
                     'Apple Mobile Device Ethernet', 'Bluetooth Device (Personal Area Network)']:
            with self.subTest(name=name):
                self.assertFalse(netinfo.is_android_usb(adapter(name)))

    def test_active_adapters_valid_addresses_and_real_prefixes(self):
        fixtures = [adapter('USB RNDIS', prefix=25),
                    adapter('Apple Mobile Device Ethernet', '172.20.10.2', 28),
                    adapter('USB NCM', '169.254.1.2', 16),
                    adapter('USB RNDIS', up=False), adapter('Realtek USB GbE')]
        with patch.object(netinfo, 'adapters', return_value=fixtures):
            result = netinfo.usb_network_adapters()
        self.assertEqual(len(result), 3)
        self.assertEqual(result[0]['networks'], ['192.168.42.128/25'])
        self.assertEqual(result[1]['platform'], 'iphone')
        self.assertEqual(result[2]['ips'], [])
        self.assertEqual(result[2]['networks'], [])


class PairingTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config_path = os.path.join(self.temp.name, 'config.json')
        patcher = patch.object(server, 'CONFIG_PATH', self.config_path)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.cfg = {'key': 'old234', 'id': 'test-pairing', 'port': 23333,
                    'device': 'custom-device', 'denoise': 'light'}
        server.save_config(self.cfg)
        self.app = server.App(server.parse_args(['--no-bonjour', '--no-usb']), self.cfg,
                              None, server.micmod.FakeOutput())
        self.app.port = 23333
        self.adapters = patch.object(netinfo, 'adapters', return_value=[adapter('USB RNDIS')])
        self.adapters.start()
        self.addCleanup(self.adapters.stop)
        ips = patch.object(server, 'candidate_ips', return_value=['10.0.0.10'])
        ips.start()
        self.addCleanup(ips.stop)
        self.app.refresh_urls()

    async def request(self, path, data=None, local=True, method='POST', headers=None, raw=None):
        body = raw if raw is not None else json.dumps(data or {}).encode()
        request_headers = {'host': '127.0.0.1:%d' % self.app.port}
        request_headers.update(headers if headers is not None else {'content-type': 'application/json'})
        response = await self.app.route(method, path, {}, body, local, request_headers)
        head, body = response.split(b'\r\n\r\n', 1)
        status = int(head.split(b' ')[1])
        try:
            result = json.loads(body)
        except ValueError:
            result = body.decode()
        return status, result

    async def test_manual_key_persists_and_invalidates_old_network_sessions(self):
        network = SimpleNamespace(via='wifi', writer=Mock())
        usb = SimpleNamespace(via='usb', writer=Mock())
        mp3 = SimpleNamespace(writer=Mock())
        self.app.hub.ws = [network, usb]
        self.app.hub.mp3 = [mp3]
        status, result = await self.request('/api/access-key', {'action': 'set', 'key': '  MyCode2026  '})
        self.assertEqual(status, 200)
        self.assertEqual(result['key'], 'mycode2026')
        self.assertTrue(self.app.key_ok('MYCODE2026'))
        self.assertFalse(self.app.key_ok('old234'))
        self.assertTrue(all('?k=mycode2026' in url for url in result['urls'] + result['usb']['netUrls']))
        self.assertEqual(server.load_config()['key'], 'mycode2026')
        self.assertEqual(server.load_config()['device'], 'custom-device')
        network.writer.close.assert_called_once()
        mp3.writer.close.assert_called_once()
        usb.writer.close.assert_not_called()
        status, _ = await self.request('/api/check', method='GET', headers={'x-sq-key': 'old234'}, local=False)
        self.assertEqual(status, 403)
        status, _ = await self.request('/api/check', method='GET', headers={'x-sq-key': 'MYCODE2026'}, local=False)
        self.assertEqual(status, 200)

    async def test_generation_changes_code_and_same_code_is_noop(self):
        status, result = await self.request('/api/access-key', {'action': 'generate'})
        self.assertEqual(status, 200)
        self.assertNotEqual(result['key'], 'old234')
        self.assertEqual(len(result['key']), 6)
        status, same = await self.request('/api/access-key', {'action': 'set', 'key': result['key']})
        self.assertFalse(same['changed'])

    async def test_validation_rejects_bad_input_without_changing_config(self):
        for value in ['', '123', 'x' * 17, 'abc def', '中文密码', 'abcd&k=x', 123456, None]:
            with self.subTest(value=value):
                status, _ = await self.request('/api/access-key', {'action': 'set', 'key': value})
                self.assertEqual(status, 400)
        for body in [b'[]', b'null', b'{', b'\xff']:
            status, _ = await self.request('/api/access-key', raw=body)
            self.assertEqual(status, 400)
        self.assertEqual(server.load_config()['key'], 'old234')

    async def test_failed_atomic_save_preserves_current_code_and_file(self):
        with patch.object(server.os, 'replace', side_effect=OSError('read only')):
            status, _ = await self.request('/api/access-key', {'action': 'set', 'key': 'abcd1234'})
        self.assertEqual(status, 500)
        self.assertEqual(self.app.cfg['key'], 'old234')
        self.assertEqual(server.load_config()['key'], 'old234')
        self.assertEqual(os.listdir(self.temp.name), ['config.json'])

    async def test_mutations_are_local_json_post_only(self):
        for path in ['/api/access-key', '/api/refresh']:
            status, _ = await self.request(path, {'action': 'generate'}, local=False)
            self.assertEqual(status, 403)
            status, _ = await self.request(path, method='GET')
            self.assertEqual(status, 405)
            status, _ = await self.request(path, headers={'content-type': 'text/plain'})
            self.assertEqual(status, 415)
            status, _ = await self.request(path, headers={'content-type': 'application/json', 'origin': 'https://example.com'})
            self.assertEqual(status, 403)
        self.assertEqual(self.app.cfg['key'], 'old234')

    async def test_local_control_rejects_untrusted_requests_before_dispatch(self):
        paths = ('/api/device', '/api/mic-device', '/api/denoise', '/api/chime',
                 '/api/access-key', '/api/refresh')
        cases = (
            ({'origin': 'https://untrusted.example', 'content-type': 'text/plain'}, 403),
            ({'origin': 'https://untrusted.example', 'content-type': 'application/json'}, 403),
            ({'origin': 'null', 'content-type': 'application/json'}, 403),
            ({'origin': '', 'content-type': 'application/json'}, 403),
            ({'origin': 'http://localhost:23333', 'content-type': 'application/json'}, 403),
            ({'origin': 'http://127.0.0.1:23334', 'content-type': 'application/json'}, 403),
            ({'host': 'untrusted.example:23333', 'content-type': 'application/json'}, 403),
            ({'host': '', 'content-type': 'application/json'}, 403),
            ({'content-type': 'text/plain'}, 415),
            ({}, 415),
        )
        with patch.object(self.app, 'api', return_value=server.json_response({'ok': True})) as api:
            for path in paths:
                for headers, expected in cases:
                    with self.subTest(path=path, headers=headers):
                        status, _ = await self.request(path, headers=headers)
                        self.assertEqual(status, expected)
                status, _ = await self.request(path, local=False)
                self.assertEqual(status, 403)
            api.assert_not_awaited()

    async def test_untrusted_host_cannot_read_local_console_or_status(self):
        for path in ('/pc', '/api/status', '/api/qr.svg'):
            with self.subTest(path=path):
                status, _ = await self.request(path, method='GET', headers={'host': 'untrusted.example:23333'})
                self.assertEqual(status, 403)

    async def test_chime_accepts_same_origin_and_native_json_posts(self):
        with patch.object(self.app.hub, 'play_chime') as play_chime:
            for host in ('127.0.0.1:23333', 'localhost:23333', '[::1]:23333'):
                with self.subTest(host=host):
                    status, result = await self.request('/api/chime', headers={
                        'host': host, 'origin': 'http://' + host, 'content-type': 'Application/JSON; charset=utf-8',
                    })
                    self.assertEqual((status, result), (200, {'ok': True}))
            status, result = await self.request('/api/chime')
            self.assertEqual((status, result), (200, {'ok': True}))
            self.assertEqual(play_chime.call_count, 4)

    async def test_default_http_port_accepts_implicit_authority(self):
        self.app.port = 80
        for host in ('localhost', 'localhost:80'):
            with self.subTest(host=host):
                status, result = await self.request('/api/chime', headers={
                    'host': host, 'origin': 'http://localhost', 'content-type': 'application/json',
                })
                self.assertEqual((status, result), (200, {'ok': True}))

    async def test_duplicate_control_headers_are_rejected_on_http_connection(self):
        http_server = await asyncio.start_server(self.app.handle, '127.0.0.1', 0)
        async with http_server:
            self.app.port = http_server.sockets[0].getsockname()[1]
            authority = f'127.0.0.1:{self.app.port}'
            cases = (
                ('Host: untrusted.example', 403),
                ('Origin: https://untrusted.example', 403),
                ('Content-Type: text/plain', 415),
            )
            with patch.object(self.app.hub, 'play_chime') as play_chime:
                for header, expected in cases:
                    with self.subTest(header=header):
                        reader, writer = await asyncio.open_connection('127.0.0.1', self.app.port)
                        try:
                            writer.write((f'POST /api/chime HTTP/1.1\r\n{header}\r\n'
                                          f'Host: {authority}\r\nOrigin: http://{authority}\r\n'
                                          'Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}').encode('ascii'))
                            await writer.drain()
                            response = await asyncio.wait_for(reader.read(), 5)
                            self.assertEqual(int(response.split(b' ')[1]), expected)
                        finally:
                            writer.close()
                            await writer.wait_closed()
                play_chime.assert_not_called()

    async def test_no_key_mode_is_explicit(self):
        self.app.args.no_key = True
        status, _ = await self.request('/api/access-key', {'action': 'generate'})
        self.assertEqual(status, 409)
        status, result = await self.request('/api/refresh')
        self.assertEqual(result['key'], '')
        self.assertNotIn('?k=', result['usb']['netUrls'][0])

    async def test_refresh_includes_usb_addresses_and_unplug_removes_them(self):
        self.app.advertiser = Mock()
        status, result = await self.request('/api/refresh')
        self.assertEqual(status, 200)
        self.assertTrue(result['usb']['net'])
        self.assertEqual(result['usb']['netAdapters'][0]['platform'], 'android')
        self.assertIn('192.168.42.150', result['usb']['netUrls'][0])
        self.assertTrue(self.app.is_usb_net_ip('192.168.42.129'))
        self.assertFalse(self.app.is_usb_net_ip('192.168.43.129'))
        self.assertFalse(self.app.is_usb_net_ip('USB'))
        advertised = self.app.advertiser.update.call_args.args[0]
        self.assertIn('192.168.42.150', advertised)
        with patch.object(netinfo, 'adapters', return_value=[]):
            _, result = await self.request('/api/refresh')
        self.assertFalse(result['usb']['net'])
        self.assertEqual(result['usb']['netUrls'], [])
        self.assertFalse(self.app.is_usb_net_ip('192.168.42.129'))
        self.assertEqual(result['key'], 'old234')


if __name__ == '__main__':
    unittest.main()
