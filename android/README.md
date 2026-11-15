# 声桥 Android

原生 Android 客户端，支持 Android 8.0 及以上版本。旧电脑端可使用标准模式。

## 功能

- 扬声器：48 kHz 双声道 PCM（1536 kbps）或 Opus（64/128/256/320 kbps），APK 内置 `libopus` 解码并通过低延迟 `AudioTrack` 输出，不依赖系统 Opus 解码器。
- 麦克风：48 kHz 单声道 PCM，10 ms 一帧发送到电脑的 VB-CABLE。
- 自动发现同一局域网中的电脑，也可以手动填写 IP、端口和访问码。
- 标准模式提供 40 / 70 / 150 ms 三档接收缓冲；新电脑端支持独立的低延迟模式，接收缓冲从 30 ms 起步。
- 音量、电脑端降噪、系统回声消除。
- 前台服务、部分 CPU 唤醒锁、低延迟 Wi-Fi 锁，收听或开麦后息屏继续运行。
- 断线自动重连，网络恢复时丢弃过旧的音频，避免延迟不断累积。
- 荣耀 Translucent Component Kit 11.0.0.134：底部导航支持系统跟随、液态玻璃和磨砂玻璃；不支持设备使用普通原生背景。
- 悬浮底部栏让滚动内容延伸到材质后面；设置支持任意纯色及系统图片选择作为全局页面背景。

## 构建

需要 JDK 17、Android SDK 35、NDK 27.0.12077973 和 CMake 3.22.1。`libopus` 1.5.2 源码位于 `app/src/main/cpp/opus/`，Gradle 会直接编译进 APK。在本目录运行：

```bash
./gradlew test assembleDebug
```

Windows 命令提示符也可以运行：

```bat
gradlew.bat test assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 发布与升级

当前对外版本为 `1.0.0`（versionCode 2），applicationId 保持 `com.shengqiao.android`，可以从旧的 1.0.0（versionCode 1）覆盖升级并保留设置。正式 Release 签名不写入仓库，通过四个环境变量提供：`SHENGQIAO_KEYSTORE`、`SHENGQIAO_KEYSTORE_PASSWORD`、`SHENGQIAO_KEY_ALIAS`、`SHENGQIAO_KEY_PASSWORD`。必须全部设置；缺少任一变量会在构建前报错。

```powershell
$env:SHENGQIAO_KEYSTORE = 'D:\keys\shengqiao.jks'
$env:SHENGQIAO_KEYSTORE_PASSWORD = '密钥库密码'
$env:SHENGQIAO_KEY_ALIAS = 'shengqiao'
$env:SHENGQIAO_KEY_PASSWORD = '密钥密码'
.\gradlew.bat :app:assembleRelease
```

未配置签名时仍会生成 `app-release-unsigned.apk`，只用于构建检查，不作为正式安装包。请使用同一个正式密钥库签名后续版本，并在发布前递增 versionCode。

荣耀通透验证包使用独立 applicationId，不覆盖声桥主应用：

```bash
./gradlew :glass-demo:assembleDebug
```

输出到 `glass-demo/build/outputs/apk/debug/glass-demo-debug.apk`。验证页覆盖滚动背景、三种材质模式、按钮、导航和 Dialog；实际效果以荣耀设备能力及系统设置为准。

安装主应用：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 电脑先运行声桥，手机与电脑连接同一个 Wi-Fi。
2. 打开 Android App，选择自动发现的电脑，或填写电脑 IP 和访问码。
3. 点“连接电脑”，再打开“听电脑”或“当麦克风”。
4. 首次开麦时允许麦克风权限；点“允许息屏后台运行”并确认系统请求。
5. 息屏后保留通知栏中的声桥常驻通知，不要从系统设置里强行停止 App。

### 用 USB 数据线连接

1. 用支持数据传输的数据线连接电脑，在手机「设置 → 个人热点／网络共享」中开启「USB 网络共享」（不同品牌入口名称可能不同）。无需开启 USB 调试或安装 iTunes。
2. 在电脑声桥的「设备」页点击「刷新检测」，再点击「查看 USB 共享地址」。
3. 在安卓 App 中选择发现的电脑，或手动填写上述 USB 地址的 IP、端口与访问码。连接成功后，电脑端会将识别到的共享网卡连接标为「USB 共享」。
4. 若没有识别到共享网卡，在电脑首页「切换地址」中选择 USB 网卡对应的 IP，再手动连接；确认手机未处于仅充电状态。

电脑首页可以刷新连接信息、手动设置访问码或随机生成新码。修改访问码后，需要在手机端输入新码重新连接。

在应用的「设置 → 音频 → 低延迟模式」开启后，开始收听电脑声音。首页显示「低延迟」时，电脑端和 Android 播放均已应用设置。低延迟模式默认关闭，PCM 与各档 Opus 都可使用。网络持续波动时会暂时切回标准缓冲，重新开始收听或重新打开开关可以重试。连接详情可查看 RTT、PCM 水位和 AudioTrack 排队估计。

国产手机的系统可能另有“自启动”“后台运行”“锁屏后清理”等设置，需要把声桥设为允许。Wi-Fi 信号、蓝牙耳机的编解码缓冲和手机厂商的电源策略仍会影响端到端延迟；追求最低延迟时优先使用手机扬声器或有线耳机。

## 代码

- `BridgeService.java`：连接状态、自动重连、前台服务和息屏锁。
- `AudioEngine.java`：低延迟播放与麦克风采集。
- `BridgeClient.java`：声桥配置消息和麦克风低积压发送队列。
- `core/WebSocketTransport.java`：无第三方依赖的 WebSocket 客户端。
- `core/JitterBuffer.java`：自适应播放缓冲。
