import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

/// 和电脑端控制页同一套颜色：胡桃木机身、羊皮纸表盘、琥珀色指示灯
enum Theme {
    static let walnut = Color(hex: 0x1C1612)
    static let cabinet = Color(hex: 0x2A211B)
    static let cabinet2 = Color(hex: 0x352A22)
    static let parchment = Color(hex: 0xF3E3C3)
    static let parchment2 = Color(hex: 0xE9D3AA)
    static let ink = Color(hex: 0x2B1D12)
    static let amber = Color(hex: 0xFFB547)
    static let needle = Color(hex: 0xA8301C)
    static let text = Color(hex: 0xF1E6D6)
    static let muted = Color(hex: 0xA8957F)
    static let line = Color(hex: 0x3D3128)
    static let danger = Color(hex: 0xFF8A6B)
}
