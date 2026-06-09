import SwiftUI

@main
struct loveaceiosApp: App {
    @State private var authVM = AuthViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(authVM)
        }
    }
}
