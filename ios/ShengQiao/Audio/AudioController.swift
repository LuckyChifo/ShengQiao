import AVFoundation

/// 管理 AVAudioEngine：
/// - 收听：AVAudioSourceNode 从 C 播放核心（SQPlayer）拉数据
/// - 麦克风：AVAudioSinkNode 把输入写进环形缓冲（SQRing），由网络线程取走发送
/// 每次开关功能都会重建引擎，避免旧状态（比如语音处理、输入节点）残留。
final class AudioController {
    let player: OpaquePointer
    let micRing: OpaquePointer
    static let outputRate: Double = 48000

    private(set) var engine: AVAudioEngine?
    private(set) var isRunning = false
    private(set) var micSampleRate: Double = 48000
    private(set) var lastError: String?
    private var wantListen = false
    private var wantMic = false
    private var wantEcho = true
    private var observers: [NSObjectProtocol] = []
    private var engineObserver: NSObjectProtocol?
    private var interrupted = false

    /// 引擎状态变化（启动、出错、被打断）时回调，在主线程
    var onChange: (() -> Void)?

    init() {
        player = sq_player_create(48000, AudioController.outputRate)!
        micRing = sq_ring_create(1 << 16)!
        let nc = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: session,
                                        queue: .main) { [weak self] note in
            self?.handleInterruption(note)
        })
        observers.append(nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session,
                                        queue: .main) { [weak self] _ in
            self?.rebuild()
        })
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        if let o = engineObserver { NotificationCenter.default.removeObserver(o) }
        sq_player_destroy(player)
        sq_ring_destroy(micRing)
    }

    var needsEngine: Bool { wantListen || wantMic }

    /// 设置想要的状态并（必要时）重建引擎。主线程调用。
    func apply(listen: Bool, mic: Bool, echoCancel: Bool) {
        let changed = listen != wantListen || mic != wantMic || (mic && echoCancel != wantEcho)
        wantListen = listen
        wantMic = mic
        wantEcho = echoCancel
        if changed || (needsEngine && !isRunning) {
            rebuild()
        }
    }

    func stop() {
        teardown()
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        onChange?()
    }

    // MARK: - engine

    private func teardown() {
        if let o = engineObserver {
            NotificationCenter.default.removeObserver(o)
            engineObserver = nil
        }
        if let e = engine {
            e.stop()
            if let input = inputNodeIfUsed(e), wantEchoWasEnabled {
                try? input.setVoiceProcessingEnabled(false)
            }
            e.reset()
        }
        engine = nil
        isRunning = false
        wantEchoWasEnabled = false
    }

    private var wantEchoWasEnabled = false
    private var usedInput = false

    private func inputNodeIfUsed(_ e: AVAudioEngine) -> AVAudioInputNode? {
        usedInput ? e.inputNode : nil
    }

    func rebuild() {
        teardown()
        usedInput = false
        guard needsEngine else {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            onChange?()
            return
        }
        do {
            try configureSession()
            try startEngine(useSinkNode: true)
            lastError = nil
        } catch {
            // AVAudioSinkNode 在个别系统版本上和语音处理不兼容：退回 installTap
            if wantMic {
                teardown()
                usedInput = false
                do {
                    try startEngine(useSinkNode: false)
                    lastError = nil
                } catch {
                    lastError = "音频启动失败：\(error.localizedDescription)"
                    teardown()
                }
            } else {
                lastError = "音频启动失败：\(error.localizedDescription)"
                teardown()
            }
        }
        onChange?()
    }

    private func configureSession() throws {
        let s = AVAudioSession.sharedInstance()
        if wantMic {
            try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
        } else {
            try s.setCategory(.playback, mode: .default, options: [])
        }
        try? s.setPreferredSampleRate(48000)
        try? s.setPreferredIOBufferDuration(0.005)
        try s.setActive(true)
    }

    private func startEngine(useSinkNode: Bool) throws {
        let e = AVAudioEngine()
        engine = e

        if wantMic {
            let input = e.inputNode
            usedInput = true
            if wantEcho {
                try input.setVoiceProcessingEnabled(true)
                wantEchoWasEnabled = true
            }
            let inFmt = input.outputFormat(forBus: 0)
            guard inFmt.sampleRate > 0, inFmt.channelCount > 0 else {
                throw NSError(domain: "ShengQiao", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "没有可用的麦克风"])
            }
            micSampleRate = inFmt.sampleRate
            let ring = micRing
            if useSinkNode {
                let sink = AVAudioSinkNode { _, frameCount, abl -> OSStatus in
                    let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: abl))
                    guard let first = buffers.first, let raw = first.mData else { return noErr }
                    let data = raw.assumingMemoryBound(to: Float.self)
                    sq_ring_write_strided(ring, data, Int32(frameCount), Int32(max(1, first.mNumberChannels)))
                    return noErr
                }
                e.attach(sink)
                e.connect(input, to: sink, format: inFmt)
            } else {
                input.installTap(onBus: 0, bufferSize: 480, format: inFmt) { buffer, _ in
                    guard let ch = buffer.floatChannelData else { return }
                    let stride = buffer.stride
                    sq_ring_write_strided(ring, ch[0], Int32(buffer.frameLength), Int32(max(1, stride)))
                }
            }
        }

        if wantListen {
            let fmt = AVAudioFormat(standardFormatWithSampleRate: AudioController.outputRate, channels: 2)!
            let p = player
            let source = AVAudioSourceNode(format: fmt) { _, _, frameCount, abl -> OSStatus in
                let buffers = UnsafeMutableAudioBufferListPointer(abl)
                guard buffers.count >= 2,
                      let l = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                      let r = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
                sq_player_render(p, l, r, Int32(frameCount))
                return noErr
            }
            e.attach(source)
            e.connect(source, to: e.mainMixerNode, format: fmt)
            sq_player_request_reset(player)          // 从最新的声音开始
        }

        engineObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
                                                                object: e, queue: .main) { [weak self] _ in
            // 插拔耳机、连蓝牙等会改变硬件格式，引擎会自己停掉，重建即可
            guard let self = self, self.engine === e else { return }
            self.rebuild()
        }
        e.prepare()
        try e.start()
        isRunning = true
    }

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            interrupted = true
            isRunning = false
            onChange?()
        case .ended:
            interrupted = false
            if needsEngine {
                // 电话挂断等：延迟一点再恢复，系统音频会话需要时间交还
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                    guard let self = self, self.needsEngine, !self.interrupted else { return }
                    self.rebuild()
                }
            }
        @unknown default:
            break
        }
    }

    /// 回到前台时调用：如果引擎被系统停了就恢复
    func ensureRunning() {
        if needsEngine && (engine == nil || !(engine?.isRunning ?? false)) && !interrupted {
            rebuild()
        }
    }

    // MARK: - stats

    func stats() -> SQPlayerStats {
        var st = SQPlayerStats()
        sq_player_get_stats(player, &st)
        return st
    }

    var outputLatency: Double {
        let s = AVAudioSession.sharedInstance()
        return s.outputLatency + s.ioBufferDuration
    }
}
