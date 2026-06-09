import Foundation
import SwiftSoup
import os

private let logger = Logger(subsystem: "tech.loveace.loveaceios", category: "CompetitionService")

actor CompetitionService {
    private let connection: AUFEConnection
    static let baseURL = "http://211-86-241-245.vpn2.aufe.edu.cn:8118"

    init(connection: AUFEConnection) {
        self.connection = connection
    }

    func getCompetitionInfo() async -> UniResponse<CompetitionFullResponse> {
        do {
            let client = await connection.client!
            let url = "\(Self.baseURL)/xsXmMain.aspx"

            let (indexData, _) = try await client.get(url)
            let indexHtml = String(data: indexData, encoding: .utf8) ?? ""
            let indexDoc = try SwiftSoup.parse(indexHtml)

            let viewState = try indexDoc.select("input[name=__VIEWSTATE]").first()?.attr("value") ?? ""
            let viewStateGen = try indexDoc.select("input[name=__VIEWSTATEGENERATOR]").first()?.attr("value") ?? ""
            let eventValidation = try indexDoc.select("input[name=__EVENTVALIDATION]").first()?.attr("value") ?? ""

            let (postData, _) = try await client.post(url, formData: [
                "__VIEWSTATE": viewState, "__VIEWSTATEGENERATOR": viewStateGen,
                "__EVENTVALIDATION": eventValidation,
                "__EVENTTARGET": "ctl00$ContentPlaceHolder1$ContentPlaceHolder2$DataList1$ctl01$LinkButton1",
                "__EVENTARGUMENT": "", "__LASTFOCUS": "",
                "ctl00$ContentPlaceHolder1$ContentPlaceHolder2$ddlSslb": "%",
                "ctl00$ContentPlaceHolder1$ContentPlaceHolder2$txtSsmc": ""
            ])
            let postHtml = String(data: postData, encoding: .utf8) ?? ""
            let currentDoc = try SwiftSoup.parse(postHtml)

            var allAwards = parseAwardProjects(currentDoc)
            let creditsSummary = parseCreditsSummary(currentDoc)
            let totalPages = parseTotalPages(currentDoc)

            var currentHtml = postHtml
            if totalPages >= 2 { for page in 2...totalPages {
                let pageDoc = try SwiftSoup.parse(currentHtml)
                let nextFormData = try extractNextPageFormData(pageDoc, targetPage: page)
                guard !(nextFormData["__VIEWSTATE"]?.isEmpty ?? true) else { break }
                let (pageData, _) = try await client.post(url, formData: nextFormData)
                currentHtml = String(data: pageData, encoding: .utf8) ?? ""
                let pageDoc2 = try SwiftSoup.parse(currentHtml)
                allAwards.append(contentsOf: parseAwardProjects(pageDoc2))
            } }
            return .success(CompetitionFullResponse(awards: allAwards, creditsSummary: creditsSummary))
        } catch {
            logger.error("getCompetitionInfo failed: \(error.localizedDescription)")
            return .failure(error.localizedDescription, retryable: true)
        }
    }

    private func parseAwardProjects(_ doc: Document) -> [AwardProject] {
        var awards: [AwardProject] = []
        guard let table = try? doc.select("table[id*=gvHj]").first() else { return awards }
        guard let rows = try? table.select("tr") else { return awards }
        for i in 1..<rows.size() {
            guard let cells = try? rows.get(i).select("td"), cells.size() >= 12 else { continue }
            let firstText = (try? cells.get(0).text().trimmingCharacters(in: .whitespaces)) ?? ""
            guard !firstText.contains("当前第"), !firstText.contains("页/共"), Int(firstText) != nil else { continue }
            func cell(_ idx: Int) -> String { (try? cells.get(idx).text().trimmingCharacters(in: .whitespaces)) ?? "" }
            awards.append(AwardProject(
                projectId: cell(0), projectName: cell(1), level: cell(2), grade: cell(3),
                awardDate: cell(4), applicantId: cell(5), applicantName: cell(6),
                order: Int(cell(7)) ?? 0, credits: Double(cell(8)) ?? 0.0,
                bonus: Double(cell(9)) ?? 0.0, status: cell(10), verificationStatus: cell(11)
            ))
        }
        return awards
    }

    private func parseCreditsSummary(_ doc: Document) -> CreditsSummary? {
        func parseCredit(_ spanId: String) -> Double? {
            guard let text = try? doc.select("span[id=\(spanId)]").first()?.text().trimmingCharacters(in: .whitespaces),
                  !text.isEmpty, text != "无" else { return nil }
            return Double(text)
        }
        return CreditsSummary(
            disciplineCompetitionCredits: parseCredit("ContentPlaceHolder1_ContentPlaceHolder2_lblXkjsxf"),
            scientificResearchCredits: parseCredit("ContentPlaceHolder1_ContentPlaceHolder2_lblKyxf"),
            transferableCompetitionCredits: parseCredit("ContentPlaceHolder1_ContentPlaceHolder2_lblKzjslxf"),
            innovationPracticeCredits: parseCredit("ContentPlaceHolder1_ContentPlaceHolder2_lblCxcyxf"),
            abilityCertificationCredits: parseCredit("ContentPlaceHolder1_ContentPlaceHolder2_lblNlzgxf"),
            otherProjectCredits: parseCredit("ContentPlaceHolder1_ContentPlaceHolder2_lblQtxf")
        )
    }

    private func parseTotalPages(_ doc: Document) -> Int {
        guard let span = try? doc.select("span[id*=gvHj_LabelPageCount]").first(),
              let text = try? span.text().trimmingCharacters(in: .whitespaces) else { return 1 }
        return Int(text) ?? 1
    }

    private func extractNextPageFormData(_ doc: Document, targetPage: Int) throws -> [String: String] {
        let viewState = try doc.select("input[name=__VIEWSTATE]").first()?.attr("value") ?? ""
        let viewStateGen = try doc.select("input[name=__VIEWSTATEGENERATOR]").first()?.attr("value") ?? ""
        let eventValidation = try doc.select("input[name=__EVENTVALIDATION]").first()?.attr("value") ?? ""
        var eventTarget = ""
        if let nextLink = try? doc.select("a[id*=LinkButtonNextPage]").first(),
           let href = try? nextLink.attr("href"),
           let match = href.range(of: "__doPostBack\\('([^']+)'", options: .regularExpression) {
            let full = String(href[match])
            eventTarget = full.replacingOccurrences(of: "__doPostBack('", with: "").replacingOccurrences(of: "'", with: "")
        }
        var formData: [String: String] = [
            "__VIEWSTATE": viewState, "__VIEWSTATEGENERATOR": viewStateGen,
            "__EVENTVALIDATION": eventValidation, "__EVENTTARGET": eventTarget,
            "__EVENTARGUMENT": "", "__LASTFOCUS": ""
        ]
        if let pageInput = try? doc.select("input[id*=txtNewPageIndex]").first(),
           let name = try? pageInput.attr("name"), !name.isEmpty {
            formData[name] = String(targetPage)
        }
        return formData
    }
}
