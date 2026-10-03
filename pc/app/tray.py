"""声桥 · Windows 托盘图标（右下角）。"""
import os
import sys
import threading
import webbrowser

from capture import log

RUN_KEY = r'Software\Microsoft\Windows\CurrentVersion\Run'
RUN_NAME = 'ShengQiao'


def launch_command():
    """开机自启时运行的命令：exe 版直接运行 exe；源码版用 pythonw 运行 server.py。"""
    if getattr(sys, 'frozen', False):
        return '"%s" --background' % sys.executable
    exe = sys.executable
    if exe.lower().endswith('python.exe'):
        w = exe[:-10] + 'pythonw.exe'
        if os.path.exists(w):
            exe = w
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'server.py')
    return '"%s" "%s" --background' % (exe, script)


def autostart_enabled():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, RUN_NAME)
            return True
    except OSError:
        return False


def set_autostart(on):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, RUN_NAME, 0, winreg.REG_SZ, launch_command())
        else:
            try:
                winreg.DeleteValue(k, RUN_NAME)
            except OSError:
                pass


def load_icon_image():
    from PIL import Image
    here = os.path.dirname(os.path.abspath(__file__))
    base = getattr(sys, '_MEIPASS', here)
    for p in (os.path.join(base, 'web', 'icon-180.png'), os.path.join(here, 'web', 'icon-180.png')):
        if os.path.exists(p):
            return Image.open(p).convert('RGBA').resize((64, 64))
    return Image.new('RGBA', (64, 64), (255, 181, 71, 255))


def run(app):
    """在主线程运行托盘图标，直到用户点“退出”。"""
    try:
        import pystray
    except Exception as e:
        log('托盘图标不可用（%s），声桥继续在后台运行；关闭方法：任务管理器结束 pythonw.exe' % e)
        threading.Event().wait()
        return

    def open_console(icon=None, item=None):
        webbrowser.open(app.console_url())

    def status_text(item):
        n = sum(1 for c in app.hub.ws if c.listen) + len(app.hub.mp3)
        mic = '，手机麦克风已接入' if app.mic.owner is not None else ''
        return ('%d 台手机在听%s' % (n, mic)) if (n or mic) else '等待手机连接'

    def toggle_autostart(icon, item):
        try:
            set_autostart(not autostart_enabled())
        except Exception as e:
            log('设置开机自启失败：%s' % e)

    def quit_app(icon, item):
        app.stopping = True
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem('打开声桥控制页', open_console, default=True),
        pystray.MenuItem(status_text, None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('开机时自动启动', toggle_autostart, checked=lambda item: autostart_enabled()),
        pystray.MenuItem('退出声桥', quit_app),
    )
    icon = pystray.Icon('ShengQiao', load_icon_image(), '声桥', menu)

    def refresher():
        # 让悬停提示和菜单里的状态跟着变
        import time
        while not app.stopping:
            try:
                icon.title = '声桥 · ' + status_text(None)
                icon.update_menu()
            except Exception:
                pass
            time.sleep(2)
    threading.Thread(target=refresher, daemon=True).start()
    icon.run()
