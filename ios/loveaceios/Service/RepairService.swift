import Foundation
import SwiftSoup
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "RepairService")

actor RepairService {
    private let connection: AUFEConnection
    private var sessionInitialized = false
    static let baseURL = "http://wxqd-aufe-edu-cn.vpn2.aufe.edu.cn:8118"

    init(connection: AUFEConnection) {
        self.connection = connection
    }

    private func ensureSession() async {
        guard !sessionInitialized else { return }
        do {
            let client = await connection.client!
            let (data, _) = try await client.get("\(Self.baseURL)/index.php/index/user")
            let html = String(data: data, encoding: .utf8) ?? ""
            if html.contains("weui_tabbar") || html.contains("零星维修") || html.contains("我的") {
                sessionInitialized = true
            } else { sessionInitialized = true }
        } catch { logger.error("ensureSession failed: \(error.localizedDescription)") }
    }

    func getAllOrders() async -> UniResponse<RepairOrderSummary> {
        do {
            await ensureSession()
            async let s0 = fetchOrderList(status: 0)
            async let s1 = fetchOrderList(status: 1)
            async let s2 = fetchOrderList(status: 2)
            async let s3 = fetchOrderList(status: 3)
            let pending = try await s0 + s1
            let completed = try await s2 + s3
            return .success(RepairOrderSummary(pending: pending, completed: completed))
        } catch {
            logger.error("getAllOrders failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func fetchOrderList(status: Int) async throws -> [RepairOrder] {
        let client = await connection.client!
        let url = "\(Self.baseURL)/index.php/index/myorder/staue/\(status)"
        let (data, _) = try await client.get(url)
        let html = String(data: data, encoding: .utf8) ?? ""
        return parseOrderList(html, status: status)
    }

    private func parseOrderList(_ html: String, status: Int) -> [RepairOrder] {
        guard let doc = try? SwiftSoup.parse(html),
              let items = try? doc.select("a.weui_media_box.weui_media_appmsg") else { return [] }
        var orders: [RepairOrder] = []
        for item in items {
            guard let href = try? item.attr("href") else { continue }
            var taskId: String?
            if let m = href.range(of: "taskid/(\\d+)", options: .regularExpression) {
                let full = String(href[m])
                taskId = full.replacingOccurrences(of: "taskid/", with: "")
            } else if let m = href.range(of: "taskid%2F(\\d+)", options: .regularExpression) {
                let full = String(href[m])
                taskId = full.replacingOccurrences(of: "taskid%2F", with: "")
            }
            guard let tid = taskId else { continue }
            let title = (try? item.select("h4.weui_media_title").first()?.text().trimmingCharacters(in: .whitespaces)) ?? ""
            let descs = (try? item.select("p.weui_media_desc")) ?? Elements()
            var workHours = "", orderNumber = "", reporter = "", location = "", createTime = ""
            for desc in descs {
                let text = (try? desc.text().trimmingCharacters(in: .whitespaces)) ?? ""
                if text.contains("工时") {
                    if let m = text.range(of: "工时[：:]\\s*(\\S+)", options: .regularExpression) { workHours = String(text[m]).replacingOccurrences(of: "工时[：:]\\s*", with: "", options: .regularExpression) }
                    if let m = text.range(of: "\\d{12,}", options: .regularExpression) { orderNumber = String(text[m]) }
                }
                if text.contains("校区") || text.contains("公寓") || text.contains("楼") {
                    let parts = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).split(separator: " ").map(String.init)
                    if !parts.isEmpty { reporter = parts[0] }
                    if parts.count > 1 { location = parts.dropFirst().joined(separator: " ") }
                }
                if (try? desc.select(".fa-pencil-square-o").first()) != nil {
                    if let m = text.range(of: "\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}", options: .regularExpression) { createTime = String(text[m]) }
                    else if let m = text.range(of: "\\d{4}-\\d{1,2}-\\d{1,2}", options: .regularExpression) { createTime = String(text[m]) }
                }
            }
            let statusText = ["待接单", "待完工", "待评价", "已完成"][min(status, 3)]
            orders.append(RepairOrder(taskId: tid, title: title, orderNumber: orderNumber,
                                      workHours: workHours, reporter: reporter, location: location,
                                      createTime: createTime, status: status, statusText: statusText))
        }
        return orders
    }

    func getOrderDetail(taskId: String) async -> UniResponse<RepairOrderDetail> {
        do {
            await ensureSession()
            let client = await connection.client!
            let (data, _) = try await client.get("\(Self.baseURL)/index.php/index/showrepair/taskid/\(taskId)")
            let html = String(data: data, encoding: .utf8) ?? ""
            return .success(parseOrderDetail(html, taskId: taskId))
        } catch {
            logger.error("getOrderDetail failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func parseOrderDetail(_ html: String, taskId: String) -> RepairOrderDetail {
        guard let doc = try? SwiftSoup.parse(html) else { return RepairOrderDetail(taskId: taskId) }
        var faultArea = "", repairProject = "", phone = "", faultAddress = ""
        if let inputs = try? doc.select("input.weui_input") {
            for input in inputs {
                let id = (try? input.attr("id")) ?? ""
                let value = (try? input.attr("value")) ?? ""
                switch id {
                case "Area_Name": faultArea = value
                case "Project_Name": repairProject = value
                case "telphone": phone = value
                case "Address": faultAddress = value
                default: break
                }
            }
        }
        let description = (try? doc.select("#WordDesc").first()?.text().trimmingCharacters(in: .whitespaces)) ?? ""
        var progressItems: [RepairProgress] = []
        if let progressCon = try? doc.select(".progressCon").first() {
            let leftItems = (try? progressCon.select(".conLeft li")) ?? Elements()
            let rightItems = (try? progressCon.select(".conRight li")) ?? Elements()
            for i in 0..<leftItems.size() {
                let stage = (try? leftItems.get(i).text().trimmingCharacters(in: .whitespaces)) ?? ""
                let time = i < rightItems.size() ? ((try? rightItems.get(i).select("span").first()?.text().trimmingCharacters(in: .whitespaces)) ?? "") : ""
                let desc = i < rightItems.size() ? ((try? rightItems.get(i).select("p").first()?.text().trimmingCharacters(in: .whitespaces)) ?? "") : ""
                progressItems.append(RepairProgress(stage: stage, time: time, description: desc))
            }
        }
        var settlements: [RepairSettlement] = []
        if let table = try? doc.select("table.wxcl").first(), let rows = try? table.select("tbody tr") {
            for row in rows {
                guard let cells = try? row.select("td"), cells.size() >= 3 else { continue }
                func cell(_ i: Int) -> String { (try? cells.get(i).text().trimmingCharacters(in: .whitespaces)) ?? "" }
                settlements.append(RepairSettlement(serviceName: cell(0), material: cell(1), workPoints: cell(2)))
            }
        }
        return RepairOrderDetail(taskId: taskId, faultArea: faultArea, repairProject: repairProject,
                                  phone: phone, faultAddress: faultAddress, description: description,
                                  progress: progressItems, settlements: settlements)
    }

    func getRepairFormData() async -> UniResponse<RepairFormData> {
        do {
            await ensureSession()
            let client = await connection.client!
            let (areaData, _) = try await client.get("\(Self.baseURL)/index.php/index/arealist")
            let areaHtml = String(data: areaData, encoding: .utf8) ?? ""
            let areas = parseAreaList(areaHtml)
            let (projectData, _) = try await client.get("\(Self.baseURL)/index.php/index/projectlist")
            let projectHtml = String(data: projectData, encoding: .utf8) ?? ""
            let projects = parseProjectList(projectHtml)
            return .success(RepairFormData(areas: areas, projects: projects))
        } catch {
            logger.error("getRepairFormData failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func parseAreaList(_ html: String) -> [RepairAreaGroup] {
        guard let doc = try? SwiftSoup.parse(html) else { return [] }
        let tabs = (try? doc.select("#segmentedControls a.mui-control-item")) ?? Elements()
        let contentDivs = (try? doc.select("#segmentedControlContents > div.mui-control-content")) ?? Elements()
        var groups: [RepairAreaGroup] = []
        if tabs.size() == contentDivs.size() && tabs.size() > 0 {
            for i in 0..<tabs.size() {
                let groupName = (try? tabs.get(i).text().trimmingCharacters(in: .whitespaces)) ?? ""
                let items: [RepairAreaItem] = ((try? contentDivs.get(i).select("li.mui-table-view-cell")) ?? Elements()).compactMap { li in
                    let id = (try? li.attr("data-val")) ?? ""
                    let name = (try? li.text().trimmingCharacters(in: .whitespaces)) ?? ""
                    guard !id.isEmpty, !name.isEmpty else { return nil }
                    return RepairAreaItem(itemId: id, name: name)
                }
                if !items.isEmpty { groups.append(RepairAreaGroup(groupName: groupName, items: items)) }
            }
        }
        return groups
    }

    private func parseProjectList(_ html: String) -> [RepairProjectGroup] {
        guard let doc = try? SwiftSoup.parse(html) else { return [] }
        let tabs = (try? doc.select("#segmentedControls a.mui-control-item")) ?? Elements()
        let contentDivs = (try? doc.select("#segmentedControlContents > div.mui-control-content")) ?? Elements()
        var groups: [RepairProjectGroup] = []
        if tabs.size() == contentDivs.size() && tabs.size() > 0 {
            for i in 0..<tabs.size() {
                let groupName = (try? tabs.get(i).text().trimmingCharacters(in: .whitespaces)) ?? ""
                let items: [RepairProjectItem] = ((try? contentDivs.get(i).select("li.mui-table-view-cell")) ?? Elements()).compactMap { li in
                    let id = (try? li.attr("data-val")) ?? ""
                    let name = (try? li.text().trimmingCharacters(in: .whitespaces)) ?? ""
                    guard !id.isEmpty, !name.isEmpty else { return nil }
                    return RepairProjectItem(itemId: id, name: name)
                }
                if !items.isEmpty { groups.append(RepairProjectGroup(groupName: groupName, items: items)) }
            }
        }
        return groups
    }

    func submitRepair(request: RepairSubmitRequest) async -> UniResponse<Bool> {
        do {
            await ensureSession()
            let client = await connection.client!
            let (data, _) = try await client.post("\(Self.baseURL)/index.php/index/repair?sf_request_type=ajax", formData: [
                "areaid": request.areaId, "areaname": request.areaName,
                "projectid": request.projectId, "projectname": request.projectName,
                "telphone": request.phone, "address": request.address,
                "worddesc": request.description, "pic": request.picUrls ?? "",
                "voiceid": "", "taskType": "1"
            ], headers: [
                "X-Requested-With": "XMLHttpRequest",
                "Referer": "\(Self.baseURL)/index.php/index/repair"
            ])
            let body = String(data: data, encoding: .utf8) ?? ""
            if body.contains("\"isok\"") && body.contains("true") {
                return .success(true, message: "报修提交成功")
            }
            if let msgMatch = body.range(of: "\"msg\"\\s*:\\s*\"([^\"]+)\"", options: .regularExpression) {
                let msg = String(body[msgMatch]).replacingOccurrences(of: "\"msg\"", with: "").replacingOccurrences(of: "\"", with: "").replacingOccurrences(of: ":", with: "").trimmingCharacters(in: .whitespaces)
                return .failure(msg)
            }
            return .failure("提交失败")
        } catch {
            logger.error("submitRepair failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }
}
