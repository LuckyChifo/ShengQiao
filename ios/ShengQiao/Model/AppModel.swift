import AVFoundation
import Network
import Combine
import Foundation
import MediaPlayer
import QuartzCore
import UIKit

enum Preset: String, CaseIterable, Identifiable {
    case low, mid, high
    var id: String { rawValue }
    var title: String { ["low": "低", "mid": "均衡", "high": "稳"][rawValue]! }
    var targetMs: Double { ["low": 35, "mid": 70, "high": 150][rawValue]! }
    var help: String {
        switch self {
        case .low: return "延迟最低。网络一般时可能偶尔卡一下，会自动加缓冲。"
        case .mid: return "推荐。网络抖动时自动加大缓冲，稳定后再收回。"
        case .high: return "最不容易卡，延迟稍高。Wi-Fi 信号差时用这个。"
        }
    }
}

enum LinkState: Equatable {
    case idle
    case connecting
    case connected
    case failed(String)
    case needKey
}

struct MicInfo: Equatable {
    var state = "off"          // off / starting / running / no-cable / error
    var device = ""
    var error = ""
    var mine = false
    var busy = false
    var latencyMs = 0
    var denoise = "rnnoise"                 // 电脑端当前的降噪方式
    var denoiseModes: [String] = []         // 电脑端能用的降噪方式
}

/// 降噪方式（在电脑端处理，App 只负责选择）
enum DenoiseMode: String, CaseIterable, Identifiable {
    case off, light, rnnoise, strong
    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: return "关闭"
        case .light: return "轻度"
        case .rnnoise: return "RNNoise"
        case .strong: return "强力"
        }
    }
    var help: String {
        switch self {
        case .off: return "不做降噪，声音最原始。"
        case .light: return "频谱减法，压风扇、空调这类持续的噪声，几乎不伤人声。对键盘声无效。"
        case .rnnoise: return "神经网络降噪（推荐），键盘、杂音也能压掉。多 20 毫秒延迟。"
        case .strong: return "RNNoise 加噪声门：不说话时完全静音，适合很吵的环境。"
        }
    }
}

struct LiveStats: Equatable {
    var latencyMs = 0
    var bufferMs = 0
    var targetMs = 0
    var underruns = 0
    var rttMs: Int?
    var lostFrames = 0
    var netKbps = 0
    var micKbps = 0
    var buffering = true
}

/// 网络线程和主线程共享的少量数据
private final class Shared {
    private let lock = NSLock()
    private var _micPeak: Float = 0
    private var _bytesIn = 0
    private var _bytesOut = 0
    private var _lost = 0
    func addIn(_ n: Int) { lock.lock(); _bytesIn += n; lock.unlock() }
    func addOut(_ n: Int, peak: Float) { lock.lock(); _bytesOut += n; if peak > _micPeak { _micPeak = peak }; lock.unlock() }
    func addLost(_ n: Int) { lock.lock(); _lost += n; lock.unlock() }
    func take() -> (bytesIn: Int, bytesOut: Int, lost: Int) {
        lock.lock(); defer { lock.unlock() }
        let r = (_bytesIn, _bytesOut, _lost)
        _bytesIn = 0; _bytesOut = 0; _lost = 0
        return r
    }
    func takePeak() -> Float {
        lock.lock(); defer { lock.unlock() }
        let p = _micPeak
        _micPeak = 0
        return p
    }
}

