# 声桥低延迟模式实施记录

## 当前交接状态

- 当前进度：**P0～P3 的实现已完成；P4 的自动化集成、APK 构建与 Windows 桌面包已完成，目标手机真机验收仍待进行**。
- 低延迟协商、独立流编码、writer/ACK、Android 连续解码、PCM 缓冲、输出与设置均已接入。下方第 2～12 节保留逐小步历史，不代表当前进度。
- 2026-11-14 晚间收尾：修复 `test_wasapi_output.py` 直接运行不执行测试的问题；PC 协议 51 项、USB 18 项、四种降噪端到端和 Android 隔离协议联调通过。
- Windows 桌面包已按最新源码重打为 `../dist/ShengQiao-Windows-Desktop.zip`，ZIP 完整性、网页资源及 Opus/RNNoise 文件一致性通过；exe 使用测试音源和假输出的启动检查返回 0。正式服务仍在运行，未做打包 exe 的第二实例完整服务测试。
- 最新 Debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`，并同步到 `../dist/ShengQiao-Android-debug.apk`。当前 ADB 无在线设备，真机安装、instrumentation、低延迟 A/B、锁屏/蓝牙/路由和长时间双向音频仍是待验收项；不能从自动化结果推断真实延迟收益。

原始方案：`E:\ShengQiao-Source\LOW-LATENCY-MODE-PLAN-AND-PROMPT.md`。

实际 Git 项目根目录：`E:\ShengQiao-Source\ShengQiao`。

## 1. 协作和基线约束

1. 当前分支为 `main`，HEAD 为 `bbdf01a873d65eb45d84ae365b5009ecaa737119`（2026-10-03，`交接说明 CLAUDE.md`）。HEAD 不是完整工作基线。
2. 工作区已有大量未提交修改，包括 `pc/app/server.py`、网页、iOS、构建文件等；`android/` 整体当前为未跟踪目录，`pc/app/opus_codec.py` 和 bundled `opus.dll` 也未跟踪。不得用 `reset --hard`、`clean`、整目录覆盖或 checkout 回退这些内容。
3. 项目内未找到 `AGENTS.md`。已阅读 `README.md`、`CLAUDE.md`、`REVIEW-2026-11-13.md`、`android/README.md` 和 `E:\ShengQiao-Source\android-tasks\STATUS.md`。
4. 外层状态表中的荣耀 SDK、界面和 Opus 文件认领记录均标为已完成，没有显示正在活跃开发的低延迟任务。后续若发现其他窗口新认领或同时修改相同文件，先停止冲突部分并保留现场。
5. 每步完成后在本文件追加：实际改动、行为变化、未运行/未验证项、下一步建议。不要覆盖历史步骤。
6. 不提交、不推送、不发布、不改签名、不安装设备、不重置访问码/配置，除非用户后来明确授权。
7. 本步仅确认以下工具路径存在，未执行构建或测试：`pc/runtime/python.exe`、项目根目录 `E:\ShengQiao-Source\.tools` 中的 JDK、Android SDK 和 Gradle。未枚举 adb 设备。

## 2. 首版范围和偏好定稿

### 2.1 范围

只优化 **Windows PC → Android 的“收听电脑声音”**。保留标准模式、双向音频、麦克风 PCM 上行、降噪、AEC、iOS、网页和 iPhone USB 现有行为。

首版继续保持：WebSocket/TCP、48 kHz、stereo、int16 PCM、10 ms/480 samples 帧、codec 1=PCM、codec 3=Opus、8 字节音频头，以及 PCM 和 64/128/256/320 kbps Opus 选项。

不扩展到 UDP/RTP/QUIC/WebRTC、5 ms 帧、Oboe/AAudio、iOS Opus、上行 Opus、玻璃 SDK/导航/主题重构。

### 2.2 Android 持久化偏好

- 新键：`latencyMode`，字符串 `standard | low`，默认 `standard`。
- 旧键 `target` 原样保留为**标准模式缓冲偏好**，只允许 40/70/150 ms，非法值回退 70 ms；不另存一份可能互相覆盖的缓冲偏好。
- 低延迟实际生效时禁用标准缓冲控件并显示“低延迟模式自动调整缓冲”，但不得改写 `target`。关闭低延迟后恢复该值。
- 未连接也允许保存 `latencyMode=low`，显示“连接后生效”，不得显示“已生效”。
- `bitrate`、`echo`、`volume`、降噪和设备选择保持原值；切换低延迟不自动修改它们。
- `preferredMode` 来自持久化偏好；`effectiveMode`、`activation`、`fallbackReason` 仅为运行状态，自动回退不得覆盖 `latencyMode=low`。
- `BridgeState.targetMs` 继续表示标准缓冲偏好，实际 PCM 目标由单独字段表示，避免低延迟目标污染标准设置。

### 2.3 生效判定

只有同时满足以下条件，整体状态才能显示低延迟 `active`：

1. 当前连接和收听会话仍有效，且是最新 `configId`。
2. PC 最新 status 确认 `latencyMode=low`。
3. Android 已按相同 `(connectionGeneration, streamId)` 处理新流边界。
4. 本地解码、PCM 缓冲和输出已经应用 low profile。
5. 当前不处于模式 fallback。

只有 PC 接受 low、只有用户打开开关、或只有 AudioTrack 请求了 LOW_LATENCY，都不足以显示整体已生效。

## 3. 初始 profile 参数定稿

这些是首版实现起点，不是实测最优值；后续调整必须在本文记录原因。

| 项目 | standard | low |
| --- | --- | --- |
| 帧长 | 480 samples / 10 ms | 不变 |
| Opus application | 保留 VOIP / 2048 | RESTRICTED_LOWDELAY / 2051 |
| Android 基础 PCM 目标 | 用户 `target`：40/70/150 ms | 30 ms |
| 自适应总目标上限 | 保留现有标准策略，修复 reset 遗留 extra | 60 ms |
| 一次独立欠载 | 保留标准策略 | +10 ms，每事件最多一次 |
| 稳定回落 | 保留标准策略 | 连续稳定 10 秒，-10 ms，同时消费真实积压 |
| 解码后 PCM 硬界限 | 标准目标的合理有界余量 | `min(target + 30 ms, 90 ms)` |
| PC 每客户端待发 PCM | 保留兼容行为但必须始终有界 | 目标 20 ms、硬上限 40 ms，同时检查包龄 |
| PC 待发包最大应用层年龄 | 有界，具体值在实现记录中说明 | 60 ms |
| Android 待解码压缩包 | 有界 | 至多 6 包 / 60 ms |
| PCM 漂移补偿 | 标准行为不得退化 | 初始 ±0.5%，有界连续插值 |
| audio_ack | 旧客户端不要求 | 100 ms 合并上报，仅序号前进时发送 |
| 持续堵塞恢复 | 有界恢复 | 初始阈值 `max(500 ms, 200 ms + 2 × 最小 RTT)` |
| 主动拥塞重连 | 保留退避 | 10 秒内最多一次 |

补充约束：

- 队列包数和真实最老包龄必须同时限制；应用层时间戳使用各自设备的单调时钟。
- 编码前丢过期 PCM；已经编码的 Opus 不得在同一流里静默跳包。清掉已编码历史后必须新建流或重连。
- PCM 追赶、变速和交叉淡化发生在连续解码之后。
- AudioTrack 初始低延迟容量从设备推荐 quantum 的 2 倍开始；失败回退当前安全配置。容量、有效 buffer 和当前排队量分开记录。
- 蓝牙、系统重采样和硬件末端延迟不得写成已知为 0。

## 4. 协议契约 v1

### 4.1 能力协商

PC 的 `hello` 保留现有 `name/id/rate/channels/bitrates/version/mp3` 等字段，新增：

```json
{
  "type": "hello",
  "features": ["mic", "opus", "latency_profile_v1", "stream_info_v1"],
  "latencyModes": ["standard", "low"],
  "latencyProfileVersion": 1,
  "audioFrameSamples": 480
}
```

- 没有可用 Opus 时不宣称 `opus`，但低延迟 PCM 协议仍可宣称可用。
- Android 必须收到 hello，确认 `features` 同时含 `latency_profile_v1`、`stream_info_v1`，且 `latencyProfileVersion=1`，才能启用新语义。
- 新版 Android 的首次完整 config 示例：

```json
{
  "type": "config",
  "clientFeatures": ["latency_profile_v1", "stream_info_v1"],
  "configId": 1,
  "latencyMode": "low",
  "listen": true,
  "mic": false,
  "micRate": 48000,
  "bitrate": 128,
  "deviceName": "手机"
}
```

- `clientFeatures` 声明的连接能力固定不变；后续 config 可省略，若重复必须相同。
- 首个 config 发送完整状态。后续只发送变化字段、`configId` 和必要的设备标识；切模式/码率不得无条件重发 `mic:true`，避免重新抢占其他手机的麦克风。
- `configId` 是每连接从 1 开始的正整数，严格递增。相同 ID 的重复请求必须幂等；同 ID 不同内容以及过期 ID 不得产生新副作用。客户端只接受仍等于本地最新请求 ID 的应用响应。
- 未知字段忽略；已知字段做类型、长度和范围校验。无效 `configId` 的消息整包忽略，其他无效字段不应用且不得改变全局配置。重复相同有效配置可回报当前状态，但不重建流、不清缓冲、不重启麦克风。
- 新协议客户端在首个有效 `stream_info` 前，丢弃 hello 后因服务端默认 `listen=true` 而可能收到的旧 PCM。旧协议路径仍按原规则播放。

### 4.2 流边界和顺序

对声明新能力的客户端，每条新流的首包音频之前必须有：

```json
{
  "type": "stream_info",
  "streamId": 1,
  "configId": 1,
  "latencyMode": "low",
  "codec": "opus",
  "bitrate": 128,
  "sampleRate": 48000,
  "channels": 2,
  "frameSamples": 480,
  "firstSequence": 0,
  "opusApplication": 2051,
  "codecDelaySamples": 120
}
```

定稿规则：

- `streamId` 是每连接从 1 开始严格递增的正整数；客户端流身份为 `(connectionGeneration, streamId)`。
- `firstSequence` 首版固定为 0。第一包音频使用该值，随后按 uint32 小端序号递增，比较和缺口计算按模 2^32，允许 `0xffffffff → 0`。
- 二进制头保持 `[codec][flags][0][0][seq u32 LE]`；新语义只存在于已经协商的会话。
- PCM 使用 `codec="pcm"`、`bitrate=0`、`opusApplication=null`、`codecDelaySamples=0`；Opus 使用实际码率和实时查询的 lookahead，不能写死 120。
- 新建流触发：开始/重新收听、PCM/Opus 切换、Opus application 切换、码率切换（首版统一重建）、模式切换、需要重建编码器、采集时间轴不连续、灾难性积压恢复。
- 普通音量调整、重复同值 config、UI render 不新建流。
- 切流时清除尚未写出的旧流音频，并在同一 writer 中按 **`stream_info` → 对应该配置的 `status` → 新流音频** 写入。已经进入 TCP 的字节不可撤回，严重过期走有节流的连接恢复。
- 周期 status 只报告当前流，不代替 `stream_info`，也不触发 decoder reset。
- Android 网络接收线程必须识别 `stream_info`，把流边界和音频按到达顺序放进同一串行解码队列；UI 更新另行异步发布。不能先投主线程再让网络线程直接解码后续音频。
- 解码执行器处理新流时按序重建/reset decoder、清旧 PCM 和 extra、重置该流序号与统计并淡入。旧 connection generation 的事件一律拒绝。

### 4.3 status 和回退

保留现有 status 字段，新增可选字段：

```json
{
  "type": "status",
  "configId": 1,
  "requestedLatencyMode": "low",
  "latencyMode": "low",
  "streamId": 1,
  "codec": "opus",
  "bitrate": 128,
  "frameMs": 10,
  "codecDelayMs": 2.5,
  "fallbackReason": null,
  "codecFallbackReason": null
}
```

- 旧客户端不依赖这些字段；未协商或尚无配置时可省略。
- `requestedLatencyMode` 是服务端当前接受的请求，`latencyMode` 是服务端实际流 profile。
- `fallbackReason` 只描述**模式**回退：`server_unsupported | output_unsupported | network_unstable | processing_overload | codec_error | null`。
- `codecFallbackReason` 单独描述编码回退，例如 `opus_unavailable | encoder_error | decoder_error | null`。Opus 失败而 PCM low 可运行时，`latencyMode` 仍为 low。
- Android 的 `output_unsupported`、本地处理过载等只存在本地状态；自动回退时向 PC 请求 standard，但持久化 `preferredMode=low` 不变。
- 建议自动回退条件：10 秒窗口至少 3 次独立欠载且 PCM 总目标已到 60 ms，或反复拥塞恢复。排除启动预缓冲、主动切换和路由重建。
- 自动回退在本次收听会话及同一次连续故障的自动重连中保持 standard，不自动反复试 low。用户手动重试、手动停止后重新收听或主动建立全新会话时才清除该回退决定。

### 4.4 audio_ack

仅 low 新协议会话需要，约每 100 ms 合并发送：

```json
{
  "type": "audio_ack",
  "streamId": 1,
  "receivedSequence": 100
}
```

- `receivedSequence` 表示该流在 Android 网络接收层已经连续、完整、有效接收的最大 uint32 序号，不表示已解码或已播放。
- 序号没有推进时不重复发送；无新音频时不把缺少 ACK 当拥塞。
- PC 用本机单调时间维护有界“已写入序号 → 写入时间”账本，以最老未确认包龄辅助判断远端/TCP 滞留。
- 旧 stream、重复、逆序、越过已发送范围的 ACK 不得推进账本。启动、暂停、切流、恢复设宽限期，轻微抖动不触发断线。
- ACK 必须经现有后台发送机制，只保留最新待发送 ACK，不在 UI/音频渲染线程写 socket。
- `drain()` 只表示本地 transport 当前可继续写，不代表客户端收到或听到。严重堵塞使用能放弃该连接残留数据的恢复路径；错误访问码、用户主动断开不得计入拥塞重连。

## 5. 状态归属定稿

| 状态/对象 | 唯一修改方 |
| --- | --- |
| Android 持久化偏好和可见 Service 状态 | 主线程既有入口 |
| WebSocket 接收顺序 | 网络读取线程 |
| 当前 stream、Opus decoder、序号连续性 | 串行解码执行器 |
| PCM 读写索引 | 明确同步的单生产者/单消费者缓冲 |
| AudioTrack 生命周期和 write | 播放线程，安全命令协调停止 |
| UI | 主线程上的不可变快照 |
| PC 每客户端 encoder、stream、待发队列和 ACK 账本 | 该客户端单一串行发送/编码上下文 |

额外边界：

- decoder 不得被网络线程和播放线程并发使用；close 必须排在既有 decode 之后。
- 播放线程不做 socket、JSON、磁盘或 JNI 初始化。
- `startNewStream`、`changeProfile`、`recoverUnderrun`、`stopListening` 分开实现，不能继续共用一个语义不清的 reset。
- 新流、重连和停止后重开都清除 extra；旧播放错误回调必须带 playback generation，不能关闭新播放。
- 新协商客户端优先每客户端独立 Opus encoder。旧客户端可保留兼容共享路径，但不得因新客户端切流而重置旧客户端仍依赖的编码器。

## 6. 兼容矩阵定稿

| 组合 | 行为 |
| --- | --- |
| 新 PC + 新 Android | 协商 standard/low，使用 stream_info、每流序号和诊断 |
| 旧 PC + 新 Android | 旧协议 standard；保留 low 偏好，显示需更新电脑端，标准缓冲可操作 |
| 新 PC + 旧 Android | 旧协议，不发送需要新语义才能安全理解的流 |
| 新 PC + 当前 iOS | 继续 PCM、麦克风和 USB 行为 |
| 新 PC + 网页实时/MP3 | 原协议和原流不变 |
| 多客户端 | 每客户端独立模式/流/队列；慢客户端不阻塞快客户端，编码错误只影响自身 |

## 7. 已核对的现状与新增覆盖缺口

现状源码确认：

- `pc/app/opus_codec.py` 固定 application=2048，尚无 PCM 长度校验和 lookahead 查询。
- `Hub.opus_encoders` 按码率共享；编码异常会把所有客户端切 PCM。
- `WSClient` 直接写 transport，固定 96 KiB 限制；采集通过每帧 `call_soon_threadsafe` 进入事件循环。
- Android 当前文本回调到主线程、二进制直接 `audio.offer`；当前 config 总是携带 listen/mic/micRate/bitrate。
- `JitterBuffer` 在压缩包层追赶，reset 未清 `extraFrames`；`AudioEngine.Playback` 取出后再解码。
- 旧延迟公式为 `buffer + 10 + rtt/2`，不包含完整输出排队和设备延迟。

现有测试没有覆盖以下新契约，后续不得以旧测试通过代替：

- capability/configId/stream_info/ACK、过期响应、序号回绕和兼容矩阵。
- 多客户端独立编码历史、切流顺序、编码前过期丢弃和 ACK 不推进恢复。
- Android 连续解码后 PCM 追赶、短缺口 PLC、新流清 extra、漂移和快速切换。
- AudioTrack 短写/零写/实际排队、路由重建和旧 generation 回调。
- 真实 A/B、双向音频、锁屏和路由测试。

**本步没有运行任何测试，以上缺口仅来自源码和既有测试文件核对。**

## 8. 本步实际文件改动

- 新增：`LOW-LATENCY-IMPLEMENTATION.md`。
- 功能源码改动：无。
- 测试执行：无（用户要求）。
- Git 提交、设备安装、用户配置修改：无。

## 9. 第 1 步规划的 P1-1（已由第 2 步完成）

建议下一小步只做 **P1-1：PC OpusEncoder 基础能力**，范围限定 `pc/app/opus_codec.py`：

1. 用命名常量支持 VOIP 和 RESTRICTED_LOWDELAY，标准默认仍为 VOIP。
2. 构造时校验码率/application，编码前严格要求 480 帧、48 kHz、stereo、int16，即 1920 字节 PCM。
3. 正确声明可变参数 CTL 的 ctypes 调用方式，查询并暴露实际 `OPUS_GET_LOOKAHEAD`。
4. 保证初始化失败、重复 close 和析构路径不泄漏、不双释放。
5. 不处理 `server.py`、采集队列或 Android；按当前用户要求不运行测试。完成后更新本文件并停止。

## 10. 第 2 步完成记录：P1-1 PC OpusEncoder 基础能力

实际修改：`pc/app/opus_codec.py`。

完成内容：

- 新增 `SAMPLE_RATE`、`CHANNELS`、`FRAME_SAMPLES`、`PCM_BYTES` 常量，明确当前输入为 48 kHz、stereo、480 帧、int16，即 1920 字节。
- 新增 `OPUS_APPLICATION_VOIP=2048`、`OPUS_APPLICATION_RESTRICTED_LOWDELAY=2051` 和允许值集合。
- `OpusEncoder(bitrate_kbps)` 旧调用保持 VOIP 默认；新调用可传第二个 `application` 参数，非法 application 在加载库之前拒绝。
- 创建编码器时使用命名采样率、声道和 application 常量，不再散落 48000/2/2048。
- 新增 `OPUS_GET_LOOKAHEAD=4027` 查询，结果以 samples 暴露为 `encoder.lookahead`；查询失败会关闭已创建的编码器。
- `encode()` 接受 bytes-like 输入，但在进入 C 接口前严格要求 `memoryview.nbytes == 1920`；过短、过长及非 bytes-like 输入均明确拒绝。
- `opus_encoder_destroy.restype` 设为 `None`；既有初始化失败清理、重复 close 和析构保护保留。

兼容与边界：

- `server.py` 尚未传入 application，因此当前所有实际编码仍走 VOIP；本步没有宣称低延迟链路已生效。
- 未修改四档码率、帧长、DTX、FEC、声道或带宽配置。
- 未处理每客户端独立编码器、切流、采集/发送队列、Android 或 UI。
- 按用户要求未运行 `test_opus.py`、编译检查或任何其他测试；真实库查询结果和波形仍待后续验证。

下一步建议：P1-2 只实现采集线程到 asyncio 的有界邮箱、单调时间戳和单 drain/wakeup，并接入 `server.py` 当前采集入口；不要同一步展开每客户端 writer 或 Android。

## 11. 第 3 步完成记录：P1-2 PC 采集有界交接

实际修改：`pc/app/capture.py`、`pc/app/server.py`。

完成内容：

- 新增 `CaptureMailbox`，采集线程调用 `offer()` 时记录 `time.monotonic()` 应用接收时间；该时间是应用拿到 PCM 块的时间，不冒充硬件采样时间。
- 默认 `MAILBOX_BLOCKS=4`（40 ms）和 `MAILBOX_MAX_AGE=0.060`（60 ms），提交和 drain 两侧都检查数量/包龄。
- `_scheduled` 配合锁保证同一时间最多一个待执行或执行中的 drain；drain 期间新到的数据只在结束时补排一个回调。
- drain 在事件循环执行，过期块和同一批中的较旧块被记录为丢弃，只向 Hub 发布最新有效块，避免挂起恢复后连续补发旧声音。
- 新增 `stats()`，提供 `queueMs`、`oldestAgeMs`、`dropped`、`discontinuities`、`lastDeliveryAgeMs`，供后续诊断接入。
- `close()` 可拒绝后续提交并清空待处理块；loop 已关闭时的 `call_soon_threadsafe` 失败会关闭邮箱，不让采集线程反复排回调。
- `App.start()` 已改用 `CaptureMailbox.offer`，不再每帧直接 `loop.call_soon_threadsafe(self.hub.on_capture_block, blk)`。
- `Hub.on_capture_discontinuity()` 当前重置 `fill_t`，防止真实采集恢复后再沿用旧静音填充时间轴。

参数和边界说明：

- 40 ms 容量和 60 ms 包龄是本步确定的首版采集邮箱起点，并非硬件实测结果。
- 邮箱对标准和低延迟路径都提供基本有界保护；正常无积压时每块仍按原路径发布。
- 当前不连续通知只处理 filler 时间轴，**尚未触发新 stream_info 或新流重建**。
- 尚未修改固定 96 KiB transport 判断、共享 Opus 编码器、每客户端发送队列、ACK 或 Android。
- `stats()` 尚未接入网页或 Android 诊断界面。
- 按用户要求未运行采集仿真、协议测试、编译检查或任何其他测试；事件循环停顿与真实声卡行为待后续验证。

下一步建议：P1-3 只实现新协议会话的 StreamProfile 和每客户端独立编码状态，明确 standard/low 与 PCM/Opus 组合及切流时的 encoder 生命周期；保留旧客户端共享路径，不在同一步实现 writer/ACK。

## 12. 第 4 步完成记录：P1-3 StreamProfile 与独立编码状态

实际改动：

- 新增 `pc/app/audio_pipeline.py`。
- 修改 `pc/app/server.py` 的模块导入、WSClient 所有权和断开清理。

完成内容：

- `StreamProfile` 只接受 `standard | low` 和 PCM(0)/64/128/256/320 kbps；`codec` 与 `opusApplication` 由 profile 派生，low 使用 2051，standard 使用 2048。
- `ClientAudioPipeline` 分开保存 `requested_profile` 和 `actual_profile`，模式回退与编码回退不会混为一个字段。
- 每个 WSClient 创建独立管线；管线只有在未来新协议配置启用后才创建 encoder，当前旧客户端不会因此多建编码器。
- `configure()` 对相同 requested profile 幂等，新 configId 可更新状态但不新建流；profile 改变或停止后重新配置会新建流。
- `restart()` 强制重建当前实际流；`close()` 释放 encoder 和实际流，但保留本连接的 streamId 计数，便于后续重新收听时继续递增。
- 每条流从 sequence 0 开始，音频头仍使用 `<BBHI`，每包后按 uint32 回绕。
- `_stream_info()` 输出 P0 冻结字段，Opus lookahead 来自实际 encoder，PCM application 为 null、codec delay 为 0。
- `encode()` 返回 `(可选 stream_info, 原始音频包)`。编码中失败时先产生同模式 PCM 新流信息，再产生 sequence 0 的 PCM 包，不静默跳过 Opus 历史。
- Opus 不可用和创建/编码失败分别记录 `opus_unavailable`、`encoder_error`；只改变当前管线，旧 Hub 的全局回退路径尚未改动。
- `status()` 提供 configId、请求/实际模式、streamId、实际 codec、码率、帧长和 codecDelayMs；`fallbackReason` 当前固定 null，模式回退由后续状态机负责。
- `last_codec_error` 只保存错误字符串，避免异常 traceback 持有旧 encoder 或管线对象。
- `run_session()` 的 finally 已调用 `client.audio_pipeline.close()`。

明确未完成：

- 新管线尚未接收 `hello/config` 协商，也尚未被 Hub 调用；当前实际客户端仍使用旧共享编码路径。
- configId 的严格递增、重复冲突和过期请求处理由 server 集成负责，管线只做正整数校验。
- `configure/restart` 返回新边界后，调用方必须清旧发送队列并保证 stream_info/status/音频写序；本步没有 writer，无法声称该顺序已在线路上生效。
- 未实现 ACK、transport 恢复、Android 或 UI。
- 按用户要求未运行真实 libopus、单元、协议、编译或任何其他测试；四档码率、回绕和异常路径仍待后续验证。

下一步建议：P1-4 接入 PC 能力协商和增量 config，校验 configId，并让新协议客户端通过独立管线发布 stream_info/status/音频；旧客户端继续原路径，ACK 留到后续步骤。

## 13. PC 与 Android 集成（2026-11-14）

Android 默认使用标准模式。在「设置 → 音频 → 低延迟模式」打开开关并开始收听后，新电脑端协商低延迟。首页在电脑端确认当前流且 AudioTrack 已开始消费该流后显示「低延迟」。标准缓冲偏好仍保存在原有 `target` 设置中。关闭开关或自动回退后使用该偏好。该模式只影响电脑到 Android 的收听链路。

- PC 的 `server.py` 接入能力声明、递增 configId 和增量配置。协商后的每个连接使用独立编码状态、PCM 待发队列和有界控制帧队列。切流按 `stream_info → status → 音频` 写入，发送前丢弃过期 PCM 并重新建立编码历史。编码器故障只将该流回退到 PCM。未声明能力的客户端继续使用原二进制帧及配置方式。
- `capture.py` 将应用采集时间传到发送队列。采集邮箱允许两个相邻的正常块同批交付，堆积恢复时只交付最新有效块。声卡切换和静音填充转换会建立新流。PyAudio 备用采集队列缩到四块并按回调时间淘汰过期块。
- `BridgeClient`、`StreamConfig` 和 `AudioStreamReceiver` 按网络到达顺序切换流，使用同一个解码线程推进 Opus 历史，并以流修订号拦截旧事件。最多对三个真实缺口调用 JNI PLC。解码失败可临时改用 PCM，用户保存的码率偏好仍可用于下一次收听。客户端后台发送线程每约 100 ms 合并 ACK，并在暂停前有待确认音频时补发最后一个 ACK。
- `PcmJitterBuffer` 在解码后执行 PCM 追赶和淡化，按水位做 ±0.5% 线性插值时钟补偿。每条新流清空自适应缓冲。低延迟目标从 30 ms 开始，每次独立欠载加 10 ms，上限 60 ms，稳定 10 秒回落一档并消费积压。标准保留 40/70/150 ms 基础设置，以及每次加 20 ms、稳定 15 秒回落的既有 Android 策略。
- `AudioEngine` 由同一播放线程创建、配置、写入、清空和释放 AudioTrack。有效缓冲从两倍系统推荐输出周期开始，最低一个网络帧，容量留给欠载后的增长。区分容量、有效缓冲、播放头估算的排队、系统欠载与应用欠载。实际路由切换时清除旧输出、重估缓冲并淡入。排队估算不含系统后续混音、重采样、蓝牙和硬件末端缓冲。参数行为依据 [Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack)。
- `BridgeService`、`BridgeState`、设置页和首页详情接入请求模式、实际模式、应用与系统欠载、PCM 追赶、网络缺口及编解码回退。连续独立欠载且已达到 60 ms 时切回标准。同一次故障中两次低延迟流断开相距不足 10 秒时，下次连接用标准模式继续；标准连接沿用现有重连退避。切换开关或停止后重新收听可以重试。

| 应用层初始参数 | 低延迟 | 标准协商流 |
| --- | --- | --- |
| PC 每连接待发 PCM | 上限 40 ms，超限收回到 20 ms，包龄上限 60 ms | 上限 150 ms，超限收回到 70 ms，包龄上限 200 ms |
| PC ACK / transport | ACK 账本 512 条，活跃时阈值 `max(500 ms, 200 ms + 2 × 最小 RTT)`，超时中止连接 | transport 写超时 1 秒 |
| Android 待解码 | 最多 6 包，年龄 60 ms | 最多 15 包，年龄 150 ms |
| Android 已解码 PCM | 上限为目标 +30 ms，最多 90 ms，接收年龄上限 150 ms | 上限为目标 +300 ms，接收年龄上限 650 ms |
| 编码 / 帧长 | Opus application 2051 或 PCM，10 ms | Opus application 2048 或 PCM，10 ms |

发送端超过约 250 ms 没有写入新音频且没有待发 PCM 时，暂停 ACK 堵塞判定。活跃流发生切流时，保留跨流最老未确认年龄，直到更新流的 ACK 证明此前字节已经通过。电脑端本地状态接口同时提供采集邮箱、每连接待发时长和待确认包龄。

## 14. 验证与交付

| 验证命令或入口 | 结果 |
| --- | --- |
| `pc/runtime/python.exe -B pc/tests/test_low_latency.py` | 8 通过，0 失败，0 跳过。覆盖切流写序、重复 config、过期 PCM、ACK 回绕/超时/空闲、多客户端与编码异常隔离、采集积压 |
| `pc/runtime/python.exe -B pc/tests/test_opus.py` | 3 通过，0 失败，0 跳过。包括本机 libopus 的四档码率 × 两种 application、帧长度和前瞻查询 |
| `pc/runtime/python.exe -B pc/tests/test_ws_reader.py` | 5 通过，0 失败，0 跳过 |
| `pc/runtime/python.exe -B pc/tests/test_device_names.py` | 4 通过，0 失败，0 跳过 |
| `pc/runtime/python.exe -B pc/tests/test_connection_settings.py` | 9 通过，0 失败，0 跳过。覆盖 USB 网卡、访问码持久化、会话失效和本机接口约束 |
| `pc/runtime/python.exe -B pc/tests/test_wasapi_output.py` | 1 通过，0 失败，0 跳过；已修复直接运行时不进入 unittest 的问题 |
| `pc/runtime/python.exe -B pc/tests/test_mic_core.py` | 9 个仿真检查通过，0 失败 |
| `pc/runtime/python.exe -B pc/tests/test_usb.py` | 18 项模拟 USB 检查通过，0 失败；本次通过既有隔离启动脚本运行，未关闭正式服务 |
| `pc/runtime/python.exe -B pc/tests/test_protocol.py HOST PORT KEY` | 隔离服务的 51 项旧协议、网页 HTTP/MP3、PCM、手机麦克风与抢占检查通过，0 失败 |
| `pc/runtime/python.exe -B pc/tests/test_denoise_e2e.py HOST PORT KEY` | 四种降噪模式经网络和假声卡完成回归，0 失败 |
| Gradle `:app:testDebugUnitTest :app:testReleaseUnitTest` | Debug 30 通过，含真实 Python 服务联调；Release 28 通过、2 项因未注入联调环境跳过，均 0 失败。PCM 测试覆盖实际包龄、追赶、独立欠载、回落与正负 300 ppm 漂移 |
| Gradle `:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --offline` | 构建通过。lint 0 错误，5 项警告 |

PC 的 Python 命令以项目根目录为工作目录，`PYTHONPATH` 指向 `pc/deps`。协议回归使用独立端口、临时配置、合成声源和假麦克风输出。Android 测试通过真实的 Python WebSocket 服务验证低延迟 Opus 到标准 PCM 的切换及 ACK，JVM 验证与真机 JNI 测试分别统计。

本机合成输入、每组 200 个 10 ms 帧，1/2/4 个独立编码管线每帧总编码耗时的 P95 分别为 0.205 / 0.223 / 0.475 ms。同一测试中以 asyncio 定时模拟 10 ms 投递的调度误差 P95 为 17 / 20 / 20 ms。该测试无声卡、网络或手机输出。PC 保持事件循环内编码，Android 保持 Java AudioTrack。

- 可安装 Debug APK 为 `android/app/build/outputs/apk/debug/app-debug.apk`，并同步到 `../dist/ShengQiao-Android-debug.apk`；2026-11-14 重新构建，SHA-256 为 `06065f07c6ee64418fd6539fdb743f51b76e15840caf9dc5ff1e0ef375e3cf1a`。
- 未签名 Release 为 `android/app/build/outputs/apk/release/app-release-unsigned.apk`，2026-11-14 重新构建；它不是可直接安装的正式签名版。
- 真机测试 APK 为 `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，含真实 PC 低延迟 Opus 双音样本与 JNI PLC 测试；最新构建检查为 UP-TO-DATE，但尚未在设备执行。
- Windows 桌面包为 `../dist/ShengQiao-Windows-Desktop.zip`，2026-11-14 21:19 更新，SHA-256 为 `eafc076f682c05a97409257fd3fdf4d97071c4b4341191b253b3542e67a58755`。

本轮 ADB 最初枚举到手机，随后设备掉线。真机安装、instrumentation 和延迟 A/B 待验收。复测时在同一手机、Wi-Fi、码率和输出路由下分别录制至少 30 次 PC 发声到手机出声的响应，记录中位数和 P95；用至少 10 分钟稳定收听与双向音频记录欠载、重连、CPU 和内存，再覆盖锁屏、有线耳机与蓝牙切换、受控弱网和恢复。声卡采集、手机物理输出和 iOS 真机行为仍待验证。工作区保留本轮源码和构建结果，未提交 Git、安装 APK 或改动用户的正式配置。
