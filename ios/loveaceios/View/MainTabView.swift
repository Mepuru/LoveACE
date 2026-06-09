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
    }
}