final class AppModel: ObservableObject {
    // MARK: published UI state
    @Published private(set) var link: LinkState = .idle
    @Published private(set) var pcName = ""
    @Published private(set) var listening = false
    @Published private(set) var micOn = false
    @Published var echoCancel: Bool { didSet { defaults.set(echoCancel, forKey: "echoCancel"); if micOn { applyAudio() } } }
    @Published var preset: Preset { didSet { defaults.set(preset.rawValue, forKey: "preset"); sq_player_set_target_ms(audio.player, preset.targetMs) } }
    @Published var volume: Double { didSet { defaults.set(volume, forKey: "volume"); sq_player_set_gain(audio.player, Float(volume)) } }
    @Published var micGain: Double { didSet { defaults.set(micGain, forKey: "micGain"); let g = Float(micGain); connection.queue.async { self.qMicGain = g } } }
    @Published private(set) var micInfo = MicInfo()
    @Published private(set) var stats = LiveStats()
    @Published private(set) var micLevel: Float = 0
    @Published private(set) var audioError: String?
    @Published var micPermissionDenied = false
    @Published var showConnect = false
    /// 当前是不是通过 USB 数据线（电脑经 usbmux 连进来）连着电脑
    @Published private(set) var viaUSB = false
    /// 是否允许电脑通过 USB 连接（关掉就不再监听）
    @Published var usbEnabled: Bool {
        didSet {
            defaults.set(usbEnabled, forKey: "usbEnabled")
            if usbEnabled {
                setManualDisconnect(false)
                usb.start()
            } else {
                dropUSB()
            }
        }
    }

    let audio = AudioController()
    let connection = Connection()
    let discovery = Discovery()
    let usb = UsbListener()

    private let defaults = UserDefaults.standard
    private let shared = Shared()
    private(set) var target: ConnTarget?
    private var key = ""
    private(set) var pcId = ""
    private var wifiPcId = ""    // 对应 target 和 key，USB 握手不能改写
    // 在主线程发起连接时立即更新，主线程处理事件时再核对。
    private var connectionSession: UUID?
    private var manualDisconnect = false
    private var retry = 0
    private var reconnectWork: DispatchWorkItem?
    private var uiTimer: Timer?
    private var tick = 0
    private var rttMs: Double?
    private var discoveryWatch: Any?
    private var denoiseSentAt = Date.distantPast
    private var micPermissionRequest: UUID?

    // 只在 connection.queue 上访问
    private var qListen = false
    private var qMic = false
    private var qMicGain: Float = 1
    private var qMicChunk = 480
    private var qMicSeq: UInt32 = 0
    private var qExpectSeq: Int64 = -1
    private var qLastMessage = CACurrentMediaTime()
    private var qReadySession: UUID?
    private var micTimer: DispatchSourceTimer?
    private var micFloats = [Float](repeating: 0, count: 4096)

