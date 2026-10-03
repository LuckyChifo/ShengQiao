# 声桥 ShengQiao — 交接说明（给接手的 Claude Code）

Windows 电脑 ⇄ iPhone 的局域网双向音频：
1. **听电脑**：WASAPI 环回采集电脑正在播放的声音 → iPhone App（或 Safari 网页）实时播放，目标延迟约 0.1 s。
2. **当麦克风**：iPhone 麦克风 → 电脑端写进 VB-CABLE 的 `CABLE Input`，其他软件选 `CABLE Output` 当麦克风（功能参考 MicYou，代码独立实现，未复制其 GPL 代码）。
3. **降噪**（电脑端，可选 off / light / rnnoise / strong）和**回声消除**（iPhone 端系统语音处理）互相独立。

用户：中文沟通，Windows 笔记本（主力）+ MacBook Air（Apple Silicon，用 Xcode 装 App）+ iPhone，电脑装有 VB-CABLE。
用户界面文案全部中文、口语化。

## 当前状态（2026-10-03）

| 部分 | 状态 |
|---|---|
| 电脑端 Python（协议、抖动缓冲、降噪、Bonjour、控制页） | Linux 上全部测试通过 |
| 电脑端真实 WASAPI 采集 / VB-CABLE 写入 | **从未在 Windows 上跑过**。`pc/selftest.bat` 专门测这两条链路 |
| 托盘图标（pystray）、单实例、开机自启、桌面快捷方式 | **未在 Windows 上验证** |
| iPhone App（SwiftUI + C） | **Swift 从未编译过**（开发环境没有 Xcode），只做过 tree-sitter 语法检查和人工对照 API 审查。C 核心在 Linux 上测试通过（含 ThreadSanitizer） |
| Xcode 工程 | 由 `tools/gen_xcodeproj.py` 生成，pbxproj 解析器验证过结构；没在真 Xcode 里打开过 |
| GitHub Actions（`.github/workflows/`） | 写好了，**从未运行过**（没有仓库） |
| exe 打包（`pc/shengqiao.spec`，PyInstaller） | 没构建过；目前交付的是“便携 Python + start.bat”方案 |

**第一优先**：让用户在 Mac 上编译 iOS 工程、在 Windows 上跑 `selftest.bat`，按报错修。

## 目录

