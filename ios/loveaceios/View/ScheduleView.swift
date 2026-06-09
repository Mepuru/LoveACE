import SwiftUI

// MARK: - Grid Data

struct GridCell: Identifiable {
    let id = UUID()
    let course: ScheduleCourse
    let timePlace: ScheduleTimePlace
    let rowSpan: Int
    let colorIndex: Int
    let mergedWeeks: String
}

func buildGrid(_ courses: [ScheduleCourse]) -> [String: [GridCell]] {
    var courseColorMap: [String: Int] = [:]
    var colorIdx = 0
    struct TempEntry { let course: ScheduleCourse; let tp: ScheduleTimePlace }
    var tempGrid: [String: [TempEntry]] = [:]

    for course in courses {
        if courseColorMap[course.courseCode] == nil {
            courseColorMap[course.courseCode] = colorIdx % courseColorPalette.count
            colorIdx += 1
        }
        for tp in course.timeAndPlaceList {
            let key = "\(tp.classDay)-\(tp.classSessions)"
            tempGrid[key, default: []].append(TempEntry(course: course, tp: tp))
        }
    }

    var grid: [String: [GridCell]] = [:]
    for (key, entries) in tempGrid {
        struct GroupKey: Hashable { let courseCode: String; let classroom: String; let span: Int }
        var grouped: [GroupKey: [String]] = [:]
        var groupData: [GroupKey: TempEntry] = [:]
        for entry in entries {
            let gk = GroupKey(courseCode: entry.course.courseCode, classroom: entry.tp.classroomName, span: entry.tp.continuingSession)
            grouped[gk, default: []].append(entry.tp.weekDescription)
            if groupData[gk] == nil { groupData[gk] = entry }
        }
        grid[key] = grouped.map { gk, weeks in
            let entry = groupData[gk]!
            return GridCell(course: entry.course, timePlace: entry.tp, rowSpan: gk.span,
                            colorIndex: courseColorMap[entry.course.courseCode] ?? 0, mergedWeeks: mergeWeeks(weeks))
        }
    }
    return grid
}

func mergeWeeks(_ weeks: [String]) -> String {
    let unique = Array(Set(weeks))
    if unique.count == 1 { return unique[0] }
    let joined = unique.joined(separator: ", ")
    return joined.count > 18 ? "\(unique[0]) 等" : joined
}

func isCoveredByAbove(_ grid: [String: [GridCell]], day: Int, session: Int) -> Bool {
    for s in stride(from: session - 1, through: 1, by: -1) {
        guard let cells = grid["\(day)-\(s)"] else { continue }
        if cells.contains(where: { s + $0.rowSpan > session }) { return true }
    }
    return false
}

func getMaxUsedSession(_ grid: [String: [GridCell]]) -> Int {
    var maxEnd = 0
    for (key, cells) in grid {
        guard let session = Int(key.split(separator: "-").last ?? "") else { continue }
        for cell in cells { maxEnd = max(maxEnd, session + cell.rowSpan - 1) }
    }
    return max(min(maxEnd, 12), 8)
}

func filterCoursesByWeek(_ courses: [ScheduleCourse], week: Int) -> [ScheduleCourse] {
    courses.compactMap { course in
        let filtered = course.timeAndPlaceList.filter { tp in
            guard !tp.classWeek.isEmpty, week > 0, week <= tp.classWeek.count else { return true }
            let idx = tp.classWeek.index(tp.classWeek.startIndex, offsetBy: week - 1)
            return tp.classWeek[idx] == "1"
        }
        guard !filtered.isEmpty else { return nil }
        return ScheduleCourse(
            courseId: course.courseId,
            programPlanNumber: course.programPlanNumber,
            courseName: course.courseName,
            unit: course.unit,
            programPlanName: course.programPlanName,
            attendClassTeacher: course.attendClassTeacher,
            studyModeName: course.studyModeName,
            coursePropertiesName: course.coursePropertiesName,
            examTypeName: course.examTypeName,
            courseCategoryName: course.courseCategoryName,
            restrictedCondition: course.restrictedCondition,
            timeAndPlaceList: filtered,
            selectCourseStatusName: course.selectCourseStatusName
        )
    }
}

// MARK: - Schedule View

