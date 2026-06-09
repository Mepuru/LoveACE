import Foundation
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "StudentScheduleService")

actor StudentScheduleService {
    private let connection: AUFEConnection
    private var cachedDynamicPath: String?
    private var cacheTermCode: String?
    private var cacheResult: UniResponse<StudentScheduleResponse>?
    private var cacheTimestamp: Date = .distantPast
    static let baseURL = "http://jwcxk2-aufe-edu-cn.vpn2.aufe.edu.cn:8118"
    private static let cacheTTL: TimeInterval = 30

    init(connection: AUFEConnection) {
        self.connection = connection
    }

    func getStudentSchedule(termCode: String) async -> UniResponse<StudentScheduleResponse> {
        if termCode == cacheTermCode, let cached = cacheResult, cached.success,
           Date().timeIntervalSince(cacheTimestamp) < Self.cacheTTL {
            return cached
        }

        do {
            let client = await connection.client!
            if cachedDynamicPath == nil { try await fetchDynamicPath() }
            guard let dynPath = cachedDynamicPath else { throw ServiceError.parseError("未能获取动态路径参数") }

            let indexUrl = "\(Self.baseURL)/student/courseSelect/calendarSemesterCurriculum/index"
            let scheduleUrl = "\(Self.baseURL)/student/courseSelect/thisSemesterCurriculum/\(dynPath)/ajaxStudentSchedule/past/callback"

            let (data, _) = try await client.post(scheduleUrl, formData: ["planCode": termCode], headers: [
                "Referer": indexUrl,
                "X-Requested-With": "XMLHttpRequest",
                "Accept": "application/json, text/javascript, */*; q=0.01"
            ])
            let body = String(data: data, encoding: .utf8) ?? ""
            guard let jsonData = body.data(using: .utf8),
                  let json = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
                throw ServiceError.parseError("课表数据解析失败")
            }

            if let errorMsg = json["errorMessage"] as? String, !errorMsg.isEmpty {
                throw ServiceError.parseError("服务器返回错误: \(errorMsg)")
            }

            let allUnits = json["allUnits"] as? Double ?? 0.0
            let dateList = json["dateList"] as? [[String: Any]] ?? []
            let scheduleInfos = dateList.map { parseDateInfo($0) }
            let resp = StudentScheduleResponse(allUnits: allUnits, errorMessage: "", dateList: scheduleInfos)
            let result: UniResponse<StudentScheduleResponse> = .success(resp)
            cacheTermCode = termCode
            cacheResult = result
            cacheTimestamp = Date()
            return result
        } catch {
            cachedDynamicPath = nil
            logger.error("getStudentSchedule failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func parseDateInfo(_ obj: [String: Any]) -> ScheduleDateInfo {
        let planCode = obj["programPlanCode"] as? String ?? ""
        let planName = obj["programPlanName"] as? String ?? ""
        let totalUnits = obj["totalUnits"] as? Double ?? 0.0
        let courseList = obj["selectCourseList"] as? [[String: Any]] ?? []
        let courses = courseList.map { parseCourse($0) }
        return ScheduleDateInfo(programPlanCode: planCode, programPlanName: planName,
                                totalUnits: totalUnits, selectCourseList: courses)
    }

    private func parseCourse(_ obj: [String: Any]) -> ScheduleCourse {
        let idObj = obj["id"] as? [String: Any] ?? [:]
        let courseId = ScheduleCourseId(
            executiveEducationPlanNumber: idObj["executiveEducationPlanNumber"] as? String ?? "",
            coureNumber: idObj["coureNumber"] as? String ?? "",
            coureSequenceNumber: idObj["coureSequenceNumber"] as? String ?? "",
            studentNumber: idObj["studentNumber"] as? String ?? ""
        )
        let timePlaceArr = obj["timeAndPlaceList"] as? [[String: Any]] ?? []
        let timePlaces = timePlaceArr.map { parseTimePlace($0) }
        return ScheduleCourse(
            courseId: courseId,
            courseName: obj["courseName"] as? String ?? "",
            unit: obj["unit"] as? Double ?? 0.0,
            programPlanName: obj["programPlanName"] as? String ?? "",
            attendClassTeacher: obj["attendClassTeacher"] as? String ?? "",
            studyModeName: obj["studyModeName"] as? String ?? "",
            coursePropertiesName: obj["coursePropertiesName"] as? String ?? "",
            examTypeName: obj["examTypeName"] as? String ?? "",
            courseCategoryName: obj["courseCategoryName"] as? String,
            timeAndPlaceList: timePlaces,
            selectCourseStatusName: obj["selectCourseStatusName"] as? String ?? ""
        )
    }

    private func parseTimePlace(_ obj: [String: Any]) -> ScheduleTimePlace {
        ScheduleTimePlace(
            classWeek: obj["classWeek"] as? String ?? "",
            classDay: obj["classDay"] as? Int ?? 0,
            classSessions: obj["classSessions"] as? Int ?? 0,
            continuingSession: obj["continuingSession"] as? Int ?? 0,
            campusName: obj["campusName"] as? String ?? "",
            teachingBuildingName: obj["teachingBuildingName"] as? String ?? "",
            classroomName: obj["classroomName"] as? String ?? "",
            weekDescription: obj["weekDescription"] as? String ?? "",
            coursePropertiesName: obj["coursePropertiesName"] as? String ?? "",
            coureName: obj["coureName"] as? String ?? ""
        )
    }

    private func fetchDynamicPath() async throws {
        let client = await connection.client!
        let indexUrl = "\(Self.baseURL)/student/courseSelect/calendarSemesterCurriculum/index"
        let (data, _) = try await client.get(indexUrl)
        let html = String(data: data, encoding: .utf8) ?? ""
        let pattern = "/student/courseSelect/thisSemesterCurriculum/([A-Za-z0-9]+)/ajaxStudentSchedule"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
              let range = Range(match.range(at: 1), in: html) else {
            throw ServiceError.parseError("未能从页面中提取动态路径参数")
        }
        cachedDynamicPath = String(html[range])
    }
}
