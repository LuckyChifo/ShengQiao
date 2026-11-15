import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                MeterView(level: model.pcLevel, active: model.listening && model.link == .connected,
                          caption: model.pcName)
                    .padding(10)
                    .background(
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .fill(LinearGradient(colors: [Color(hex: 0x3A2E25), Color(hex: 0x261E18)],
                                                 startPoint: .top, endPoint: .bottom))
                            .shadow(color: .black.opacity(0.35), radius: 14, y: 8)
                    )
                HStack(spacing: 14) {
                    BigToggle(title: model.listening ? "正在听电脑" : "听电脑",
                              systemImage: model.listening ? "speaker.wave.2.fill" : "speaker.wave.2",
                              isOn: model.listening) { model.setListening(!model.listening) }
                    BigToggle(title: model.micOn ? "正在当麦克风" : "当麦克风",
                              systemImage: model.micOn ? "mic.fill" : "mic",
                              isOn: model.micOn) { model.setMic(!model.micOn) }
                }
                statusBlock
                if model.micOn {
                    micSection
                }
                if model.listening {
                    listenSection
                }
                if model.micPermissionDenied {
                    permissionNote
                }
                detailsSection
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 28)
        }
        .background(Theme.walnut.ignoresSafeArea())
        .foregroundColor(Theme.text)
        .sheet(isPresented: $model.showConnect) {
            ConnectView(discovery: model.discovery).environmentObject(model)
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                if model.link == .idle && !model.hasTarget { model.showConnect = true }
            }
        }
    }

    // MARK: - header

    private var header: some View {
        HStack {
            Text("声桥").font(.system(size: 26, weight: .semibold)).kerning(3)
            Spacer()
            Button { model.showConnect = true } label: {
                HStack(spacing: 8) {
                    Circle().fill(lampColor).frame(width: 9, height: 9)
                        .shadow(color: lampColor == Theme.amber ? Theme.amber.opacity(0.7) : .clear, radius: 5)
                    Text(linkTitle).font(.subheadline).foregroundColor(Theme.muted).lineLimit(1)
                    Image(systemName: "chevron.right").font(.caption).foregroundColor(Theme.muted)
                }
                .padding(.vertical, 8).padding(.horizontal, 12)
                .background(Capsule().fill(Theme.cabinet))
            }
            .accessibilityLabel("连接：\(linkTitle)")
        }
        .padding(.top, 8)
    }

    private var lampColor: Color {
        switch model.link {
        case .connected: return Theme.amber
        case .failed, .needKey: return Theme.danger
        default: return Color(hex: 0x4A3B30)
        }
    }

    private var linkTitle: String {
        switch model.link {
        case .idle: return "连接电脑"
        case .connecting: return "正在连接…"
        case .connected:
            let name = model.pcName.isEmpty ? "已连接" : model.pcName
            return model.viaUSB ? name + " · USB" : name
        case .failed: return "连不上"
        case .needKey: return "访问码不对"
        }
    }

    // MARK: - status

    private var statusBlock: some View {
        let (text, alert) = statusLine
        return Text(text)
            .font(.callout)
            .foregroundColor(alert ? Theme.danger : Theme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .animation(nil, value: text)
    }

    private var statusLine: (String, Bool) {
        switch model.link {
        case .idle:
            return ("还没连接电脑。点右上角，选择电脑或扫码连接。", false)
        case .connecting:
            return ("正在连接\(model.pcName.isEmpty ? "电脑" : model.pcName)…", false)
        case .failed(let reason):
            return ("\(reason)。正在重试，确认电脑上的声桥开着、手机和电脑在同一个 Wi-Fi。", true)
        case .needKey:
            return ("访问码不对。请重新扫码，或输入电脑上显示的访问码。", true)
        case .connected:
            if let e = model.audioError { return (e, true) }
            if !model.listening && !model.micOn {
                return ("已连上。打开“听电脑”，电脑的声音就从手机出来；打开“当麦克风”，手机就是电脑的麦克风。", false)
            }
            var lines: [String] = []
            var alert = false
            if model.listening {
                let s = model.stats
                lines.append(s.buffering ? "听电脑：正在缓冲…" : "听电脑：延迟约 \(Int((Double(s.latencyMs) / 10).rounded()) * 10) 毫秒")
            }
            if model.micOn {
                let m = model.micInfo
                if m.busy { lines.append("麦克风：另一台手机正在用，这台的声音不会传过去"); alert = true }
                else if m.state == "no-cable" { lines.append("麦克风：电脑上没找到 VB-CABLE，请先安装"); alert = true }
                else if m.state == "error" { lines.append("麦克风：电脑端出错（\(m.error)）"); alert = true }
                else if m.state == "running" && m.mine { lines.append("麦克风：已接入电脑的 \(shortDevice(m.device))") }
                else { lines.append("麦克风：电脑正在打开虚拟麦克风…") }
            }
            return (lines.joined(separator: "\n"), alert)
        }
    }

    private func shortDevice(_ name: String) -> String {
        if let r = name.range(of: " (") { return String(name[..<r.lowerBound]) }
        return name.isEmpty ? "虚拟麦克风" : name
    }

    // MARK: - sections

    private var micSection: some View {
        Card {
            HStack {
                Text("麦克风").foregroundColor(Theme.muted)
                LevelBar(level: model.micLevel)
            }
            Divider().background(Theme.line)
            VStack(alignment: .leading, spacing: 8) {
                Text("降噪").foregroundColor(Theme.muted)
                Picker("降噪", selection: denoiseBinding) {
                    ForEach(DenoiseMode.allCases) { m in
                        Text(m.title).tag(m)
                    }
                }
                .pickerStyle(.segmented)
                Text(currentDenoise.help).font(.footnote).foregroundColor(Theme.muted)
            }
            Divider().background(Theme.line)
            Toggle(isOn: $model.echoCancel) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("消除回声")
                    Text("iPhone 系统的语音处理：用手机外放听电脑声音时打开，电脑那头就听不到回声（也会顺带压一点噪声）。和上面的降噪互不影响。戴耳机可以关掉，音质更好。")
                        .font(.footnote).foregroundColor(Theme.muted)
                }
            }
            .tint(Theme.amber)
            Divider().background(Theme.line)
            SliderRow(title: "麦克风音量", value: $model.micGain, range: 0.5...4, step: 0.1)
            Text("在电脑上的 QQ、微信、腾讯会议、OBS 等软件里，把麦克风选成「CABLE Output」。")
                .font(.footnote).foregroundColor(Theme.muted)
        }
    }

    private var currentDenoise: DenoiseMode {
        DenoiseMode(rawValue: model.micInfo.denoise) ?? .rnnoise
    }

    private var denoiseBinding: Binding<DenoiseMode> {
        Binding(get: { currentDenoise }, set: { model.setDenoise($0) })
    }

    private var listenSection: some View {
        Card {
            HStack {
                Text("延迟").foregroundColor(Theme.muted)
                Picker("延迟", selection: $model.preset) {
                    ForEach(Preset.allCases) { p in Text(p.title).tag(p) }
                }
                .pickerStyle(.segmented)
            }
            Text(model.preset.help).font(.footnote).foregroundColor(Theme.muted)
            Divider().background(Theme.line)
            SliderRow(title: "音量", value: $model.volume, range: 0...2, step: 0.05)
            Text("锁屏或切到别的 App 也会继续播放。手机侧边的音量键同样有效。")
                .font(.footnote).foregroundColor(Theme.muted)
        }
    }

    private var permissionNote: some View {
        Card {
            Text("麦克风权限被关闭了，声桥没法把声音传给电脑。")
            Button("去设置里打开") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .foregroundColor(Theme.amber)
        }
    }

    private var detailsSection: some View {
        DisclosureGroup {
            let s = model.stats
            VStack(alignment: .leading, spacing: 6) {
                StatRow(name: "估计延迟", value: model.listening ? "\(s.latencyMs) 毫秒" : "—")
                StatRow(name: "缓冲", value: model.listening ? "\(s.bufferMs) / \(s.targetMs) 毫秒" : "—")
                StatRow(name: "卡顿次数", value: model.listening ? "\(s.underruns)" : "—")
                StatRow(name: "往返时间", value: s.rttMs.map { "\($0) 毫秒" } ?? "—")
                StatRow(name: "收到的数据", value: String(format: "%.2f Mbps", Double(s.netKbps) / 1000))
                StatRow(name: "麦克风上传", value: model.micOn ? "\(s.micKbps) kbps" : "—")
                StatRow(name: "麦克风延迟（电脑端）", value: model.micOn && model.micInfo.mine ? "\(model.micInfo.latencyMs) 毫秒" : "—")
                StatRow(name: "丢失的数据", value: "\(s.lostFrames) 块")
            }
            .padding(.top, 8)
        } label: {
            Text("连接详情").foregroundColor(Theme.muted)
        }
        .tint(Theme.muted)
        .padding(.top, 4)
    }
}

