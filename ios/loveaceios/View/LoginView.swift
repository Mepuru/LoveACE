import SwiftUI

struct LoginView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var userId = ""
    @State private var ecPassword = ""
    @State private var password = ""
    @State private var showPassword = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Spacer(minLength: 80)

                VStack(spacing: 14) {
                    Image(systemName: "graduationcap.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.blue)

                    Text("彩带小工具")
                        .font(.system(size: 28, weight: .bold, design: .rounded))

                    Text("智能学业分析 · 安财专属")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.bottom, 44)

                VStack(spacing: 16) {
                    inputField(icon: "person.fill", placeholder: "学号", text: $userId, isSecure: false)
                        .keyboardType(.numberPad)
                        .textContentType(.username)

                    inputField(icon: "network", placeholder: "校园网关密码", text: $ecPassword, isSecure: true)
                        .textContentType(.password)

                    HStack(spacing: 12) {
                        Image(systemName: "lock.fill")
                            .foregroundStyle(.tertiary)
                            .frame(width: 20)
                        Group {
                            if showPassword {
                                TextField("教务密码", text: $password)
                            } else {
                                SecureField("教务密码", text: $password)
                            }
                        }
                        .textContentType(.password)
                        Button { showPassword.toggle() } label: {
                            Image(systemName: showPassword ? "eye.slash" : "eye")
                                .foregroundStyle(.tertiary)
                                .contentTransition(.symbolEffect(.replace))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(.fill.quaternary, in: .rect(cornerRadius: 12))
                }
                .padding(.horizontal, 32)

                if let error = authVM.errorMessage {
                    HStack(spacing: 6) {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                        Text(error).foregroundStyle(.red)
                    }
                    .font(.subheadline)
                    .padding(.top, 12)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }

                Button {
                    authVM.login(userId: userId, ecPassword: ecPassword, password: password)
                } label: {
                    Group {
                        if authVM.state == .loading {
                            ProgressView().tint(.white)
                        } else {
                            Text("登录")
                                .fontWeight(.semibold)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .foregroundStyle(.white)
                    .background(canLogin ? Color.blue : Color.gray.opacity(0.35), in: .rect(cornerRadius: 14))
                }
                .disabled(!canLogin || authVM.state == .loading)
                .padding(.horizontal, 32)
                .padding(.top, 28)

                Spacer(minLength: 40)
            }
        }
        .onAppear {
            if let creds = authVM.getRememberedCredentials() {
                userId = creds.userId
                ecPassword = creds.ecPassword
                password = creds.password
            }
        }
    }

    private var canLogin: Bool {
        !userId.isEmpty && !ecPassword.isEmpty && !password.isEmpty
    }

    private func inputField(icon: String, placeholder: String, text: Binding<String>, isSecure: Bool) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(.tertiary)
                .frame(width: 20)
            if isSecure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(.fill.quaternary, in: .rect(cornerRadius: 12))
    }
}
