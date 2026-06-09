import SwiftUI

struct SettingsView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var profileVM = ProfileViewModel()
    @State private var showLogoutAlert = false

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(v) (\(b))"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    profileCard
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                Section("个人设置") {
                    HStack {
                        Label("昵称", systemImage: "pencil.line")
                        Spacer()
                        TextField("设置昵称", text: Binding(
                            get: { profileVM.nickname },
                            set: { profileVM.setNickname($0) }
                        ))
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(.secondary)
                    }
                }

                Section("数据说明") {
                    Label {
                        Text("所有数据均来自安徽财经大学校内系统，通过学校加密通道获取。本应用不会收集、上传或共享任何个人数据，所有信息仅存储在您的设备本地。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } icon: {
                        Image(systemName: "lock.shield.fill")
                            .foregroundStyle(.green)
                    }
                }

                Section("法律信息") {
                    Link(destination: URL(string: "https://linota.cn/loveace/privacy")!) {
                        Label("隐私政策", systemImage: "hand.raised.fill")
                    }
                    Link(destination: URL(string: "https://linota.cn/loveace/terms")!) {
                        Label("使用条款", systemImage: "doc.text.fill")
                    }
                    Link(destination: URL(string: "mailto:support@linota.cn")!) {
                        Label("联系我们", systemImage: "envelope.fill")
                    }
                }

                Section("关于") {
                    LabeledContent {
                        Text(appVersion)
                    } label: {
                        Label("版本", systemImage: "info.circle.fill")
                    }
                    LabeledContent {
                        Text("SwiftUI")
                    } label: {
                        Label("框架", systemImage: "swift")
                    }
                    LabeledContent {
                        Text("iOS 17+")
                    } label: {
                        Label("兼容", systemImage: "iphone")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showLogoutAlert = true
                    } label: {
                        HStack {
                            Spacer()
                            Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right.fill")
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle("我的")
            .alert("确认退出", isPresented: $showLogoutAlert) {
                Button("取消", role: .cancel) {}
                Button("退出", role: .destructive) { authVM.logout() }
            } message: { Text("退出后需要重新登录") }
            .onAppear { profileVM.setActiveUserId(authVM.userId) }
        }
    }

    @ViewBuilder
    private var profileCard: some View {
        VStack(spacing: 14) {
            Image(systemName: "person.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.white.opacity(0.9))
            Text(profileVM.nickname.isEmpty ? authVM.userId : profileVM.nickname)
                .font(.title2).fontWeight(.bold).foregroundStyle(.white)
            Text("学号: \(authVM.userId)")
                .font(.subheadline).foregroundStyle(.white.opacity(0.7))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .background(
            LinearGradient(colors: [.blue, .cyan, .teal], startPoint: .topLeading, endPoint: .bottomTrailing)
        )
        .clipShape(.rect(cornerRadius: 24))
        .shadow(color: .blue.opacity(0.3), radius: 16, y: 8)
        .padding(.horizontal, -4)
    }
}
