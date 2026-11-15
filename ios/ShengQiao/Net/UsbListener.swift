import Foundation
import Network

/// USB 直连：在手机的本机回环地址上监听，电脑端经 usbmux 连进来（见 pc/app/usbmux.py）。
///
/// - 只绑定回环接口：局域网里的其他设备连不上，所以不需要访问码，数据线本身就是信任。
/// - 这里是 WebSocket 服务端（系统帮忙做握手），连进来的 NWConnection 交给 `Connection.adopt`，
///   之后和 Wi-Fi 连接走同一套消息（hello / config / 音频帧）。
final class UsbListener {
    static let port: NWEndpoint.Port = 23334

    /// 有电脑连进来了（在内部队列上回调）
    var onConnection: ((NWConnection) -> Void)?

    private let queue = DispatchQueue(label: "shengqiao.usb")
    private var listener: NWListener?
    private var wanted = false

    func start() {
        queue.async {
            self.wanted = true
            self.open()
        }
    }

    func stop() {
        queue.async {
            self.wanted = false
            self.listener?.cancel()
            self.listener = nil
        }
    }

    private func open() {
        guard wanted, listener == nil else { return }
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 1 << 20
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        let params = NWParameters(tls: nil, tcp: tcp)
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        params.requiredInterfaceType = .loopback
        params.allowLocalEndpointReuse = true

        let l: NWListener
        do {
            l = try NWListener(using: params, on: UsbListener.port)
        } catch {
            retryLater()
            return
        }
        l.newConnectionHandler = { [weak self] c in self?.onConnection?(c) }
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self = self, let l = l, l === self.listener else { return }
            if case .failed = state {
                l.cancel()
                self.listener = nil
                self.retryLater()
            }
        }
        listener = l
        l.start(queue: queue)
    }

    private func retryLater() {
        queue.asyncAfter(deadline: .now() + 2) { self.open() }
    }
}
