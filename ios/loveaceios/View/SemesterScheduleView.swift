import SwiftUI

struct SemesterScheduleView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var vm = ScheduleViewModel()
    @State private var semesterVM = SemesterViewModel()
    @State private var selectedWeek = 1
    @State private var selectedCourseDetail: CourseDetailInfo?
    @State private var grid: [String: [GridCell]] = [:]
    @State private var maxSession = 10

    private let weekdayShort = ["一", "二", "三", "四", "五", "六", "日"]
    private let cellGap: CGFloat = 1.5
    private let cellHeight: CGFloat = 58
    private let timeColWidth: CGFloat = 28

    private var todayDayIndex: Int {
        let wd = Calendar.current.component(.weekday, from: Date())
        return wd == 1 ? 7 : wd - 1
    }

    private var currentWeekCourses: [ScheduleCourse] {
        filterCoursesByWeek(vm.courses, week: selectedWeek)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                weekPicker
                if vm.isLoading {
                    LoadingView(message: "加载课表...")
                } else if vm.courses.isEmpty {
                    EmptyStateView(title: "暂无课程", systemImage: "calendar.badge.exclamationmark")
                } else {
                    weekStatsBar
                    Divider().opacity(0.3)
                    weekScheduleGrid
                }
            }
            .navigationTitle("学期课表")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $selectedCourseDetail) { CourseDetailSheet(detail: $0) }
            .onChange(of: vm.courses.count) { rebuildGrid() }
            .onChange(of: selectedWeek) { rebuildGrid() }
            .onAppear {
                if let jwc = authVM.jwcService, let sch = authVM.studentScheduleService {
                    vm.initialize(jwcService: jwc, scheduleService: sch)
                    vm.setActiveUserId(authVM.userId)
                    vm.loadTerms()
                }
                semesterVM.loadSemesterInfo()
            }
            .onChange(of: semesterVM.currentWeek) {
                if semesterVM.currentWeek > 0 { selectedWeek = semesterVM.currentWeek }
            }
        }
    }

    private func rebuildGrid() {
        let filtered = currentWeekCourses
        grid = buildGrid(filtered)
        maxSession = getMaxUsedSession(grid)
    }

    // MARK: - Week Picker

    @ViewBuilder
    private var weekPicker: some View {
        let total = semesterVM.totalWeeks
        let current = semesterVM.currentWeek

        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(1...max(total, 1), id: \.self) { week in
                        Button {
                            withAnimation(.snappy) { selectedWeek = week }
                        } label: {
                            VStack(spacing: 2) {
                                Text("\(week)")
                                    .font(.system(size: 14, weight: selectedWeek == week ? .bold : .medium, design: .rounded))
                                Text("周")
                                    .font(.system(size: 9))
                            }
                            .frame(width: 36, height: 44)
                        }
                        .foregroundStyle(selectedWeek == week ? .white : (week == current ? .blue : .secondary))
                        .background {
                            if selectedWeek == week {
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(.blue.gradient)
                            } else if week == current {
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(.blue, lineWidth: 1.5)
                            }
                        }
                        .id(week)
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
            }
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    withAnimation { proxy.scrollTo(selectedWeek, anchor: .center) }
                }
            }
            .onChange(of: selectedWeek) {
                withAnimation { proxy.scrollTo(selectedWeek, anchor: .center) }
            }
        }
    }

    // MARK: - Stats

    private var weekStatsBar: some View {
        let filtered = currentWeekCourses
        let courseCount = Set(filtered.map(\.courseCode)).count
        let sessionCount = filtered.reduce(0) { $0 + $1.timeAndPlaceList.count }
        return HStack {
            Text("第 \(selectedWeek) 周 · \(courseCount) 门课 · \(sessionCount) 节")
                .font(.caption).foregroundStyle(.secondary)
            Spacer()
            if semesterVM.currentWeek == selectedWeek {
                Text("本周")
                    .font(.caption2).fontWeight(.semibold)
                    .foregroundStyle(.blue)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(.blue.opacity(0.1), in: .capsule)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 4)
    }

    // MARK: - Grid

    @ViewBuilder
    private var weekScheduleGrid: some View {
        GeometryReader { geo in
            let dayWidth = (geo.size.width - timeColWidth) / 7

            ScrollView(.vertical, showsIndicators: false) {
                HStack(alignment: .top, spacing: 0) {
                    VStack(spacing: cellGap) {
                        Color.clear.frame(width: timeColWidth, height: 32)
                        ForEach(1...maxSession, id: \.self) { s in
                            Text("\(s)")
                                .font(.system(size: 9, weight: .bold, design: .rounded))
                                .foregroundStyle(.tertiary)
                                .frame(width: timeColWidth, height: cellHeight)
                        }
                    }

                    ForEach(1...7, id: \.self) { day in
                        let isToday = day == todayDayIndex && semesterVM.currentWeek == selectedWeek
                        VStack(spacing: cellGap) {
                            VStack(spacing: 1) {
                                Text("周\(weekdayShort[day - 1])")
                                    .font(.system(size: 11, weight: isToday ? .bold : .medium))
                                    .foregroundStyle(isToday ? .blue : .primary)
                                if isToday {
                                    Circle().fill(.blue).frame(width: 4, height: 4)
                                }
                            }
                            .frame(width: dayWidth, height: 32)

                            ForEach(buildDaySlots(day: day), id: \.session) { slot in
                                slotView(slot, dayWidth: dayWidth, isToday: isToday)
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func slotView(_ slot: DaySlot, dayWidth: CGFloat, isToday: Bool) -> some View {
        switch slot.kind {
        case .empty:
            RoundedRectangle(cornerRadius: 4)
                .fill(isToday ? Color.blue.opacity(0.03) : Color.clear)
                .frame(width: dayWidth, height: cellHeight)
        case .single(let cell):
            CourseBlockView(cell: cell, width: dayWidth, height: CGFloat(cell.rowSpan) * (cellHeight + cellGap) - cellGap)
                .onTapGesture { openDetail(cell) }
        case .multi(let cells):
            MultiCourseBlock(cells: cells, width: dayWidth,
                             cellHeight: CGFloat(cells[0].rowSpan) * (cellHeight + cellGap) - cellGap,
                             onDetail: openDetail)
        case .covered:
            EmptyView()
        }
    }

    private func openDetail(_ cell: GridCell) {
        selectedCourseDetail = CourseDetailInfo(
            course: cell.course, timePlace: cell.timePlace,
            color: courseColorPalette[cell.colorIndex % courseColorPalette.count])
    }

    // MARK: - Day Slots

    private enum SlotKind { case empty, single(GridCell), multi([GridCell]), covered }
    private struct DaySlot { let session: Int; let kind: SlotKind }

    private func buildDaySlots(day: Int) -> [DaySlot] {
        var slots: [DaySlot] = []
        var s = 1
        while s <= maxSession {
            if let cells = grid["\(day)-\(s)"], !cells.isEmpty {
                let span = cells[0].rowSpan
                slots.append(DaySlot(session: s, kind: cells.count == 1 ? .single(cells[0]) : .multi(cells)))
                for skip in 1..<span { slots.append(DaySlot(session: s + skip, kind: .covered)) }
                s += span
            } else if isCoveredByAbove(grid, day: day, session: s) {
                s += 1
            } else {
                slots.append(DaySlot(session: s, kind: .empty))
                s += 1
            }
        }
        return slots
    }
}
