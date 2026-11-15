# 声桥 ShengQiao

Windows 电脑和手机之间的双向局域网音频：

- **两种连接方式**：Wi-Fi（同一局域网），或 **USB 数据线**（iPhone 直连、安卓/iPhone 网络共享）。电脑「设备」页提供 USB 连接说明和刷新检测。
- **听电脑**：电脑正在播放的声音（WASAPI 环回采集）实时传到 iPhone，延迟约 0.1 秒；App 锁屏也能继续播放。
- **当麦克风**：iPhone 麦克风传到电脑，写进 VB-CABLE 的 CABLE Input，任何软件选 CABLE Output 即可当麦克风用
  （思路参考 [MicYou](https://github.com/LanRhyme/MicYou)，代码独立实现）。

| 目录 | 内容 |
|---|---|
| `pc/` | 电脑端（Python）：采集、推流、麦克风接收、Bonjour 广播、托盘图标、控制页、网页版播放器。说明见 `pc/README-Windows.md` |
| `ios/` | iPhone App（SwiftUI + C 音频核心）。安装说明见 `ios/README.md` |
| `android/` | Android App（原生 Java）：低延迟双向音频、自动发现、息屏前台服务。说明见 `android/README.md` |
| `tools/` | 生成图标、生成 Xcode 工程 |
| `.github/workflows/` | 云端编译：Windows exe（含装 VB-CABLE 后的真机音频自检）、iOS 不签名编译 |

## 协议（WebSocket，端口 23333）

- 连接：`ws://电脑IP:23333/ws?k=访问码`，或任意路径 + 请求头 `X-SQ-Key: 访问码`（App 用 Bonjour 端点连接时）。访问码错误 → 关闭码 4001。
- 电脑 → 手机：二进制帧 `[codec][flags][0][0][seq u32 LE]` + 10 ms 音频。codec 1 = 48 kHz 双声道 16 位 PCM；codec 3 = Opus（64/128/256/320 kbps）。
- 手机 → 电脑：二进制帧 `[2][flags][0][0][seq u32 LE]` + 单声道 16 位 PCM（采样率在 config 里给出）。
- 文本消息：`hello`（电脑名、id、采样率）、`config`（`listen`、`mic`、`micRate`、`denoise`、`deviceName`、`bitrate`）、`status`（麦克风输出状态，每秒一次）、`ping`/`pong`、`stats`。
- Bonjour 服务类型 `_shengqiao._tcp`，TXT：`id`（电脑固定 id）、`name`（电脑名）。

## USB 连接

**安卓**：插上数据线，在手机设置里打开「USB 网络共享」，无需 USB 调试或 iTunes。在电脑「设备」页点击「刷新检测 → 查看 USB 共享地址」，再在安卓 App 中手动填写对应 IP、端口和访问码，或选择自动发现的电脑。电脑端识别常见 RNDIS / USB NCM 网卡，并根据实际网段标记「USB 共享」。

首页「连接手机」支持刷新连接信息、手动设置访问码（4–16 位英文字母或数字）以及随机生成新码。新码会持久保存，二维码和链接同步更新；网络连接的设备需用新码重新连接。

iPhone 有两种做法，电脑端自动识别，控制页的“USB 连接”卡片会说明当前是哪种：

1. **USB 直连（usbmux）**：不用 Wi-Fi，也不用开个人热点。电脑装有 iTunes 或“Apple 设备”App（带 Apple Mobile Device Service，监听 127.0.0.1:27015）即可。
   方向和 Wi-Fi 相反：手机上的 App 在本机回环地址 `23334` 端口监听（WebSocket 服务端，只绑回环接口，所以不需要访问码），
   电脑端 `pc/app/usbmux.py` 经 usbmuxd 的 `Connect` 连过去，当 WebSocket 客户端（帧带掩码位，掩码键全 0）。握手后协议和 Wi-Fi 完全一样，`server.py` 的会话循环共用。
   拔线、手机上没开声桥时，电脑端每 1.5 秒重试，不用手动操作。手机同时连着 Wi-Fi 时 USB 优先，USB 断了自动回到 Wi-Fi。
2. **USB 共享网络**：iPhone 开“个人热点”再插线，电脑多出一张 “Apple Mobile Device Ethernet” 网卡（`pc/app/netinfo.py` 认它），
   手机在 172.20.10.1，电脑在 172.20.10.x，现有的 Wi-Fi 协议原样跑在这条线上。控制页给出这张网卡上的地址和二维码，手机列表里标成“USB 共享”。

测试：`python3 pc/tests/test_usb.py`（假 usbmuxd + 假手机，不需要真设备）。

## 降噪与回声消除

- 降噪在电脑端（`pc/app/denoise.py`），48 kHz、每帧 10 ms，可选：关闭 / 轻度（频谱减法，最小值跟踪噪声估计）/
  RNNoise（[xiph/rnnoise](https://github.com/xiph/rnnoise) 0.2 正式版，BSD 许可，`pc/app/lib/` 里是用 MinGW 从发布包源码编译的 DLL，
  见 `RNNOISE-VERSION.txt`）/ 强力（RNNoise + 基于人声概率的噪声门）。App 用 `config.denoise` 选择，`status.mic.denoise` 回报。
- 回声消除在 iPhone 端：AVAudioEngine 输入节点的系统语音处理（`setVoiceProcessingEnabled`），参考信号就是 App 自己播放的电脑声音。
- 测试：`python3 pc/tests/test_denoise.py`（合成语音 + 白噪声/风扇/键盘，量停顿压低多少、人声保留多少），
  `pc/tests/test_denoise_e2e.py`（经网络、抖动缓冲到虚拟声卡的完整链路）。

## 抖动缓冲

三端（网页 `player-core.js`、iOS `sq_core.c`、电脑端 `mic.py`）是同一套算法：自适应目标水位（卡顿就加 25 ms，稳定 15 秒再收回），
±0.5% 变速补偿两边声卡的时钟漂移（Catmull-Rom 插值），积压过多时交叉淡化跳到最新，断流前留 FADE 个样本做完整淡出。

## 测试

```
python3 pc/tests/test_mic_core.py               # 电脑端麦克风抖动缓冲仿真
python3 ios/CoreTests/test_core.py              # iOS C 核心仿真（编译成动态库，ctypes 驱动）
cc -std=c11 -fsanitize=thread ios/CoreTests/stress_threads.c ios/ShengQiao/Audio/sq_core.c -lpthread -lm && ./a.out
python3 pc/app/server.py --no-tray --source tone --mic-out fake --debug   # 然后：
python3 pc/tests/test_protocol.py <电脑IP> 23333 <访问码>
```

Windows 上：`pc/selftest.bat` 用真实声卡检查采集和 VB-CABLE 麦克风链路。