```
pc/
  app/server.py      HTTP + WebSocket 服务（手写，无框架）、Hub 广播、控制页 API、Bonjour、托盘模式入口 main()
  app/capture.py     采集：SoundcardSource（主，soundcard 库 WASAPI 环回）/ PyAudioSource（备用，连续失败 2 次自动切换）/ ToneSource（测试音）；CaptureRunner；log()
  app/mic.py         MicJitter（numpy 抖动缓冲 + 漂移补偿重采样）、MicSink（线程：jitter → denoise → WASAPI 输出）、SoundcardOutput、FakeOutput（测试用）
  app/denoise.py     降噪：SpectralSubtraction（light）、RNNoise（ctypes 调 lib/rnnoise.dll）、VoiceGate（strong）、Denoiser（线程安全切换）
  app/tray.py        pystray 托盘、注册表开机自启
  app/selftest.py    Windows 真机自检（VB-CABLE 回环录音、环回采集、网络、RNNoise 加载），结果写 selftest.txt
  app/web/           index.html（Safari 网页播放器）、player-core.js（网页版抖动缓冲）、pc.html（电脑端控制页）、图标、keepawake.mp4
  app/lib/           rnnoise.dll（Windows）、librnnoise.so（仅 Linux 测试用，不打进 Windows 包）、许可与版本说明
  start.bat          首次下载官方 embeddable Python 3.12.10（npmmirror → 华为云 → python.org，校验 PSF 签名）→ 建桌面快捷方式 → pythonw 启动；`start.bat debug` / `start.bat selftest`
  selftest.bat / debug.bat / firewall.bat
  tools/make_portable.py   组装 Windows 便携包（wheels 解压到 deps/，bat 转 CRLF，README 加 BOM）
  tests/             test_protocol.py、test_mic_core.py、test_denoise.py、test_denoise_e2e.py
ios/
  ShengQiao/Audio/sq_core.c/.h       C 实时音频核心：SQPlayer（无锁 SPSC 抖动缓冲，算法同 mic.py/player-core.js）、SQRing（麦克风环形缓冲）
  ShengQiao/Audio/AudioController.swift  AVAudioEngine：AVAudioSourceNode 播放、AVAudioSinkNode 收麦（失败退回 installTap）、语音处理（回声消除）、打断/路由变化重建
  ShengQiao/Model/AppModel.swift     状态机：连接/重连、配置下发、麦克风发送定时器（网络队列 5 ms）、锁屏控制、UserDefaults
  ShengQiao/Net/Connection.swift     Network.framework WebSocket（绕开 ATS；serviceClass = .interactiveVoice）
  ShengQiao/Net/Discovery.swift      NWBrowser 查 `_shengqiao._tcp`
  ShengQiao/UI/*.swift               ContentView（主界面）、ConnectView（选电脑/扫码/手动）、MeterView（Canvas 画 VU 表）、QRScannerView、Theme
  ShengQiao.xcodeproj                生成物；改了源文件列表后运行 `python3 tools/gen_xcodeproj.py`
  CoreTests/test_core.py / stress_threads.c   C 核心仿真测试 / TSAN 压测
tools/gen_xcodeproj.py、tools/make_icons.py
```

## 协议（WebSocket，默认端口 23333，被占用顺延到 +9）

- 握手：`/ws?k=访问码` 或任意路径 + 头 `X-SQ-Key`；错码 → close 4001。App 的 UA 含 `ShengQiao`。
- 电脑→手机二进制：`<BBHI>` = `(1, flags, 0, seq)` + 480 帧 48 kHz 双声道 int16（10 ms）。flags bit0 = 静音。
- 手机→电脑二进制：`(2, flags, 0, seq)` + 单声道 int16，采样率由 `config.micRate` 给出（电脑端重采样到 48 k）。
- 文本：`hello{rate,channels,name,id,version,features}`；`config{listen,mic,micRate,denoise}`（App 发；网页客户端不发，默认 listen=true）；
  `status{mic:{state,device,error,mine,busy,latencyMs,underruns,denoise,denoiseModes},listeners}` 每秒发给 App；`ping/pong`；`stats`。
