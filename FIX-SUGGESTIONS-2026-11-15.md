# 声桥修复交接 · 2026-11-15

请基于当前工作区修复以下三项。优先处理 iOS 的连接和录音收尾，再修复 Windows 自检的退出码。背景见 [当天审查记录第五轮](REVIEW-2026-11-15.md)。

## 1. iOS 切回前台后仍应等待新访问码

涉及 [AppModel.swift](ios/ShengQiao/Model/AppModel.swift#L633)。

收到 `.badKey` 时，现有代码会取消重连、进入 `.needKey`、停止音频，并保留 `target` 供用户重新连接。`enteredBackground()` 随后会把有目标的连接改成 `.failed("App 在后台")`。回到前台时，`becameActive()` 就会用旧码发起 Wi-Fi 连接。

建议在 `enteredBackground()` 中保留 `.needKey`，同时照常清理网络连接与发现任务。保留现有目标和重新输入访问码的入口。普通 Wi-Fi 连接进入后台后，回前台仍应按原逻辑恢复。

验收时，先在手机上开麦，再在电脑端改访问码并让手机用旧码重连。确认收到拒绝后停止录音。切到后台再回来，仍显示访问码错误，电脑端没有新的旧码连接尝试。输入新码后能正常连接。另测正常 Wi-Fi 连接在前后台切换后的恢复。

## 2. iOS 仅用 USB 时要能主动断开，并在拔线后停止音频

涉及 [ConnectView.swift](ios/ShengQiao/UI/ConnectView.swift#L117) 和 [AppModel.swift](ios/ShengQiao/Model/AppModel.swift#L261)。

连接页只在 `model.hasTarget` 为真时显示“断开连接”，而 USB 接管只设置 `viaUSB`，可以没有 Wi-Fi 目标。仅用 USB 时，关掉 USB 开关或拔线都会进入 `usbLost()`。其中无 Wi-Fi 目标的分支只将连接状态设为空闲，播放和录音可能继续运行。

建议将按钮显示条件改为 `model.hasTarget || model.viaUSB`，继续调用现有 `disconnect()`。在 `usbLost()` 的无 Wi-Fi 目标分支复用 `stopAudio()`，一并取消待处理的麦克风权限请求、停止实际录音和播放，并更新锁屏播放状态。有 Wi-Fi 目标时保留现有的拔线回退和音频恢复行为。

验收时，用没有 Wi-Fi 目标的手机通过 USB 连接，同时打开“听电脑”和“当麦克风”。分别测试点“断开连接”、关闭 USB 开关、拔线，确认都回到空闲，两个音频开关复位且实际录音和播放停止。主动断开后保持插线并切换前后台，确认不会自行重连。将 USB 直连开关关掉再打开，确认能恢复连接。另测有 Wi-Fi 目标时拔线，确认仍能回到 Wi-Fi。

## 3. Windows 自检应传回 Python 的退出码

涉及 `start.bat` 的 [便携分支](pc/start.bat#L41) 和 [备用分支](pc/start.bat#L113)。自检退出逻辑见 [selftest.py](pc/app/selftest.py#L210)。

已在隔离环境确认 `.venv` 备用路径会执行 `selftest.py`，自检报告失败并返回 `1` 时，批处理仍返回 `0`。便携 Python 路径也写死了 `exit /b 0`。

建议在两个自检分支中，于 Python 退出后立即用 `set "SELFTEST_EXIT=%ERRORLEVEL%"` 保存退出码，在 `pause` 后用 `exit /b %SELFTEST_EXIT%` 返回。保留双击运行时的暂停和报告显示。

验收时，用隔离副本分别覆盖便携和 `.venv` 两种启动路径。让测试用的 `selftest.py` 分别返回 `0` 和 `1`，核对 `start.bat selftest` 在四种组合下返回相同的退出码，并确认没有启动 `server.py`。

## 回报验证结果

请在审查记录补上实际修改、测试结果及待验证项。iOS 的前后台、USB 和录音收尾需要 Xcode 编译与 iPhone 真机验证。本轮在 Windows 上完成了源码复核和启动分支的隔离测试。
