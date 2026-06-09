import SwiftUI

struct ScoresView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var vm = AcademicViewModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if !vm.terms.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(vm.terms) { term in
                                Button { withAnimation(.snappy) { vm.selectTerm(term) } } label: {
                                    Text(term.termName)
                                        .font(.caption)
                                        .fontWeight(.medium)
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 8)
                                }
                .glassCapsule(tint: vm.selectedTerm?.termCode == term.termCode ? .blue.opacity(0.3) : nil, interactive: vm.selectedTerm?.termCode == term.termCode)
                                .foregroundStyle(vm.selectedTerm?.termCode == term.termCode ? .blue : .secondary)
                            }
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 8)
                    }
                }

                if vm.scoresLoading {
                    LoadingView(message: "加载成绩...")
                } else if let scores = vm.scores {
                    if scores.records.isEmpty {
                        EmptyStateView(title: "暂无成绩", systemImage: "doc.text.magnifyingglass", description: "该学期暂无成绩数据")
                    } else {
                        scoreCarousel(scores.records)
                    }
                }
            }
            .navigationTitle("成绩查询")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if let jwc = authVM.jwcService {
                    vm.initialize(service: jwc)
                    vm.loadTerms()
                }
            }
        }
    }

    @ViewBuilder
    private func scoreCarousel(_ records: [ScoreRecord]) -> some View {
        ScrollView(.vertical) {
            LazyVStack(spacing: 14) {
                ForEach(records) { record in
                    ScoreCardView(record: record)
                        .compatScrollTransition()
                }
            }
            .compatScrollTargetLayout()
            .padding(.horizontal)
            .padding(.vertical, 20)
        }
        .compatScrollTargetBehavior()
    }
}

struct ScoreCardView: View {
    let record: ScoreRecord

    private var color: Color { scoreColor(for: record.score) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(record.courseNameCn)
                        .font(.title3)
                        .fontWeight(.semibold)

                    HStack(spacing: 10) {
                        Label(record.credits + " 学分", systemImage: "book.closed.fill")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if let type = record.courseType {
                            GlassBadge(text: type, tint: .blue)
                        }
                    }

                    if !record.courseNameEn.isEmpty {
                        Text(record.courseNameEn)
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                    }
                }

                Spacer()

                VStack(spacing: 4) {
                    Text(record.score)
                        .font(AppFont.heroNumber)
                        .foregroundStyle(color.gradient)
                    Text("分")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if record.retakeScore != nil || record.makeupScore != nil {
                Divider().padding(.vertical, 8)
                HStack(spacing: 16) {
                    if let retake = record.retakeScore, !retake.isEmpty {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.counterclockwise")
                            Text("补考: \(retake)")
                        }
                        .font(.caption)
                        .foregroundStyle(.orange)
                    }
                    if let makeup = record.makeupScore, !makeup.isEmpty {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.2.squarepath")
                            Text("重修: \(makeup)")
                        }
                        .font(.caption)
                        .foregroundStyle(.blue)
                    }
                    Spacer()
                }
            }
        }
        .padding(20)
        .background {
            RoundedRectangle(cornerRadius: 20)
                .fill(color.opacity(0.06))
                .overlay(alignment: .topTrailing) {
                    Circle()
                        .fill(color.opacity(0.08))
                        .frame(width: 100, height: 100)
                        .offset(x: 30, y: -30)
                }
                .clipShape(.rect(cornerRadius: 20))
        }
        .glassCard(cornerRadius: 20)
    }
}
