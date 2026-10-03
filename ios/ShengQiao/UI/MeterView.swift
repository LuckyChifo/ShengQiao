import Foundation
import SwiftUI

/// 指针的物理模型：二阶阻尼（ζ≈0.8，约 300 ms 到位），模仿机械 VU 表
final class NeedlePhysics {
    private var p: Double = 0
    private var v: Double = 0
    private var last: Double?

    func step(target: Double, now: Double) -> Double {
        let dt = min(0.05, max(0.0, now - (last ?? now)))
        last = now
        let w0 = 14.0, zeta = 0.8
        let h = dt / 4
        for _ in 0..<4 {
            let acc = w0 * w0 * (target - p) - 2 * zeta * w0 * v
            v += acc * h
            p += v * h
        }
        if p < -0.02 { p = -0.02; v = 0 }
        return p
    }
}

/// 复古 VU 表：0 VU 对齐 -14 dBFS，刻度按真实 VU 表的电压比例画
struct MeterView: View {
    let level: () -> Float
    let active: Bool
    let caption: String
    @State private var physics = NeedlePhysics()

    private static let cx = 160.0, cy = 206.0, radius = 168.0, span = 46.0

    var body: some View {
        TimelineView(.animation) { timeline in
            let target = active ? MeterView.deflection(MeterView.vu(fromRMS: level())) : 0
            let pos = physics.step(target: target, now: timeline.date.timeIntervalSinceReferenceDate)
            Canvas { ctx, size in
                MeterView.draw(ctx: ctx, size: size, position: pos, caption: caption, glow: active)
            }
        }
        .aspectRatio(320.0 / 176.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement()
        .accessibilityLabel("电平表")
    }

    static func vu(fromRMS rms: Float) -> Double {
        rms <= 1e-6 ? -60 : 20 * log10(Double(rms)) + 14
    }

    static func deflection(_ vu: Double) -> Double { pow(10, (vu - 3) / 20) }

    static func angle(_ d: Double) -> Double { -span + 2 * span * min(1.04, max(0, d)) }

    static func polar(_ deg: Double, _ r: Double) -> CGPoint {
        let a = deg * .pi / 180
        return CGPoint(x: cx + r * sin(a), y: cy - r * cos(a))
    }

    static func arc(from a0: Double, to a1: Double, r: Double) -> Path {
        var p = Path()
        let steps = 48
        for i in 0...steps {
            let a = a0 + (a1 - a0) * Double(i) / Double(steps)
            let pt = polar(a, r)
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        return p
    }

    static func draw(ctx: GraphicsContext, size: CGSize, position: Double, caption: String, glow: Bool) {
        var c = ctx
        let s = size.width / 320
        c.scaleBy(x: s, y: s)
        let face = Path(CGRect(x: 0, y: 0, width: 320, height: 176))
        c.fill(face, with: .linearGradient(Gradient(colors: [Color(hex: 0xF7EAD0), Theme.parchment2]),
                                           startPoint: .zero, endPoint: CGPoint(x: 0, y: 176)))
        if glow {
            c.fill(face, with: .radialGradient(Gradient(colors: [Color(hex: 0xFFCF7A).opacity(0.45), .clear]),
                                               center: CGPoint(x: 160, y: 160), startRadius: 0, endRadius: 260))
        }
        // 红区和刻度弧
        c.stroke(arc(from: angle(deflection(0)), to: angle(1), r: radius - 8), with: .color(Theme.needle), lineWidth: 7)
        c.stroke(arc(from: angle(deflection(-20)), to: angle(deflection(0)), r: radius - 13), with: .color(Theme.ink), lineWidth: 1.2)
        let big: Set<Int> = [-20, -10, -7, -5, -3, 0, 3]
        for v in [-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3] {
            let a = angle(deflection(Double(v)))
            let isBig = big.contains(v)
            var tick = Path()
            tick.move(to: polar(a, radius - 13))
            tick.addLine(to: polar(a, radius - (isBig ? 26 : 21)))
            c.stroke(tick, with: .color(v > 0 ? Theme.needle : Theme.ink), lineWidth: isBig ? 1.8 : 1.1)
            if isBig {
                let label = v > 0 ? "+\(v)" : "\(v)"
                c.draw(Text(label).font(.system(size: 13, weight: .regular, design: .rounded))
                        .foregroundColor(v > 0 ? Theme.needle : Theme.ink),
                       at: polar(a, radius - 40))
            }
        }
        if !caption.isEmpty {
            c.draw(Text(String(caption.prefix(26))).font(.system(size: 11, weight: .medium, design: .rounded))
                    .foregroundColor(Color(hex: 0x8C7558)), at: CGPoint(x: 160, y: 120))
        }
        // 指针
        var needle = Path()
        needle.move(to: CGPoint(x: cx, y: cy))
        needle.addLine(to: polar(angle(position), radius - 2))
        c.stroke(needle, with: .color(Theme.needle), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
        // 转轴罩
        c.fill(Path(ellipseIn: CGRect(x: 102, y: 150, width: 116, height: 68)), with: .color(Theme.cabinet))
        c.fill(Path(ellipseIn: CGRect(x: 102, y: 146, width: 116, height: 64)), with: .color(Theme.cabinet2))
    }
}

/// 细长的麦克风电平条
struct LevelBar: View {
    let level: Float
    var body: some View {
        GeometryReader { geo in
            let db = level > 0 ? 20 * log10(Double(level)) : -90
            let frac = max(0, min(1, (db + 60) / 60))
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.cabinet2)
                Capsule().fill(LinearGradient(colors: [Color(hex: 0xC98A2E), Theme.amber, Color(hex: 0xFF7A52)],
                                              startPoint: .leading, endPoint: .trailing))
                    .frame(width: geo.size.width * frac)
                    .animation(.linear(duration: 0.12), value: frac)
            }
        }
        .frame(height: 8)
    }
}
