import Foundation
import Network

/// 连到哪台电脑
enum ConnTarget: Equatable, Codable {
    case service(name: String, type: String, domain: String)   // 局域网自动发现（Bonjour）
    case host(String, UInt16)                                   // 扫码 / 手动输入

    var endpoint: NWEndpoint {
        switch self {
        case let .service(name, type, domain):
            return .service(name: name, type: type, domain: domain, interface: nil)
        case let .host(host, port):
            return .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 23333)
        }
    }

    var label: String {
        switch self {
        case let .service(name, _, _): return name
        case let .host(host, port): return "\(host):\(port)"
        }
    }
}

/// 和电脑端的 WebSocket 连接（Network.framework，不受 ATS 限制）。所有回调都在 `queue` 上。
final class Connection {
    enum Event {
        case ready
        case text([String: Any])
        case audio(Data)
        case closed(String?)      // nil = 正常关闭
        case badKey
    }

    let queue = DispatchQueue(label: "shengqiao.net", qos: .userInteractive)
    var onEvent: ((Event) -> Void)?

    private var conn: NWConnection?
    private var generation = 0
    private var inFlight = 0

    /// 正在发送但还没交给系统的二进制帧数（用于网络卡住时丢弃麦克风数据，保持低延迟）
    var pendingFrames: Int { inFlight }

    func connect(_ target: ConnTarget, key: String) {
        queue.async { self.start(target, key: key) }
    }

    func disconnect() {
        queue.async {
            self.generation += 1
            self.conn?.cancel()
            self.conn = nil
            self.inFlight = 0
        }
    }

    private func start(_ target: ConnTarget, key: String) {
        generation += 1
        let gen = generation
        conn?.cancel()
        inFlight = 0

        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 1 << 20
        ws.setAdditionalHeaders([(name: "X-SQ-Key", value: key),
                                 (name: "User-Agent", value: "ShengQiao/2.0 (iPhone; iOS)")])
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 6
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        let params = NWParameters(tls: nil, tcp: tcp)
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        params.serviceClass = .interactiveVoice        // Wi-Fi 语音优先级
        params.includePeerToPeer = false

        let c = NWConnection(to: target.endpoint, using: params)
        conn = c
        c.stateUpdateHandler = { [weak self] state in
            guard let self = self, gen == self.generation else { return }
            switch state {
            case .ready:
                self.onEvent?(.ready)
                self.receive(c, gen)
            case .waiting(let error):
                // 网络不通（例如不在同一个 Wi-Fi）：交给上层重试
                self.finish(gen, reason: Connection.describe(error))
            case .failed(let error):
                self.finish(gen, reason: Connection.describe(error))
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func finish(_ gen: Int, reason: String?, badKey: Bool = false) {
        guard gen == generation else { return }
        generation += 1
        conn?.cancel()
        conn = nil
        inFlight = 0
        onEvent?(badKey ? .badKey : .closed(reason))
    }

    private func receive(_ c: NWConnection, _ gen: Int) {
        c.receiveMessage { [weak self] data, context, _, error in
            guard let self = self, gen == self.generation else { return }
            if let error = error {
                self.finish(gen, reason: Connection.describe(error))
                return
            }
            guard let context = context else {
                self.finish(gen, reason: "电脑断开了连接")
                return
            }
            if let meta = context.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata {
                switch meta.opcode {
                case .binary:
                    if let d = data { self.onEvent?(.audio(d)) }
                case .text:
                    if let d = data, let obj = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                        self.onEvent?(.text(obj))
                    }
                case .close:
                    var bad = false
                    if case .privateCode(let code) = meta.closeCode, code == 4001 { bad = true }
                    self.finish(gen, reason: bad ? nil : "电脑关闭了连接", badKey: bad)
                    return
                default:
                    break
                }
            }
            self.receive(c, gen)
        }
    }

    // MARK: - send（都在 queue 上调用或转到 queue 上）

    func sendJSON(_ obj: [String: Any]) {
        queue.async {
            // 非法对象会让 JSONSerialization 抛 Objective-C 异常（try? 拦不住），先检查
            guard let c = self.conn, JSONSerialization.isValidJSONObject(obj),
                  let data = try? JSONSerialization.data(withJSONObject: obj) else { return }
            let meta = NWProtocolWebSocket.Metadata(opcode: .text)
            let ctx = NWConnection.ContentContext(identifier: "text", metadata: [meta])
            c.send(content: data, contentContext: ctx, isComplete: true, completion: .idempotent)
        }
    }

    /// 只能在 `queue` 上调用（麦克风发送定时器就在这个队列上）
    func sendBinaryOnQueue(_ data: Data) {
        guard let c = conn else { return }
        if inFlight > 40 { return }            // 积压超过 0.4 秒：丢掉，保证低延迟
        inFlight += 1
        let gen = generation
        let meta = NWProtocolWebSocket.Metadata(opcode: .binary)
        let ctx = NWConnection.ContentContext(identifier: "mic", metadata: [meta])
        c.send(content: data, contentContext: ctx, isComplete: true, completion: .contentProcessed { [weak self] _ in
            guard let self = self, gen == self.generation else { return }
            self.inFlight -= 1
        })
    }

    var isConnected: Bool { conn != nil }

    static func describe(_ error: NWError) -> String {
        switch error {
        case .posix(let code):
            switch code {
            case .ECONNREFUSED: return "电脑拒绝连接（声桥没在运行？）"
            case .ETIMEDOUT: return "连接超时"
            case .ENETUNREACH, .EHOSTUNREACH: return "找不到电脑（是不是不在同一个 Wi-Fi？）"
            case .ECONNRESET: return "连接被重置"
            default: return "网络错误：\(code)"
            }
        case .dns:
            return "找不到这台电脑"
        default:
            return error.localizedDescription
        }
    }
}
