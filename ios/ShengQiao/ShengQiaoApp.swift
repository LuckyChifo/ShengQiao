import SwiftUI

@main
struct ShengQiaoApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .tint(Theme.amber)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: model.becameActive()
            case .background: model.enteredBackground()
            default: break
            }
        }
    }
}
