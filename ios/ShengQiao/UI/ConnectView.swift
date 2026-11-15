import SwiftUI

struct ConnectView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var discovery: Discovery
    @Environment(\.dismiss) private var dismiss

    @State private var showScanner = false
    @State private var address = ""
    @State private var code = ""
    @State private var askCodeFor: Discovery.PC?
    @State private var codeInput = ""
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            List {
                if model.link == .needKey {
                    Section {
                        Text("访问码不对。请扫电脑上的二维码，或者在下面输入电脑上显示的访问码。")
                            .foregroundColor(Theme.danger)
                    }
                    .listRowBackground(Theme.cabinet)
                }

                Section {
                    Toggle(isOn: $model.usbEnabled) {
                        Label("USB 直连", systemImage: "cable.connector").foregroundColor(Theme.text)
                    }
                    if model.viaUSB {
                        Label("已通过数据线连着电脑，不占 Wi-Fi", systemImage: "checkmark.circle")
                            .font(.callout).foregroundColor(Theme.amber)
                    }
                } header: {
                    Text("数据线")
                } footer: {
                    Text("用数据线把手机接到电脑，保持声桥在前台，电脑上的声桥会自动连过来。电脑需要装 iTunes 或“Apple 设备”App。")
                }
                .listRowBackground(Theme.cabinet)

                Section {
                    if discovery.pcs.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView().tint(Theme.muted)
                            Text("正在查找…电脑上要开着声桥，手机和电脑连同一个 Wi-Fi。")
                                .font(.callout).foregroundColor(Theme.muted)
                        }
                    }
                    ForEach(discovery.pcs) { pc in
                        Button {
                            if let k = model.savedKey(for: pc.id) {
                                model.connect(pc.target, key: k, pcId: pc.id)
                                dismiss()
                            } else {
                                codeInput = ""
                                askCodeFor = pc
                            }
                        } label: {
                            HStack {
                                Image(systemName: "desktopcomputer").foregroundColor(Theme.amber)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(pc.name).foregroundColor(Theme.text)
                                    Text(model.savedKey(for: pc.id) == nil ? "第一次连接需要访问码" : "已配对")
                                        .font(.caption).foregroundColor(Theme.muted)
                                }
                                Spacer()
                                if model.pcId == pc.id && model.link == .connected {
                                    Image(systemName: "checkmark").foregroundColor(Theme.amber)
                                }
                            }
                        }
                    }
                } header: {
                    Text("附近的电脑")
                } footer: {
                    if discovery.failed {
                        Text("局域网查找不可用。请在 设置 → 隐私与安全性 → 本地网络 里打开声桥，或者用扫码连接。")
                    }
                }
                .listRowBackground(Theme.cabinet)

                Section {
                    Button {
                        showScanner = true
                    } label: {
                        Label("扫码连接", systemImage: "qrcode.viewfinder").foregroundColor(Theme.amber)
                    }
                } footer: {
                    Text("扫电脑上声桥控制页里的二维码，地址和访问码一次就填好。")
                }
                .listRowBackground(Theme.cabinet)

                Section {
                    TextField("电脑地址，例如 192.168.1.23", text: $address)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("访问码", text: $code)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("连接") {
                        if model.connect(address: address, code: code) {
                            dismiss()
                        } else {
                            errorText = "地址格式不对，应该像 192.168.1.23 或 192.168.1.23:23333"
                        }
                    }
                    .foregroundColor(Theme.amber)
                    .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
                } header: {
                    Text("手动输入")
                } footer: {
                    if let e = errorText { Text(e).foregroundColor(Theme.danger) }
                }
                .listRowBackground(Theme.cabinet)

                if model.hasTarget || model.viaUSB {
                    Section {
                        Button("断开连接", role: .destructive) {
                            model.disconnect()
                        }
                    }
                    .listRowBackground(Theme.cabinet)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.walnut.ignoresSafeArea())
            .navigationTitle("连接电脑")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.foregroundColor(Theme.amber)
                }
            }
            .sheet(isPresented: $showScanner) {
                ScannerSheet { text in
                    showScanner = false
                    if model.connect(address: text, code: "") {
                        dismiss()
                    } else {
                        errorText = "这个二维码不是声桥的"
                    }
                }
            }
            .alert("输入访问码", isPresented: Binding(get: { askCodeFor != nil }, set: { if !$0 { askCodeFor = nil } })) {
                TextField("6 位访问码", text: $codeInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("连接") {
                    if let pc = askCodeFor {
                        model.connect(pc.target, key: codeInput, pcId: pc.id)
                        askCodeFor = nil
                        dismiss()
                    }
                }
                Button("取消", role: .cancel) { askCodeFor = nil }
            } message: {
                Text("电脑上的声桥控制页里能看到访问码。")
            }
        }
        .preferredColorScheme(.dark)
        .tint(Theme.amber)
    }
}

/// 扫码页：相机画面 + 提示 + 取消按钮
struct ScannerSheet: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var message: String?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                QRScannerView(onCode: onCode, onMessage: { message = $0 })
                    .ignoresSafeArea()
                Text(message ?? "对准电脑上声桥控制页里的二维码")
                    .font(.callout)
                    .padding(.vertical, 10).padding(.horizontal, 16)
                    .background(Capsule().fill(Color.black.opacity(0.6)))
                    .foregroundColor(.white)
                    .padding(.bottom, 40)
            }
            .navigationTitle("扫码连接")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
