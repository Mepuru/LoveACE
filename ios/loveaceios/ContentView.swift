import SwiftUI

struct ContentView: View {
    @Environment(AuthViewModel.self) private var authVM

    var body: some View {
        Group {
            switch authVM.state {
            case .initial:
                LoadingView(message: "正在恢复会话...")
                    .onAppear { authVM.restoreSession() }
            case .loading:
                LoadingView(message: "登录中...")
            case .authenticated:
                MainTabView()
            case .unauthenticated, .error:
                LoginView()
            }
        }
        .animation(.default, value: authVM.state == .authenticated)
    }
}
