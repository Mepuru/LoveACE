import Foundation
import SwiftSoup
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "CourseScheduleService")

actor CourseScheduleService {
    private let connection: AUFEConnection
    static let baseURL = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"

    struct ScheduleTermItem: Identifiable, Hashable {
        var id: String { termCode }
        let termCode: String
        let termName: String
        let isSelected: Bool
    }

    init(connection: AUFEConnection) {
        self.connection = connection
    }

    func getScheduleTerms() async -> UniResponse<[ScheduleTermItem]> {
        do {
            let client = await connection.client!
            let url = "\(Self.baseURL)/student/integratedQuery/course/courseSchdule/index"
            let (data, _) = try await client.get(url)
            let html = String(data: data, encoding: .utf8) ?? ""
            let doc = try SwiftSoup.parse(html)
            guard let select = try doc.select("select#zxjxjhh").first()
                    ?? doc.select("select[name=zxjxjhh]").first() else {
                throw ServiceError.parseError("未找到学期选择框")
            }
            let options = try select.select("option")
            var terms: [ScheduleTermItem] = []
            for option in options.array() {
                let code = try option.attr("value")
                guard !code.isEmpty else { continue }
                let name = try option.text().trimmingCharacters(in: .whitespaces)
                let selected = option.hasAttr("selected")
                terms.append(ScheduleTermItem(termCode: code, termName: name, isSelected: selected))
            }
            guard !terms.isEmpty else { throw ServiceError.parseError("未能解析出任何学期信息") }
            return .success(terms)
        } catch {
            logger.error("getScheduleTerms failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    func queryCourseSchedule(courseCode: String, termCode: String, pageNum: Int = 1, pageSize: Int = 50) async -> UniResponse<[CourseScheduleRecord]> {
        do {
            let records = try await fetchPage(termCode: termCode, courseCode: courseCode, pageNum: pageNum, pageSize: pageSize)
            return .success(records)
        } catch {
            logger.error("queryCourseSchedule failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func fetchPage(termCode: String, courseCode: String, pageNum: Int, pageSize: Int) async throws -> [CourseScheduleRecord] {
        let client = await connection.client!
        let url = "\(Self.baseURL)/student/integratedQuery/course/courseSchdule/courseInfo?sf_request_type=ajax"
        let (data, _) = try await client.post(url, formData: [
            "zxjxjhh": termCode, "kch": courseCode, "kcm": "",
            "kkxsh": "", "kkxqh": "", "jxlh": "", "jash": "",
            "skxq": "", "skjc": "", "kclb": "", "skjs": "",
            "xqname": "", "jcname": "", "jxlname": "", "jasname": "",
            "pageNum": String(pageNum), "pageSize": String(pageSize)
        ])
        let body = String(data: data, encoding: .utf8) ?? ""
        guard let jsonData = body.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              let listObj = json["list"] as? [String: Any],
              let records = listObj["records"] as? [[String: Any]] else { return [] }
        return records.map { parseCourseScheduleRecord($0) }
    }

    private func parseCourseScheduleRecord(_ obj: [String: Any]) -> CourseScheduleRecord {
        CourseScheduleRecord(
            kch: obj["kch"] as? String, kxh: obj["kxh"] as? String,
            kcm: obj["kcm"] as? String, xf: obj["xf"] as? Int,
            xs: obj["xs"] as? Int, kkxsjc: obj["kkxsjc"] as? String,
            kslxmc: obj["kslxmc"] as? String, skjs: obj["skjs"] as? String,
            bkskrl: obj["bkskrl"] as? Int, bkskyl: obj["bkskyl"] as? Int,
            xkmssm: obj["xkmssm"] as? String, kkxqm: obj["kkxqm"] as? String,
            skzc: obj["skzc"] as? String, skxq: obj["skxq"] as? Int,
            skjc: obj["skjc"] as? Int, cxjc: obj["cxjc"] as? Int,
            zcsm: obj["zcsm"] as? String, kclbmc: obj["kclbmc"] as? String,
            xqm: obj["xqm"] as? String, jxlm: obj["jxlm"] as? String,
            jasm: obj["jasm"] as? String, mxbj: obj["mxbj"] as? String,
            xss: obj["xss"] as? Int
        )
    }
}
