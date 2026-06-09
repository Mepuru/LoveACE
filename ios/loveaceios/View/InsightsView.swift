import SwiftUI
import Charts

struct InsightsView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var vm = AcademicViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.insightsLoading && vm.allTermGPA.isEmpty {
                    LoadingView(message: "正在分析学业数据...")
                } else if vm.allTermGPA.isEmpty {
                    EmptyStateView(title: "暂无数据", systemImage: "chart.bar.xaxis", description: "登录后加载成绩数据即可查看学业分析")
                } else {
                    ScrollView {
                        VStack(spacing: 20) {
                            overviewCards
                            gpaTrendSection
                            scoreDistributionSection
                            highlightsSection
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("学业分析")
            .navigationBarTitleDisplayMode(.large)
            .onAppear {
                if let jwc = authVM.jwcService {
                    vm.initialize(service: jwc)
                    vm.loadAcademicInfo()
                    vm.loadInsightsData()
                }
            }
        }
    }

    // MARK: - Overview Cards

    @ViewBuilder
    private var overviewCards: some View {
        CompatGlassContainer {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                if let info = vm.academicInfo {
                    GlassStatCard(title: "累计绩点", value: String(format: "%.2f", info.gpa), icon: "star.fill", tint: .green)
                    GlassStatCard(title: "平均分", value: String(format: "%.1f", vm.averageScore), icon: "chart.line.uptrend.xyaxis", tint: .blue)
                    GlassStatCard(title: "已修课程", value: "\(info.completedCourses)", icon: "checkmark.circle.fill", tint: .teal)
                    GlassStatCard(title: "学期数", value: "\(vm.allTermGPA.count)", icon: "calendar.badge.clock", tint: .orange)
                }
            }
        }
    }

    // MARK: - GPA Trend Chart

    @ViewBuilder
    private var gpaTrendSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            GlassSectionHeader(title: "绩点趋势", icon: "chart.xyaxis.line")

            VStack(alignment: .leading, spacing: 8) {
                Chart(vm.allTermGPA) { point in
                    LineMark(
                        x: .value("学期", point.termName),
                        y: .value("绩点", point.gpa)
                    )
                    .foregroundStyle(.blue.gradient)
                    .interpolationMethod(.catmullRom)
                    .lineStyle(StrokeStyle(lineWidth: 2.5))

                    AreaMark(
                        x: .value("学期", point.termName),
                        y: .value("绩点", point.gpa)
                    )
                    .foregroundStyle(.blue.opacity(0.1).gradient)
                    .interpolationMethod(.catmullRom)

                    PointMark(
                        x: .value("学期", point.termName),
                        y: .value("绩点", point.gpa)
                    )
                    .foregroundStyle(.blue)
                    .symbolSize(40)
                    .annotation(position: .top, spacing: 4) {
                        Text(String(format: "%.2f", point.gpa))
                            .font(.system(size: 9, weight: .medium, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                .chartYScale(domain: 0...5.0)
                .chartXAxis {
                    AxisMarks(values: .automatic) { value in
                        AxisValueLabel {
                            if let name = value.as(String.self) {
                                Text(shortenTermName(name))
                                    .font(.system(size: 9))
                            }
                        }
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading, values: [0, 1, 2, 3, 4, 5]) { value in
                        AxisValueLabel {
                            Text("\(value.as(Double.self) ?? 0, specifier: "%.0f")")
                                .font(.caption2)
                        }
                        AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [3]))
                    }
                }
                .frame(height: 200)
                .padding(.vertical, 8)
            }
            .padding(16)
            .glassCard(cornerRadius: 16)
        }
    }

    // MARK: - Score Distribution

    @ViewBuilder
    private var scoreDistributionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            GlassSectionHeader(title: "成绩分布", icon: "chart.bar.fill")

            VStack(spacing: 8) {
                Chart(vm.scoreDistribution) { bucket in
                    BarMark(
                        x: .value("分段", bucket.label),
                        y: .value("门数", bucket.count)
                    )
                    .foregroundStyle(barColor(bucket.color).gradient)
                    .cornerRadius(6)
                    .annotation(position: .top, spacing: 2) {
                        if bucket.count > 0 {
                            Text("\(bucket.count)")
                                .font(.system(size: 10, weight: .semibold, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .chartXAxis {
                    AxisMarks { value in
                        AxisValueLabel { Text(value.as(String.self) ?? "").font(.caption) }
                    }
                }
                .chartYAxis(.hidden)
                .frame(height: 160)
                .padding(.vertical, 8)

                HStack(spacing: 0) {
                    ForEach(vm.scoreDistribution) { bucket in
                        HStack(spacing: 4) {
                            Circle().fill(barColor(bucket.color)).frame(width: 6, height: 6)
                            Text(bucket.label).font(.system(size: 9)).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .padding(16)
            .glassCard(cornerRadius: 16)
        }
    }

    // MARK: - Highlights

    @ViewBuilder
    private var highlightsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            GlassSectionHeader(title: "学业亮点", icon: "sparkles")

            CompatGlassContainer {
                VStack(spacing: 10) {
                    if let best = vm.bestSubject {
                        highlightRow(icon: "trophy.fill", tint: .yellow,
                                   title: "最高分", value: "\(best.score) · \(best.courseNameCn)")
                    }
                    if let worst = vm.worstSubject {
                        highlightRow(icon: "exclamationmark.triangle.fill", tint: .orange,
                                   title: "最低分", value: "\(worst.score) · \(worst.courseNameCn)")
                    }
                    highlightRow(icon: "books.vertical.fill", tint: .blue,
                               title: "总课程数", value: "\(vm.allScores.count) 门")

                    if let latest = vm.allTermGPA.last, let first = vm.allTermGPA.first, vm.allTermGPA.count > 1 {
                        let delta = latest.gpa - first.gpa
                        highlightRow(icon: delta >= 0 ? "arrow.up.right" : "arrow.down.right",
                                   tint: delta >= 0 ? .green : .red,
                                   title: "绩点变化",
                                   value: String(format: "%+.2f (从 %.2f 到 %.2f)", delta, first.gpa, latest.gpa))
                    }
                }
            }
        }
    }

    private func highlightRow(icon: String, tint: Color, title: String, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.subheadline).lineLimit(1)
            }
            Spacer()
        }
        .padding(12)
        .glassCard(cornerRadius: 12)
    }

    private func shortenTermName(_ name: String) -> String {
        name.replacingOccurrences(of: "学年", with: "")
            .replacingOccurrences(of: "学期", with: "")
            .replacingOccurrences(of: "-", with: "-")
    }

    private func barColor(_ name: String) -> Color {
        switch name {
        case "green": return .green
        case "blue": return .blue
        case "orange": return .orange
        case "yellow": return .yellow
        case "red": return .red
        default: return .gray
        }
    }
}
