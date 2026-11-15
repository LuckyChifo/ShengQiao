# 声桥 · Mac 端交接（给在 Mac 上接手的 Claude Code）

先读同目录的 `CLAUDE.md`（项目全貌、协议、踩过的坑）。这份文件只讲 **Mac 上要做的事**。
用户说中文，界面文案全部中文、口语化。

## 背景：为什么轮到 Mac

项目在 Windows 笔记本上开发，那里**没有 Xcode，也没有 iPhone**。所以：

- 电脑端（Python）在 Windows 真机上跑通过：WASAPI 采集、托盘、客户端窗口、exe 打包。
- **iPhone App 的 Swift 代码从来没被编译过**，只做过 tree-sitter 语法检查和人工对照 API。
- 最近新增了 **USB 直连**（iPhone 经数据线，电脑经 usbmux 连进手机）。电脑端用“假 usbmuxd + 假手机”测通了，
  **手机端（`UsbListener.swift`、`Connection.adopt`、`AppModel` 里的 `viaUSB`）完全没验证过**。

Mac 的价值：能编译、能连真 iPhone，而且 **Mac 自带 usbmuxd**，可以不依赖 Windows 就把 USB 直连整条链路测一遍（见第 3 步）。

## 0. 拿到代码

整个 `ShengQiao/` 文件夹拷到 Mac（包括 `.git`）。Windows 上有**未提交的改动**（USB 直连、客户端窗口、新界面等），
`git status` 会看到一堆 `M` 和几个 `??` 新文件，这是正常的，不要 `git checkout .` / `git reset`。
Mac 上改完的东西请**提交一个 commit**（或打成补丁），方便带回 Windows。

```bash
cd ShengQiao
git status --short           # 看一眼未提交的改动，别丢
pip3 install numpy lameenc qrcode zeroconf    # 测试依赖
```

## 1. 先确认 C 核心没问题（几分钟）

```bash
python3 ios/CoreTests/test_core.py
cc -std=c11 -O1 -fsanitize=thread ios/CoreTests/stress_threads.c ios/ShengQiao/Audio/sq_core.c -lpthread -lm && ./a.out
```

在 Linux 上通过过。Mac 上通过就说明 `sq_core.c` 在 Apple clang 下没问题。

## 2. 编译 iOS 工程（最重要）

不签名编译，用来快速看全部报错（CI 里也是这条命令）：

```bash
xcodebuild -project ios/ShengQiao.xcodeproj -scheme ShengQiao -configuration Debug \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build 2>&1 | grep -E "error:|warning: .*(Sendable|actor)|BUILD" | head -60
```

要点：

- 部署目标 iOS 16.0，`SWIFT_VERSION = 5.0`（不是 Swift 6 严格并发，别升）。
- 加/删 Swift 文件后运行 `python3 tools/gen_xcodeproj.py` 重新生成工程（`--bundle-id` 可改包名，默认 `com.shengqiao.app.x7k2`）。
  **不要手改 `project.pbxproj`**。
- **按报错逐个修。** 最可能出问题的地方（因为从没编译过）：
  - `Audio/AudioController.swift`：`AVAudioSinkNode` 与 `setVoiceProcessingEnabled` 同时用的兼容性、`AVAudioSourceNode` 渲染块里对 C 函数的调用、打断/路由变化重建。
  - `Model/AppModel.swift`：主线程 / `connection.queue` 两个线程之间的数据访问、`@Published` 属性的 `didSet`（`usbEnabled`、`echoCancel` 等）在 `init` 里的初始化顺序。
  - `Net/Connection.swift`：`NWProtocolWebSocket` 元数据读取（`context.protocolMetadata(definition:)`）、`closeCode` 的 `.privateCode` 匹配。
  - `UI/ConnectView.swift`：`Toggle(isOn: $model.usbEnabled)`（`@EnvironmentObject` 的绑定语法）、SF Symbol `cable.connector`（不存在就换成 `cable.connector.horizontal` 或 `bolt.horizontal`）。
- 修的时候保持现有风格：注释和界面文案用中文，不要顺手重构。

## 3. 用真 iPhone 把 USB 直连测通（这次新功能的核心验证）

### 3.1 在 Mac 上直接跑电脑端当“电脑”

`pc/app/usbmux.py` 在非 Windows 上默认连 `/var/run/usbmuxd`（Mac 自带，不用装 iTunes）。
电脑端的采集/托盘/客户端窗口只在 Windows 用，Mac 上用测试音源即可：

```bash
python3 pc/app/server.py --no-tray --no-browser --source tone --mic-out fake --debug
# 看日志里有没有：检测到 USB 服务（usbmuxd） / 检测到 USB 设备（iPhone）
```

然后在另一个终端随时看状态（只能本机访问）：

```bash
curl -s http://127.0.0.1:23333/api/status | python3 -c "import sys,json; s=json.load(sys.stdin); print(s['usb']); print(s['listeners'])"
```

### 3.2 手机端

1. Xcode 里签名（Team 选个人 Apple ID，Bundle ID 冲突就改末尾字母），连 iPhone，点“信任此电脑”，运行 ▶︎。
2. **App 保持在前台**。电脑端的 USB 状态应从 `waiting` 变成 `connected`，日志出现 `iPhone（App） USB 连上了`，
   App 顶部显示“电脑名 · USB”，打开“听电脑”能听到测试音（叮咚旋律）。