- 同一时间只有一台手机的麦克风生效（后开的抢占，前一台收到 busy）。超过 10 s 没收到麦克风数据自动关闭。
- `/api/*` 和 `/pc` 只允许 127.0.0.1；`/api/check?k=` 对局域网开放。`--debug` 时有 `/api/debug/mic.wav`（FakeOutput 录音）。
- 配置：源码运行在 `pc/config.json`，exe 版在 `%APPDATA%\ShengQiao\`。字段 key、id、port、device、micDevice、denoise。

## 测试（Linux 上即可）

```bash
pip install numpy lameenc qrcode zeroconf          # 测试依赖
python3 pc/tests/test_mic_core.py                   # 麦克风抖动缓冲：漂移/抖动/卡顿/消费者停顿，零爆音
python3 pc/tests/test_denoise.py                    # 需 espeak-ng 合成语音（没有就用合成谐波）
python3 ios/CoreTests/test_core.py                  # 编译 sq_core.c 为 .so 后用 ctypes 跑 12 个场景
cc -std=c11 -O1 -fsanitize=thread ios/CoreTests/stress_threads.c ios/ShengQiao/Audio/sq_core.c -lpthread -lm && ./a.out
# 协议 / 端到端（另开服务器）：
python3 pc/app/server.py --no-tray --no-browser --source tone --mic-out fake --debug
python3 pc/tests/test_protocol.py <本机局域网IP> 23333 <config.json 里的 key>     # 设 SQ_TEST_BONJOUR=1 测 Bonjour
python3 pc/tests/test_denoise_e2e.py <IP> 23333 <key>
```
爆音检测统一用正弦递推残差 `|x[n] - 2cos(w)x[n-1] + x[n-2]|`，每个测试都有“硬跳变必须被检出”的自检。
注意：不要用 `pkill -f server.py` 之类匹配自身命令行的写法（会杀掉自己的 shell）。

## 踩过的坑（改代码前先看）

- **soundcard 的 COM 初始化**：import 时在当前线程 CoInitializeEx(MTA)。必须在主线程、任何其他 COM 调用之前创建采集源（`server.build()`）；其他线程靠隐式 MTA 使用。
- **RNNoise 版本**：必须用 0.2（GitHub release tarball 自带 `rnnoise_data.c`）。0.1.1 的旧模型对白噪声只压 1.4 dB 且误判为人声。DLL 用 `x86_64-w64-mingw32-gcc -O3 -shared`（SSE2 基线），只依赖 KERNEL32/msvcrt。RNNoise 输入是 int16 数值范围的 float，0.2 版延迟 20 ms；开头 20 帧静音预热。
- **频谱减法的噪声估计**会被开头的数字静音拉到 0 → 之后一分多钟不降噪。已修：静音帧不更新 + 噪声估计不低于功率 -30 dB（有回归测试）。
- **JSONSerialization 遇到 Optional/非法对象抛 ObjC 异常**（try? 拦不住）→ `Connection.sendJSON` 先 `isValidJSONObject`。
- **ATS**：用 Network.framework 的 NWConnection 而不是 URLSession，避免明文 ws:// 被拦。
- **iOS Safari 播 `<video>` 需要 Range 请求支持**（静态文件已实现 206）；网页版靠不静音、不带 loop 属性的视频防锁屏（WebKit 对 loop/静音视频不阻止休眠）。局域网 http 不是安全上下文：没有 Wake Lock、AudioWorklet、getUserMedia。
- **bat 文件必须 CRLF、纯 ASCII**；中文快捷方式名用 PowerShell `[char]0x58F0+[char]0x6865` 拼。zip 内文件名保持 ASCII（老版资源管理器会乱码）。
- **中文 Windows 老控制台**（新宋体）里方块字符双宽，二维码会变形 → 只在 Windows Terminal / UTF-8 代码页时打印。
- 抖动缓冲的设计要点：断流前留 FADE 个样本做储备，保证总能完整淡出；积压时交叉淡化跳跃；重新开始播放时直接从目标水位起步。改算法后三端（JS / C / numpy）要同步，并跑对应仿真。
- VB-CABLE 当麦克风时，**不能**把 CABLE Input 设为 Windows 默认输出（会形成回路），控制页有冲突提示。

## 待办 / 可能的下一步

1. 根据用户的 Xcode 报错和 `selftest.txt` 修 bug（最可能的问题：Swift 类型/并发诊断、AVAudioSinkNode 与语音处理的兼容性、soundcard 在特定声卡上的格式断言）。
2. 用户若创建 GitHub 私有仓库：推送后让 `.github/workflows/ios.yml`（xcodebuild 不签名编译）和 `windows.yml`（装 VB-CABLE 实测 + PyInstaller exe）跑起来，按日志修。
3. 可选：电脑端回声消除（像 MicYou 用环回信号做参考，处理电脑音箱外放被手机收进去的情况）；麦克风上行改 Opus 省带宽；App 内显示电脑端降噪的人声概率/电平。
4. 免费 Apple ID 签名 7 天过期，需要在 Xcode 里重新运行；Bundle ID `com.shengqiao.app.x7k2` 冲突时可改（`gen_xcodeproj.py --bundle-id`）。
