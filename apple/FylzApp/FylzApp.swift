import SwiftUI
import FylzKit

@main
struct FylzApp: App {
    @StateObject private var model = BrowserModel(engine: CoreEngine())

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                // "Open in Fylz" from Files / Mail / Safari: the URL arrives security-scoped.
                .onOpenURL { url in model.open(url) }
        }
    }
}