3. 打开“当麦克风”，对手机说话/出声，`/api/debug/mic.wav`（`--debug` 时有）里应能录到。
4. 拔线 → App 回到空闲/重连；插回 → 自动重新连上，不用手动操作。
5. 同时连着 Wi-Fi 的情况：USB 应该优先，拔线后自动回到 Wi-Fi。

### 3.3 如果连不上，按这个顺序排查（都是还没验证过的假设）

| 现象 | 怀疑 | 处理 |
|---|---|---|
| 电脑端一直 `waiting`，手机端没动静 | `UsbListener` 的 `requiredInterfaceType = .loopback` 让 usbmux 进来的连接被拒（usbmux 连的是设备 127.0.0.1:23334，理论上走 lo0，但没验证） | 先去掉这一行试；若可行，要给监听端加访问控制（比如只接受第一条连接 + 校验来源），否则局域网里别人能连上手机的 23334 |
| 电脑端日志：`手机端拒绝了 WebSocket 握手` | 手机监听端 WS 握手没通过 | 看 `NWProtocolWebSocket` 服务端模式是否需要 `setClientRequestHandler`；用 `nc`/抓包看手机回了什么 |
| 握手通了，但 hello 之后立刻断 | **监听端拒收“掩码键全 0”的客户端帧**（电脑端为了广播只编一次帧，用 `ws_zero_mask` 加了掩码位但键为 0，见 `pc/app/server.py`） | 改成真随机掩码：`WSClient._write` / `send_raw` 里对每个 USB 客户端按帧真正做 XOR（代价是每帧多拷贝一次，2 KB × 100/s，完全可以接受），同步改 `pc/tests/test_usb.py` 的假手机不受影响（它本来就解掩码） |
| 连上了，`link` 一直是 connecting | 入站连接的 `.ready` 回调时机，或 `Connection.attach` 里 `receive` 没启动 | 在 `stateUpdateHandler` 里打日志看状态流转 |
| 连上能听，但几秒后被 watchdog 判死 | 手机收不到电脑的消息 | 检查 `receiveMessage` 对入站连接是否持续循环 |

**协议本身没变**：USB 通道上跑的消息和 Wi-Fi 完全一样（`hello` / `config` / `status` / 音频帧），区别只有：
手机是 WebSocket 服务端（系统在 `NWListener` 里做握手），电脑是客户端；不需要访问码。

## 4. 其他要在真机上看的（CLAUDE.md 里“第一优先”的剩余项）

- Wi-Fi 路径：Bonjour 自动发现电脑、扫码连接、访问码错误提示。
- 锁屏后继续播放 / 继续当麦克风（后台音频模式）；打断（来电）和耳机插拔后的恢复。
- 回声消除开关（`setVoiceProcessingEnabled`）打开时，播放和收麦是否都正常；`AVAudioSinkNode` 失败时是否退回 `installTap`。
- 降噪选项（关闭/轻度/RNNoise/强力）选了之后电脑端是否响应（`status.mic.denoise` 回传）。

## 5. 约定（别踩）

- 三端抖动缓冲算法（网页 `player-core.js`、iOS `sq_core.c`、电脑端 `mic.py`）必须同步；改了任何一个都要跑对应仿真。
- 不要给 `JSONSerialization` 传 Optional/非法对象（会抛 ObjC 异常，`try?` 拦不住）——`Connection.sendJSON` 已经先 `isValidJSONObject`。
- 用 `NWConnection` 而不是 `URLSession`（避开 ATS 对明文 ws:// 的限制）。
- 不要用 `pkill -f server.py` 这种会匹配到自己命令行的写法，按端口或 PID 结束进程。
- 电脑端同一时间只允许一个声桥进程（单实例互斥体，Windows 才有；Mac 上没有这个限制）。

## 6. 做完后交回什么

1. 一份清单：编译时改了什么、USB 直连哪些步骤通了/没通、真机上发现的问题。
2. 代码改动提交成 commit（或补丁文件），带回 Windows。
3. 如果改了 **协议或帧格式**（比如换成真掩码），同时改 `pc/app/server.py` 和 `pc/tests/test_usb.py`，
   在 Windows 上跑 `python pc/tests/test_usb.py` 应仍全部通过。
4. 更新 `CLAUDE.md` 的“当前状态”表：把已验证的项改成“已验证”，并写上日期。

## 附：关键文件速查

| 文件 | 作用 |
|---|---|
| `ios/ShengQiao/Net/UsbListener.swift` | **新**。回环接口上的 NWListener（端口 23334，WebSocket 服务端），连进来的连接交给 `Connection.adopt` |
| `ios/ShengQiao/Net/Connection.swift` | WebSocket 连接。`start`（Wi-Fi，出站）和 `adopt`（USB，入站）共用 `attach` 里的收发逻辑；`inbound` 标记当前是否 USB |
| `ios/ShengQiao/Model/AppModel.swift` | 状态机。`viaUSB`、`usbConnected`、`usbLost`、`dropUSB`、`usbEnabled`；USB 优先，断了回 Wi-Fi |
| `ios/ShengQiao/UI/ConnectView.swift` | “数据线”区块：USB 直连开关 + 当前状态 |
| `pc/app/usbmux.py` | 电脑端：usbmuxd 客户端（plist 协议）+ `UsbManager`（盯设备插拔、反复连 23334） |
| `pc/app/server.py` | `run_session` 是 Wi-Fi/USB 共用的会话循环；`WSClient.masked` / `ws_zero_mask` 是客户端帧 |
| `pc/tests/test_usb.py` | 假 usbmuxd + 假手机的端到端测试（Windows 上全过） |
