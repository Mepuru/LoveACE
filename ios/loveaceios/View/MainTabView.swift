import SwiftUI

struct MainTabView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem { Label("首页", systemImage: "house.fill") }
                .tag(0)
            AACView()
                .tabItem { Label("爱安财", systemImage: "star.fill") }
                .tag(1)
            MoreView()
                .tabItem { Label("更多", systemImage: "square.grid.2x2.fill") }
                .tag(2)
            SettingsView()
                .tabItem { Label("我的", systemImage: "person.fill") }
                .tag(3)
        }
        .onAppear { Analytics.shared.trackScreen(tabName(selectedTab)) }
        .onChange(of: selectedTab) { _, newValue in
            Analytics.shared.trackScreen(tabName(newValue))
        }
    }

    private func tabName(_ index: Int) -> String {
        switch index {
        case 0: "首页"
        case 1: "爱安财"
        case 2: "更多"
        case 3: "我的"
        default: "unknown"
        }
    }
}
