import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
import server


class DeviceNameTests(unittest.TestCase):
    def test_app_platforms(self):
        cases = {
            'ShengQiao/2.0 (Android)': 'Android（App）',
            'ShengQiao/2.0 (iPhone; iOS 18.0)': 'iPhone（App）',
            'ShengQiao/2.0 (iPad; iPadOS)': 'iPad（App）',
            'ShengQiao/2.0': '声桥 App',
        }
        for user_agent, expected in cases.items():
            with self.subTest(user_agent=user_agent):
                self.assertEqual(server.short_ua(user_agent), expected)

    def test_browser_platforms(self):
        cases = {
            'Mozilla/5.0 (Linux; Android 15)': 'Android',
            'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)': 'iPhone',
            'Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X)': 'iPad',
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0)': 'Mac',
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64)': 'Windows',
            'Mozilla/5.0 (X11; Linux x86_64)': 'Linux',
        }
        for user_agent, expected in cases.items():
            with self.subTest(user_agent=user_agent):
                self.assertEqual(server.short_ua(user_agent), expected)

    def test_reported_device_name(self):
        cases = {
            '小明的荣耀 Magic7': '小明的荣耀 Magic7',
            '  小明的\niPhone  ': '小明的 iPhone',
            '   ': 'iPhone（App）',
        }
        for reported, expected in cases.items():
            with self.subTest(reported=reported):
                self.assertEqual(server.client_name('ShengQiao/2.0 (iPhone; iOS)', reported), expected)
        self.assertEqual(server.client_name('ShengQiao/2.0 (Android)', 'x' * 100), 'x' * 80)
        self.assertEqual(server.client_name('ShengQiao/2.0 (Android)', None), 'Android（App）')

    def test_unknown_device(self):
        self.assertEqual(server.short_ua(''), '未知设备')
        self.assertEqual(server.short_ua(None), '未知设备')


if __name__ == '__main__':
    unittest.main()