struct ScheduleView: View {
    @Environment(AuthViewModel.self) private var authVM
    @State private var vm = ScheduleViewModel()
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

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                termSelector
                if vm.isLoading {
                    LoadingView(message: "加载课表...")
                } else if vm.courses.isEmpty {
                    EmptyStateView(title: "暂无课程", systemImage: "calendar.badge.exclamationmark")
                } else {
                    statsBar
                    Divider().opacity(0.3)
                    scheduleGrid
                }
            }
            .navigationTitle("课表")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $selectedCourseDetail) { CourseDetailSheet(detail: $0) }
            .onChange(of: vm.courses.count) {
                grid = buildGrid(vm.courses)
                maxSession = getMaxUsedSession(grid)
            }
            .onAppear {
                if let jwc = authVM.jwcService, let sch = authVM.studentScheduleService {
                    vm.initialize(jwcService: jwc, scheduleService: sch)
                    vm.setActiveUserId(authVM.userId)
                    vm.loadTerms()
                }
            }
        }
    }

    @ViewBuilder
    private var termSelector: some View {
        if !vm.terms.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(vm.terms) { t in
                        Button { withAnimation(.snappy) { vm.selectTerm(t) } } label: {
                            Text(t.termName).font(.caption).fontWeight(.medium)
                                .padding(.horizontal, 12).padding(.vertical, 7)
                        }
                        .glassCapsule(tint: vm.selectedTerm?.termCode == t.termCode ? .blue.opacity(0.3) : nil, interactive: vm.selectedTerm?.termCode == t.termCode)
                        .foregroundStyle(vm.selectedTerm?.termCode == t.termCode ? .blue : .secondary)
                    }
                }
                .padding(.horizontal).padding(.vertical, 6)
            }
        }
    }

    private var statsBar: some View {
        HStack {
            Text("共 \(vm.courses.count) 门 · \(String(format: "%.1f", vm.totalUnits)) 学分")
                .font(.caption).foregroundStyle(.secondary)
            Spacer()
            Text("点击查看详情")
                .font(.caption2).foregroundStyle(.quaternary)
        }
        .padding(.horizontal, 12).padding(.vertical, 4)
    }

    // MARK: - Grid

    @ViewBuilder
    private var scheduleGrid: some View {
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
                        let isToday = day == todayDayIndex
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

// MARK: - Single Course Block

struct CourseBlockView: View {
    let cell: GridCell
    let width: CGFloat
    let height: CGFloat

    private var color: Color { courseColorPalette[cell.colorIndex % courseColorPalette.count] }

    private var infoText: Text {
        var parts: [Text] = []
        parts.append(Text(cell.course.courseName).font(.system(size: 10, weight: .semibold)))
        parts.append(Text(" @\(cell.timePlace.classroomName)").font(.system(size: 8)).foregroundColor(color.opacity(0.65)))
        if !cell.course.attendClassTeacher.isEmpty {
            parts.append(Text(" \(cell.course.attendClassTeacher)").font(.system(size: 8)).foregroundColor(color.opacity(0.55)))
        }
        if !cell.mergedWeeks.isEmpty {
            parts.append(Text(" \(cell.mergedWeeks)").font(.system(size: 7)).foregroundColor(color.opacity(0.4)))
        }
        return parts.reduce(Text(""), +)
    }

    var body: some View {
        infoText
            .foregroundStyle(color)
            .padding(.horizontal, 4)
            .padding(.vertical, 4)
            .frame(width: width, height: height, alignment: .topLeading)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(color.opacity(0.12))
                    .overlay(alignment: .leading) {
                        UnevenRoundedRectangle(topLeadingRadius: 8, bottomLeadingRadius: 8)
                            .fill(color)
                            .frame(width: 3)
                    }
            )
    }
}

// MARK: - Multi Course Block

struct MultiCourseBlock: View {
    let cells: [GridCell]
    let width: CGFloat
    let cellHeight: CGFloat
    let onDetail: (GridCell) -> Void
    @State private var currentIndex = 0

    private var idx: Int { currentIndex % cells.count }
    private var cell: GridCell { cells[idx] }
    private var color: Color { courseColorPalette[cell.colorIndex % courseColorPalette.count] }

