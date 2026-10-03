import Combine
import Foundation
import Network

/// 用 Bonjour 找局域网里开着声桥的电脑。
final class Discovery: ObservableObject {
    struct PC: Identifiable, Hashable {
        let id: String          // 电脑端的固定 id（没有就用服务名）
        let name: String        // 电脑名
        let serviceName: String
        let type: String
        let domain: String

        var target: ConnTarget { .service(name: serviceName, type: type, domain: domain) }
    }

    @Published private(set) var pcs: [PC] = []
    @Published private(set) var failed = false

    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let params = NWParameters()
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_shengqiao._tcp", domain: nil), using: params)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            var list: [PC] = []
            for r in results {
                guard case let .service(name, type, domain, _) = r.endpoint else { continue }
                var id = name
                var pcName = name
                if case let .bonjour(txt) = r.metadata {
                    let dict = txt.dictionary
                    if let v = dict["id"], !v.isEmpty { id = v }
                    if let v = dict["name"], !v.isEmpty { pcName = v }
                }
                list.append(PC(id: id, name: pcName, serviceName: name, type: type, domain: domain))
            }
            list.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            self?.pcs = list
        }
        b.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed:
                self?.failed = true
                self?.restartLater()
            case .ready:
                self?.failed = false
            default:
                break
            }
        }
        b.start(queue: .main)
        browser = b
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }

    private func restartLater() {
        stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.start() }
    }
}