// MARK: - components

struct BigToggle: View {
    let title: String
    let systemImage: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            action()
        }) {
            VStack(spacing: 10) {
                Image(systemName: systemImage).font(.system(size: 30, weight: .semibold))
                Text(title).font(.headline)
            }
            .frame(maxWidth: .infinity, minHeight: 112)
            .foregroundColor(isOn ? Theme.walnut : Theme.amber)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(isOn ? Theme.amber : Theme.cabinet)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(isOn ? Theme.amber : Theme.amber.opacity(0.35), lineWidth: 1.5)
            )
            .shadow(color: isOn ? Theme.amber.opacity(0.35) : .clear, radius: 16)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.cabinet))
    }
}

struct SliderRow: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double

    var body: some View {
        HStack(spacing: 12) {
            Text(title).foregroundColor(Theme.muted)
            Slider(value: $value, in: range, step: step).tint(Theme.amber)
            Text("\(Int((value * 100).rounded()))%")
                .font(.system(.callout, design: .rounded).monospacedDigit())
                .frame(width: 52, alignment: .trailing)
        }
    }
}

struct StatRow: View {
    let name: String
    let value: String
    var body: some View {
        HStack {
            Text(name).foregroundColor(Theme.muted)
            Spacer()
            Text(value).font(.system(.callout, design: .rounded).monospacedDigit())
        }
        .font(.callout)
    }
}
