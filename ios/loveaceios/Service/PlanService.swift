import Foundation
import SwiftSoup
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "PlanService")

actor PlanService {
    private let connection: AUFEConnection
    private lazy var jwcService = JWCService(connection: connection)
    private var planCache: [String: PlanCompletionInfo] = [:]
    private var cacheTimestamps: [String: Date] = [:]
    private(set) var cachedOptions: [PlanOption] = []
    static let baseURL = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    private static let cacheValidInterval: TimeInterval = 300

    init(connection: AUFEConnection) {
        self.connection = connection
    }

    func clearCache() { planCache.removeAll(); cacheTimestamps.removeAll() }

    private func isCacheValid(_ planId: String?) -> Bool {
        guard let ts = cacheTimestamps[planId ?? "__default__"] else { return false }
        return Date().timeIntervalSince(ts) < Self.cacheValidInterval
    }

    func getPlanCompletion(planId: String? = nil, forceRefresh: Bool = false) async -> UniResponse<PlanCompletionInfo> {
        let key = planId ?? "__default__"
        if !forceRefresh, isCacheValid(planId), let cached = planCache[key] {
            return .success(cached, message: "培养方案获取成功（缓存）")
        }
        do {
            let client = await connection.client!
            let url: String
            if let pid = planId, !pid.isEmpty {
                url = "\(Self.baseURL)/student/integratedQuery/planCompletion/getPyfaIndex/\(pid)"
            } else {
                url = "\(Self.baseURL)/student/integratedQuery/planCompletion/index"
            }
            let (data, _) = try await client.get(url)
            let html = String(data: data, encoding: .utf8) ?? ""

            if planId == nil {
                if let selection = parsePlanSelectionHtml(html), !selection.options.isEmpty {
                    cachedOptions = selection.options
                    return .failure("MULTI_PLAN", retryable: false)
                }
            }

            var planInfo = parseHtml(html)
            if planInfo.passedCourses == 0 && planInfo.totalCourses > 0 {
                planInfo = await enrichWithTermScores(planInfo)
            }
            planCache[key] = planInfo
            cacheTimestamps[key] = Date()
            return .success(planInfo)
        } catch {
            logger.error("getPlanCompletion failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func parsePlanSelectionHtml(_ html: String) -> PlanSelectionResponse? {
        guard let doc = try? SwiftSoup.parse(html),
              let buttons = try? doc.select("button.btn-success.btn-round"),
              !buttons.isEmpty() else { return nil }
        let hint = try? doc.select(".alert-warning").first()?.text().trimmingCharacters(in: .whitespaces)
        let options: [PlanOption] = buttons.compactMap { button in
            guard let onclick = try? button.attr("onclick"),
                  let text = try? button.text().trimmingCharacters(in: .whitespaces),
                  let match = onclick.range(of: "getPyfaIndex\\('(\\d+)'\\)", options: .regularExpression) else { return nil }
            let fullMatch = String(onclick[match])
            let idRange = fullMatch.range(of: "\\d+", options: .regularExpression)!
            let planIdStr = String(fullMatch[idRange])
            let type: String
            if text.contains("辅修") { type = "辅修" }
            else if text.contains("微专业") { type = "微专业" }
            else { type = "主修" }
            return PlanOption(planId: planIdStr, planName: text, planType: type, isCurrent: button.hasClass("btn-success"))
        }
        guard !options.isEmpty else { return nil }
        return PlanSelectionResponse(options: options, hint: hint)
    }

    private func parseHtml(_ html: String) -> PlanCompletionInfo {
        guard let doc = try? SwiftSoup.parse(html) else { return PlanCompletionInfo() }
        var planName = "", major = "", grade = ""
        if let h4s = try? doc.select("h4.widget-title") {
            for h4 in h4s {
                guard let text = try? h4.text().trimmingCharacters(in: .whitespaces), text.contains("培养方案") else { continue }
                planName = text
                if let regex = try? NSRegularExpression(pattern: "(\\d{4})级(.+?)本科培养方案"),
                   let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) {
                    if let r1 = Range(match.range(at: 1), in: text) { grade = String(text[r1]) }
                    if let r2 = Range(match.range(at: 2), in: text) { major = String(text[r2]) }
                }
                break
            }
        }
        let ztreeNodes = extractZTreeNodes(html)
        let categories = buildCategoryTree(ztreeNodes)
        return calculateStatistics(PlanCompletionInfo(planName: planName, major: major, grade: grade, categories: categories))
    }

    private func extractZTreeNodes(_ html: String) -> [[String: Any]] {
        let patterns = [
            "\\$\\.fn\\.zTree\\.init\\s*\\(\\s*\\$\\(\\s*[\"']#treeDemo[\"']\\s*\\)\\s*,\\s*\\w+\\s*,\\s*(\\[[\\s\\S]*?\\])\\s*\\)",
            "\\.zTree\\.init\\s*\\([^,]+,\\s*[^,]+,\\s*(\\[[\\s\\S]*?\\])\\s*\\)"
        ]
        guard let doc = try? SwiftSoup.parse(html), let scripts = try? doc.select("script") else { return [] }
        for script in scripts {
            guard let content = try? script.data(),
                  content.contains("zTree.init"), content.contains("flagId") else { continue }
            for pattern in patterns {
                guard let regex = try? NSRegularExpression(pattern: pattern),
                      let match = regex.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
                      let range = Range(match.range(at: 1), in: content) else { continue }
                var jsonStr = String(content[range])
                    .replacingOccurrences(of: "//.*?\n", with: "", options: .regularExpression)
                    .replacingOccurrences(of: "/\\*[\\s\\S]*?\\*/", with: "", options: .regularExpression)
                    .replacingOccurrences(of: ",\\s*([}\\]])", with: "$1", options: .regularExpression)
                if let data = jsonStr.data(using: .utf8),
                   let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]], !arr.isEmpty {
                    return arr
                }
            }
        }
        return []
    }

    private func buildCategoryTree(_ nodes: [[String: Any]]) -> [PlanCategory] {
        var nodesById: [String: [String: Any]] = [:]
        for node in nodes { if let id = node["id"] as? String { nodesById[id] = node } }
        return nodes
            .filter { ($0["pId"] as? String ?? "") == "-1" && ($0["flagType"] as? String ?? "") != "kch" }
            .map { buildCategoryWithChildren($0, nodesById: nodesById) }
    }

    private func buildCategoryWithChildren(_ node: [String: Any], nodesById: [String: [String: Any]]) -> PlanCategory {
        var category = parseCategoryFromNode(node)
        let categoryId = node["id"] as? String ?? ""
        var subcategories: [PlanCategory] = []
        var courses: [PlanCourse] = []
        for child in nodesById.values {
            guard (child["pId"] as? String ?? "") == categoryId else { continue }
            let flagType = child["flagType"] as? String ?? ""
            let childId = child["id"] as? String ?? ""
            if flagType == "kch" {
                courses.append(parseCourseFromNode(child))
            } else if flagType == "001" || flagType == "002" {
                subcategories.append(buildCategoryWithChildren(child, nodesById: nodesById))
            } else {
                let hasChildren = nodesById.values.contains { ($0["pId"] as? String ?? "") == childId }
                if hasChildren { subcategories.append(buildCategoryWithChildren(child, nodesById: nodesById)) }
                else { courses.append(parseCourseFromNode(child)) }
            }
        }
        return PlanCategory(categoryId: category.categoryId, categoryName: category.categoryName,
                            minCredits: category.minCredits, completedCredits: category.completedCredits,
                            totalCourses: category.totalCourses, passedCourses: category.passedCourses,
                            failedCourses: category.failedCourses, missingRequiredCourses: category.missingRequiredCourses,
                            subcategories: subcategories, courses: courses)
    }

    private func parseCategoryFromNode(_ node: [String: Any]) -> PlanCategory {
        let rawName = (node["name"] as? String ?? "")
            .replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ").trimmingCharacters(in: .whitespaces)
        let flagId = node["flagId"] as? String ?? ""
        let pattern = "([^(]+)\\(最低修读学分:([0-9.]+),通过学分:([0-9.]+),已修课程门数:(\\d+),已及格课程门数:(\\d+),未及格课程门数:(\\d+),必修课缺修门数:(\\d+)\\)"
        if let regex = try? NSRegularExpression(pattern: pattern),
           let match = regex.firstMatch(in: rawName, range: NSRange(rawName.startIndex..., in: rawName)) {
            func s(_ i: Int) -> String { Range(match.range(at: i), in: rawName).map { String(rawName[$0]) } ?? "" }
            return PlanCategory(categoryId: flagId, categoryName: s(1).trimmingCharacters(in: .whitespaces),
                                minCredits: Double(s(2)) ?? 0, completedCredits: Double(s(3)) ?? 0,
                                totalCourses: Int(s(4)) ?? 0, passedCourses: Int(s(5)) ?? 0,
                                failedCourses: Int(s(6)) ?? 0, missingRequiredCourses: Int(s(7)) ?? 0)
        }
        return PlanCategory(categoryId: flagId, categoryName: rawName)
    }

    private func parseCourseFromNode(_ node: [String: Any]) -> PlanCourse {
        let name = node["name"] as? String ?? ""
        let isPassed = name.contains("fa-smile-o fa-1x green")
        let statusDesc: String
        if name.contains("fa-smile-o fa-1x green") { statusDesc = "已通过" }
        else if name.contains("fa-frown-o fa-1x red") { statusDesc = "未通过" }
        else { statusDesc = "未修读" }
        let clean = name.replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ").trimmingCharacters(in: .whitespaces)
        var courseCode = "", courseName = "", credits: Double?, score: String?, examDate: String?, courseType = ""
        if let codeMatch = clean.range(of: "\\[([^\\]]+)\\]", options: .regularExpression) {
            let bracketContent = String(clean[codeMatch]).dropFirst().dropLast()
            courseCode = String(bracketContent)
            var remaining = String(clean[codeMatch.upperBound...]).trimmingCharacters(in: .whitespaces)
            if let creditMatch = remaining.range(of: "\\[([0-9.]+)学分\\]", options: .regularExpression) {
                let creditStr = String(remaining[creditMatch]).replacingOccurrences(of: "[", with: "").replacingOccurrences(of: "学分]", with: "")
                credits = Double(creditStr)
                remaining = remaining.replacingCharacters(in: creditMatch, with: "").trimmingCharacters(in: .whitespaces)
            }
            if let parenMatch = remaining.range(of: "\\([^)]+\\)", options: .regularExpression) {
                let parenContent = String(remaining[parenMatch]).dropFirst().dropLast()
                courseName = String(remaining[remaining.startIndex..<parenMatch.lowerBound]).trimmingCharacters(in: .whitespaces)
                if let numMatch = String(parenContent).range(of: "[0-9.]+", options: .regularExpression) { score = String(String(parenContent)[numMatch]) }
                if let dateMatch = String(parenContent).range(of: "\\d{8}", options: .regularExpression) { examDate = String(String(parenContent)[dateMatch]) }
                if String(parenContent).contains(",") { courseType = String(parenContent).split(separator: ",").first.map(String.init) ?? "" }
            } else {
                courseName = remaining
            }
        }
        return PlanCourse(courseCode: courseCode, courseName: courseName, credits: credits, score: score,
                          examDate: examDate, courseType: courseType, isPassed: isPassed, statusDescription: statusDesc)
    }

    private func calculateStatistics(_ info: PlanCompletionInfo) -> PlanCompletionInfo {
        var total = 0, passed = 0, failed = 0, unread = 0
        func countCourses(_ cats: [PlanCategory]) {
            for cat in cats {
                for c in cat.courses {
                    total += 1
                    if c.isPassed { passed += 1 }
                    else if c.statusDescription == "未通过" { failed += 1 }
                    else { unread += 1 }
                }
                countCourses(cat.subcategories)
            }
        }
        countCourses(info.categories)

        var leafTotal = 0, leafPassed = 0, leafFailed = 0, leafMissing = 0
        func leafCount(_ cats: [PlanCategory]) {
            for cat in cats {
                if cat.subcategories.isEmpty {
                    leafTotal += cat.totalCourses; leafPassed += cat.passedCourses
                    leafFailed += cat.failedCourses; leafMissing += cat.missingRequiredCourses
                } else { leafCount(cat.subcategories) }
            }
        }
        leafCount(info.categories)

        let ft = leafTotal > 0 ? leafTotal : total
        let fp = leafPassed > 0 ? leafPassed : passed
        let ff = leafTotal > 0 ? leafFailed : failed
        let fu = leafTotal > 0 ? max(leafTotal - leafPassed - leafFailed, 0) : unread
        let estCredits = estimateGraduationCredits(info.categories)
        return PlanCompletionInfo(planName: info.planName, major: info.major, grade: info.grade,
                                  categories: info.categories, totalCategories: countCategories(info.categories),
                                  totalCourses: ft, passedCourses: fp, failedCourses: ff, unreadCourses: fu,
                                  missingRequiredCourses: leafMissing, estimatedGraduationCredits: estCredits)
    }

    private func countCategories(_ cats: [PlanCategory]) -> Int {
        cats.count + cats.reduce(0) { $0 + countCategories($1.subcategories) }
    }

    private func estimateGraduationCredits(_ cats: [PlanCategory]) -> Double {
        var total = 0.0
        func findLeaf(_ cat: PlanCategory) {
            if cat.subcategories.isEmpty { total += cat.minCredits }
            else { cat.subcategories.forEach { findLeaf($0) } }
        }
        cats.forEach { findLeaf($0) }
        return total
    }

    private func enrichWithTermScores(_ planInfo: PlanCompletionInfo) async -> PlanCompletionInfo {
        let termResp = await jwcService.getAllTerms()
        guard termResp.success, let terms = termResp.data, !terms.isEmpty else { return planInfo }
        var allScores: [ScoreRecord] = []
        for term in terms {
            let scoreResp = await jwcService.getTermScore(termCode: term.termCode)
            if scoreResp.success, let data = scoreResp.data { allScores.append(contentsOf: data.records) }
        }
        guard !allScores.isEmpty else { return planInfo }
        var scoreMap: [String: ScoreRecord] = [:]
        for s in allScores {
            if let existing = scoreMap[s.courseCode] {
                if compareScores(s, existing) > 0 { scoreMap[s.courseCode] = s }
            } else { scoreMap[s.courseCode] = s }
        }
        let updatedCats = updateCategoriesWithScores(planInfo.categories, scoreMap: scoreMap)
        return calculateStatistics(PlanCompletionInfo(planName: planInfo.planName, major: planInfo.major,
                                                      grade: planInfo.grade, categories: updatedCats))
    }

    private func updateCategoriesWithScores(_ cats: [PlanCategory], scoreMap: [String: ScoreRecord]) -> [PlanCategory] {
        cats.map { cat in
            let updatedCourses = cat.courses.map { course -> PlanCourse in
                guard let record = scoreMap[course.courseCode] else { return course }
                let effectiveScore = getEffectiveScore(record)
                let passed = isPassingGrade(effectiveScore)
                return PlanCourse(courseCode: course.courseCode, courseName: course.courseName,
                                  credits: course.credits ?? Double(record.credits),
                                  score: effectiveScore, examDate: course.examDate,
                                  courseType: course.courseType, isPassed: passed,
                                  statusDescription: passed ? "已通过" : "未通过")
            }
            let updatedSubs = updateCategoriesWithScores(cat.subcategories, scoreMap: scoreMap)
            return PlanCategory(categoryId: cat.categoryId, categoryName: cat.categoryName,
                                minCredits: cat.minCredits, completedCredits: cat.completedCredits,
                                totalCourses: cat.totalCourses, passedCourses: cat.passedCourses,
                                failedCourses: cat.failedCourses, missingRequiredCourses: cat.missingRequiredCourses,
                                subcategories: updatedSubs, courses: updatedCourses)
        }
    }

    private func getEffectiveScore(_ record: ScoreRecord) -> String {
        if let r = record.retakeScore, !r.isEmpty { return r }
        if let m = record.makeupScore, !m.isEmpty { return m }
        return record.score
    }

    private func isPassingGrade(_ score: String) -> Bool {
        if let num = Double(score) { return num >= 60 }
        let passing = ["优秀", "良好", "中等", "及格", "合格", "通过", "A", "B", "C", "D"]
        return passing.contains { score.localizedCaseInsensitiveContains($0) }
    }

    private func compareScores(_ a: ScoreRecord, _ b: ScoreRecord) -> Int {
        let sa = Double(getEffectiveScore(a)), sb = Double(getEffectiveScore(b))
        if let sa, let sb { return sa < sb ? -1 : (sa > sb ? 1 : 0) }
        let pa = isPassingGrade(getEffectiveScore(a)), pb = isPassingGrade(getEffectiveScore(b))
        if pa && !pb { return 1 }; if !pa && pb { return -1 }; return 0
    }
}
