import SwiftUI

struct IdentifiableWrapper<T>: Identifiable {
    let id = UUID()
    let value: T
}

struct LoadingView: View {
    var message: String = "加载中..."

    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ErrorView: View {
    let message: String
    var retryAction: (() -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label("出错了", systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            if let retry = retryAction {
                Button("重试", action: retry)
                    .buttonStyle(.borderedProminent)
            }
        }
    }
}

struct EmptyStateView: View {
    let title: String
    let systemImage: String
    var description: String?

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            if let desc = description { Text(desc) }
        }
    }
}