    init() {
        let d = UserDefaults.standard
        echoCancel = (d.object(forKey: "echoCancel") as? Bool) ?? true
        preset = Preset(rawValue: d.string(forKey: "preset") ?? "") ?? .mid
        volume = (d.object(forKey: "volume") as? Double) ?? 1.0
        micGain = (d.object(forKey: "micGain") as? Double) ?? 1.0
        usbEnabled = (d.object(forKey: "usbEnabled") as? Bool) ?? true
        manualDisconnect = d.bool(forKey: "manualDisconnect")
        qMicGain = Float(micGain)
        sq_player_set_target_ms(audio.player, preset.targetMs)
        sq_player_set_gain(audio.player, Float(volume))

        connection.onEvent = { [weak self] ev, session in self?.onNetEvent(ev, session: session) }
        audio.onChange = { [weak self] in self?.audioChanged() }
        setupRemoteCommands()
        startMicTimer()

        usb.onConnection = { [weak self] c in DispatchQueue.main.async { self?.usbConnected(c) } }
        if usbEnabled && !manualDisconnect { usb.start() }

        discoveryWatch = discovery.$pcs.sink { [weak self] list in self?.discovered(list) }
        discovery.start()

        if let data = defaults.data(forKey: "lastTarget"),
           let t = try? JSONDecoder().decode(ConnTarget.self, from: data),
           let k = defaults.string(forKey: "lastKey") {
            pcName = defaults.string(forKey: "lastPcName") ?? ""
            connect(t, key: k, pcId: defaults.string(forKey: "lastPcId") ?? "")
        }
        uiTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.uiTick() }
    }

    // MARK: - saved keys

    private var savedKeys: [String: String] {
        get { (defaults.dictionary(forKey: "keys") as? [String: String]) ?? [:] }
        set { defaults.set(newValue, forKey: "keys") }
    }

    func savedKey(for pcId: String) -> String? { savedKeys[pcId] }

    var hasTarget: Bool { target != nil }

    private func setManualDisconnect(_ value: Bool) {
        manualDisconnect = value
        defaults.set(value, forKey: "manualDisconnect")
    }

    // MARK: - connect

    func connect(_ t: ConnTarget, key k: String, pcId: String = "") {
        setManualDisconnect(false)
        if usbEnabled { usb.start() }
        viaUSB = false
        target = t
        key = k.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        wifiPcId = pcId
        self.pcId = pcId
        retry = 0
        startWiFi(t)
    }

    private func startWiFi(_ t: ConnTarget) {
        reconnectWork?.cancel()
        let session = UUID()
        connectionSession = session
        link = .connecting
        connection.connect(t, key: key, session: session)
    }

    private func disconnectNetwork() {
        reconnectWork?.cancel()
        connectionSession = nil
        connection.disconnect()
    }

    /// 连接扫码得到的网址 / 手动输入的地址。返回 false 表示格式不对。
    @discardableResult
    func connect(address: String, code: String) -> Bool {
        guard let parsed = AppModel.parseAddress(address) else { return false }
        let k = code.isEmpty ? (parsed.key ?? "") : code
        connect(.host(parsed.host, parsed.port), key: k)
        return true
    }

    static func parseAddress(_ s: String) -> (host: String, port: UInt16, key: String?)? {
        var text = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return nil }
        if !text.contains("://") { text = "http://" + text }
        guard let comps = URLComponents(string: text), let host = comps.host, !host.isEmpty else { return nil }
        guard let port = UInt16(exactly: comps.port ?? 23333), port > 0 else { return nil }
        let key = comps.queryItems?.first(where: { $0.name == "k" })?.value
        return (host, port, key)
    }

    func disconnect() {
        setManualDisconnect(true)
        disconnectNetwork()
        usb.stop()
        viaUSB = false
        target = nil
        stopAudio()
        link = .idle
        defaults.removeObject(forKey: "lastTarget")
    }

    private func stopAudio() {
        micPermissionRequest = nil
        listening = false
        micOn = false
        micInfo = MicInfo()
        micLevel = 0
        applyAudio()
        connection.queue.async {
            _ = self.shared.takePeak()
        }
    }

    // MARK: - USB 直连

    /// 电脑经 USB 连进来了：USB 优先，把 Wi-Fi 连接换掉（Wi-Fi 的目标留着，USB 断了再回去）
    private func usbConnected(_ c: NWConnection) {
        guard usbEnabled && !manualDisconnect else { c.cancel(); return }
        reconnectWork?.cancel()
        let session = UUID()
        connectionSession = session
        viaUSB = true
        pcId = ""
        pcName = ""
        link = .connecting
        connection.adopt(c, session: session)
    }

    /// USB 连接断了（拔线、电脑退出、App 进后台…）：有 Wi-Fi 目标就回到 Wi-Fi，没有就回到空闲
    private func usbLost(_ reason: String) {
        guard viaUSB else { return }
        viaUSB = false
        pcId = wifiPcId
        pcName = target?.label ?? ""
        if target != nil {
            link = .failed(reason)
            scheduleReconnect()
        } else {
            stopAudio()
            link = .idle
        }
    }

    /// 用户在界面里关掉 USB 直连
    private func dropUSB() {
        usb.stop()
        if viaUSB {
            disconnectNetwork()
            usbLost("USB 直连已关闭")
        }
    }

    private func scheduleReconnect() {
        guard !manualDisconnect, let t = target else { return }
        reconnectWork?.cancel()
        let session = connectionSession
        let delay = min(3.0, 0.5 * pow(1.6, Double(retry)))
        retry += 1
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.connectionSession == session, self.target == t, !self.viaUSB,
                  self.link != .connected, self.link != .needKey else { return }
            // 用 IP 连接失败、但局域网里发现了同一台电脑（IP 变了）：改用自动发现的地址
            var next = t
            if case .host = t, !self.wifiPcId.isEmpty,
               let pc = self.discovery.pcs.first(where: { $0.id == self.wifiPcId }) {
                next = pc.target
                self.target = next
            }
            self.startWiFi(next)
        }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func discovered(_ list: [Discovery.PC]) {
        // 没有指定连接目标时，可以自动连已配对的电脑。
        guard !manualDisconnect, !viaUSB, target == nil else { return }
        switch link {
        case .connected, .connecting, .needKey: return
        default: break
        }
        let keys = savedKeys
        if let pc = list.first(where: { $0.id == wifiPcId && keys[$0.id] != nil }) ?? list.first(where: { keys[$0.id] != nil }),
           let key = keys[pc.id] {
            connect(pc.target, key: key, pcId: pc.id)
        }
    }

    // MARK: - network events（在 connection.queue 上）

    private func onNetEvent(_ ev: Connection.Event, session: UUID) {
        switch ev {
        case .audio(let data):
            qLastMessage = CACurrentMediaTime()
            handleAudio(data)
        case .ready:
            qLastMessage = CACurrentMediaTime()
            qReadySession = session
            qExpectSeq = -1
        case .text(let obj):
            qLastMessage = CACurrentMediaTime()
            onMainForSession(session) {
                self.handleText(obj)
            }
        case .closed(let reason):
            qReadySession = nil
            onMainForSession(session) {
                if self.viaUSB { self.usbLost(reason ?? "USB 断开了"); return }
                self.link = .failed(reason ?? "连接断开了")
                self.scheduleReconnect()
            }
        case .badKey:
            qReadySession = nil
            onMainForSession(session) {
                if self.viaUSB { self.usbLost("USB 连接被拒绝"); return }
                self.reconnectWork?.cancel()
                var keys = self.savedKeys
                if !self.wifiPcId.isEmpty { keys.removeValue(forKey: self.wifiPcId) }
                self.savedKeys = keys
                self.link = .needKey
                self.stopAudio()
                self.showConnect = true
            }
        }
    }

    private func onMainForSession(_ session: UUID, _ handle: @escaping () -> Void) {
        DispatchQueue.main.async {
            guard self.connectionSession == session else { return }
            handle()
        }
    }

    private func handleAudio(_ d: Data) {
        guard d.count > 8, qListen else { return }
        shared.addIn(d.count)
        d.withUnsafeBytes { raw in
            guard let base = raw.baseAddress, raw[0] == 1 else { return }
            let seq = Int64(raw.loadUnaligned(fromByteOffset: 4, as: UInt32.self))
            if qExpectSeq >= 0 && seq != qExpectSeq {
                shared.addLost(Int((seq - qExpectSeq) & 0xFFFF_FFFF))
            }
            qExpectSeq = (seq + 1) & 0xFFFF_FFFF
            let frames = (raw.count - 8) / 4
            sq_player_push(audio.player, base.advanced(by: 8).assumingMemoryBound(to: Int16.self), Int32(frames), 2)
        }
    }

    private func handleText(_ m: [String: Any]) {
        switch m["type"] as? String {
        case "hello":
            if let rate = m["rate"] as? Double {
                sq_player_set_rates(audio.player, rate, AudioController.outputRate)
            }
            pcName = (m["name"] as? String) ?? pcName
            if let id = m["id"] as? String, !id.isEmpty { pcId = id }
            link = .connected
            retry = 0
            if !viaUSB { wifiPcId = pcId }
            if !viaUSB && !wifiPcId.isEmpty {
                var keys = savedKeys
                keys[wifiPcId] = key
                savedKeys = keys
            }
            if !viaUSB, let t = target, let data = try? JSONEncoder().encode(t) {
                defaults.set(data, forKey: "lastTarget")
                defaults.set(key, forKey: "lastKey")
                defaults.set(wifiPcId, forKey: "lastPcId")
                defaults.set(pcName, forKey: "lastPcName")
            }
            sendConfig()
            updateNowPlaying()
        case "pong":
            if let t = m["t"] as? Double { rttMs = CACurrentMediaTime() * 1000 - t }
        case "status":
            if let mic = m["mic"] as? [String: Any] {
                var info = MicInfo()
                info.state = (mic["state"] as? String) ?? "off"
                info.device = (mic["device"] as? String) ?? ""
                info.error = (mic["error"] as? String) ?? ""
                info.mine = (mic["mine"] as? Bool) ?? false
                info.busy = (mic["busy"] as? Bool) ?? false
                info.latencyMs = (mic["latencyMs"] as? Int) ?? 0
                info.denoise = (mic["denoise"] as? String) ?? "rnnoise"
                info.denoiseModes = (mic["denoiseModes"] as? [String]) ?? []
                if Date() < denoiseSentAt.addingTimeInterval(1.5) { info.denoise = micInfo.denoise }   // 刚改过，等电脑确认
                if info != micInfo { micInfo = info }
            }
        default:
            break
        }
    }

    /// 选择降噪方式：发给电脑端，界面先改过来
    func setDenoise(_ mode: DenoiseMode) {
        var info = micInfo
        info.denoise = mode.rawValue
        micInfo = info
        denoiseSentAt = Date()
        connection.sendJSON(["type": "config", "denoise": mode.rawValue])
    }

    private func sendConfig() {
        connection.sendJSON(["type": "config", "listen": listening, "mic": micOn && audio.isRunning,
                             "micRate": Int(audio.micSampleRate)])
    }

    // MARK: - listen / mic

    func setListening(_ on: Bool) {
        listening = on
        applyAudio()
    }

    func setMic(_ on: Bool) {
        if !on {
            micPermissionRequest = nil
            micOn = false
            applyAudio()
            return
        }
        let request = UUID()
        micPermissionRequest = request
        AppModel.requestMicPermission { granted in
            DispatchQueue.main.async {
                guard self.micPermissionRequest == request else { return }
                self.micPermissionRequest = nil
                if granted {
                    self.micPermissionDenied = false
                    self.micOn = true
                    self.applyAudio()
                } else {
                    self.micPermissionDenied = true
                    self.micOn = false
                }
            }
        }
    }

    static func requestMicPermission(_ done: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: done)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(done)
        }
    }

    private func applyAudio() {
        audio.apply(listen: listening, mic: micOn, echoCancel: echoCancel)
        let listen = listening, mic = micOn && audio.isRunning
        let chunk = max(1, Int((audio.micSampleRate / 100).rounded()))
        connection.queue.async {
            self.qListen = listen
            self.qMic = mic
            self.qMicChunk = chunk
            sq_ring_discard(self.audio.micRing)
        }
        sendConfig()
        updateNowPlaying()
    }

    private func audioChanged() {
        audioError = audio.lastError
        if (listening || micOn) && !audio.isRunning && audio.lastError != nil {
            // 启动失败：把开关拨回去，别让界面显示“在听”
            if micOn && audio.lastError != nil { micOn = false }
        }
        let mic = micOn && audio.isRunning
        let chunk = max(1, Int((audio.micSampleRate / 100).rounded()))
        connection.queue.async {
            self.qMic = mic
            self.qMicChunk = chunk
            sq_ring_discard(self.audio.micRing)
        }
        // 路由变化/中断恢复可能改变采样率，电脑端也必须同步。
        sendConfig()
    }

    // MARK: - mic sender（connection.queue 上的 5 ms 定时器）

    private func startMicTimer() {
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: connection.queue)
        t.schedule(deadline: .now() + .milliseconds(5), repeating: .milliseconds(5), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in self?.pumpMic() }
        t.resume()
        micTimer = t
    }

    private func pumpMic() {
        watchdog()
        let ring = audio.micRing
        guard qMic, let session = qReadySession, session == connection.session else {
            sq_ring_discard(ring)
            return
        }
        let chunk = qMicChunk
        var budget = 8                                   // 每次最多发 80 ms，追积压
        while budget > 0 && Int(sq_ring_available(ring)) >= chunk {
            budget -= 1
            var packet = Data(count: 8 + chunk * 2)
            var peak: Float = 0
            micFloats.withUnsafeMutableBufferPointer { fb in
                _ = sq_ring_read(ring, fb.baseAddress, Int32(chunk))
                packet.withUnsafeMutableBytes { raw in
                    raw.storeBytes(of: UInt8(2), toByteOffset: 0, as: UInt8.self)
                    raw.storeBytes(of: qMicSeq.littleEndian, toByteOffset: 4, as: UInt32.self)
                    let out = raw.baseAddress!.advanced(by: 8).assumingMemoryBound(to: Int16.self)
                    peak = sq_float_to_int16(fb.baseAddress, out, Int32(chunk), qMicGain)
                }
            }
            qMicSeq &+= 1
            shared.addOut(packet.count, peak: peak)
            connection.sendBinaryOnQueue(packet)
        }
        // 网络卡住时积压超过 0.3 秒：丢掉旧的，保证实时
        if Int(sq_ring_available(ring)) > chunk * 30 { sq_ring_discard(ring) }
    }

    /// 超过 5 秒没收到电脑的任何消息：认为连接已死，主动重连
    private func watchdog() {
        guard let session = qReadySession, session == connection.session,
              CACurrentMediaTime() - qLastMessage > 5 else { return }
        connection.finishOnQueue(session, reason: "很久没收到电脑的数据")
    }

    // MARK: - UI timer

    private func uiTick() {
        tick += 1
        let st = audio.stats()
        let net: (bytesIn: Int, bytesOut: Int, lost: Int)? = tick % 4 == 0 ? shared.take() : nil
        var s = stats
        s.buffering = st.state == 0
        s.bufferMs = Int(st.emaMs.rounded())
        s.targetMs = Int(st.targetMs.rounded())
        s.underruns = Int(st.underruns)
        s.rttMs = rttMs.map { Int($0.rounded()) }
        s.latencyMs = Int((st.emaMs + audio.outputLatency * 1000 + (rttMs ?? 10) / 2 + 10).rounded())
        if let net = net {
            s.netKbps = net.bytesIn * 8 / 1000
            s.micKbps = net.bytesOut * 8 / 1000
            s.lostFrames += net.lost
        }
        let peak = micOn ? shared.takePeak() : 0
        if peak != micLevel { micLevel = peak }
        if s != stats { stats = s }
        if tick % 4 == 0 && link == .connected {
            connection.sendJSON(["type": "ping", "t": CACurrentMediaTime() * 1000])
            if listening {
                connection.sendJSON(["type": "stats", "lat": s.latencyMs, "buf": s.bufferMs, "tgt": s.targetMs,
                                     "und": s.underruns, "rtt": s.rttMs ?? 0])
            }
        }
    }

    /// 电平表每帧读取（线程安全）
    func pcLevel() -> Float {
        guard listening else { return 0 }
        return audio.stats().rms
    }

    // MARK: - lifecycle

    func becameActive() {
        audio.ensureRunning()
        if usbEnabled && !manualDisconnect { usb.start() }
        if !viaUSB, let t = target, link != .connected, link != .connecting, link != .needKey {
            startWiFi(t)
        }
        discovery.start()
    }

    func enteredBackground() {
        if !listening && !micOn {
            // 什么都没开：系统马上会挂起 App，先断开，回来再连
            disconnectNetwork()
            let wasUSB = viaUSB
            viaUSB = false
            if wasUSB {
                pcId = wifiPcId
                pcName = target?.label ?? ""
            }
            if link != .needKey {
                if target != nil { link = .failed("App 在后台") } else if wasUSB { link = .idle }
            }
            discovery.stop()
        }
    }

    // MARK: - lock screen / control center

    private func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.setListening(true); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.setListening(false); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            self.setListening(!self.listening)
            return .success
        }
    }

    private func updateNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        if listening || micOn {
            var info: [String: Any] = [
                MPMediaItemPropertyTitle: listening ? "电脑的声音" : "正在当麦克风",
                MPMediaItemPropertyArtist: pcName.isEmpty ? "声桥" : pcName,
                MPNowPlayingInfoPropertyIsLiveStream: true,
                MPNowPlayingInfoPropertyPlaybackRate: listening ? 1.0 : 0.0,
            ]
            if let img = UIImage(named: "NowPlaying") {
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            }
            center.nowPlayingInfo = info
        } else {
            center.nowPlayingInfo = nil
        }
    }
}