    private var infoText: Text {
        var parts: [Text] = []
        parts.append(Text(cell.course.courseName).font(.system(size: 10, weight: .semibold)))
        parts.append(Text(" @\(cell.timePlace.classroomName)").font(.system(size: 8)).foregroundColor(color.opacity(0.65)))
        if !cell.mergedWeeks.isEmpty {
            parts.append(Text(" \(cell.mergedWeeks)").font(.system(size: 7)).foregroundColor(color.opacity(0.4)))
        }
        return parts.reduce(Text(""), +)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            infoText
            Spacer(minLength: 0)
            HStack(spacing: 3) {
                ForEach(0..<cells.count, id: \.self) { i in
                    let c = courseColorPalette[cells[i].colorIndex % courseColorPalette.count]
                    Capsule()
                        .fill(i == idx ? c : c.opacity(0.25))
                        .frame(width: i == idx ? 10 : 5, height: 3)
                }
            }
            .padding(.top, 2)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 4)
        .padding(.vertical, 4)
        .frame(width: width, height: cellHeight, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(color.opacity(0.12))
                .overlay(alignment: .leading) {
                    UnevenRoundedRectangle(topLeadingRadius: 8, bottomLeadingRadius: 8)
                        .fill(color)
                        .frame(width: 3)
                }
        )
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.snappy) { currentIndex += 1 } }
        .onLongPressGesture { onDetail(cell) }
    }
}

// MARK: - Course Detail

struct CourseDetailInfo: Identifiable {
    let id = UUID()
    let course: ScheduleCourse
    let timePlace: ScheduleTimePlace
    let color: Color
}

struct CourseDetailSheet: View {
    let detail: CourseDetailInfo
    @Environment(\.dismiss) private var dismiss
    private let weekdayNames = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    VStack(spacing: 10) {
                        Text(detail.course.courseName)
                            .font(.title2).fontWeight(.bold).multilineTextAlignment(.center)
                        HStack(spacing: 8) {
                            GlassBadge(text: String(format: "%.1f 学分", detail.course.unit), tint: detail.color)
                            GlassBadge(text: detail.course.coursePropertiesName, tint: .blue)
                            if !detail.course.examTypeName.isEmpty {
                                GlassBadge(text: detail.course.examTypeName, tint: .orange)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(16)
                    .background(detail.color.opacity(0.06), in: .rect(cornerRadius: 18))

                    VStack(spacing: 0) {
                        detailRow("person.fill", "授课教师", detail.course.attendClassTeacher)
                        Divider().padding(.horizontal, 14)
                        detailRow("number", "课程号", detail.course.courseCode)
                        Divider().padding(.horizontal, 14)
                        detailRow("tag.fill", "课序号", detail.course.courseSequence)
                        if let cat = detail.course.courseCategoryName, !cat.isEmpty {
                            Divider().padding(.horizontal, 14)
                            detailRow("folder.fill", "课程类别", cat)
                        }
                        if !detail.course.studyModeName.isEmpty {
                            Divider().padding(.horizontal, 14)
                            detailRow("graduationcap.fill", "修读方式", detail.course.studyModeName)
                        }
                    }
                    .glassCard(cornerRadius: 14)

                    if !detail.course.timeAndPlaceList.isEmpty {
                        GlassSectionHeader(title: "时间地点", icon: "clock.fill")
                        VStack(spacing: 8) {
                            ForEach(Array(detail.course.timeAndPlaceList.enumerated()), id: \.offset) { _, tp in
                                let day = (1...7).contains(tp.classDay) ? weekdayNames[tp.classDay] : ""
                                HStack(spacing: 12) {
                                    VStack(spacing: 2) {
                                        Text(day).font(.subheadline).fontWeight(.semibold)
                                        Text("第\(tp.classSessions)-\(tp.endSession)节")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    .frame(width: 60)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Label(tp.locationDescription, systemImage: "mappin.circle.fill").font(.subheadline)
                                        if !tp.weekDescription.isEmpty {
                                            Label(tp.weekDescription, systemImage: "calendar")
                                                .font(.caption).foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                }
                                .padding(12)
                                .glassCard(cornerRadius: 12)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("课程详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func detailRow(_ icon: String, _ title: String, _ value: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).font(.subheadline).foregroundStyle(detail.color).frame(width: 24)
            Text(title).font(.subheadline).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.subheadline).fontWeight(.medium).multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }
}
