import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../..");
const outFile = resolve(repoRoot, "assets/analytics-stats.svg");

const databaseName = process.env.D1_DATABASE_NAME || "loveace-analytics";

const summary = firstRow(query(`
  SELECT
    COUNT(*) AS total_events,
    COUNT(DISTINCT client_id) AS clients,
    COUNT(DISTINCT student_hash) AS users,
    SUM(CASE WHEN created_at >= datetime('now', '-24 hours') THEN 1 ELSE 0 END) AS events_24h,
    MAX(created_at) AS last_event_at
  FROM analytics_events;
`));

const topEvents = query(`
  SELECT event_name, COUNT(*) AS count
  FROM analytics_events
  GROUP BY event_name
  ORDER BY count DESC, event_name ASC
  LIMIT 5;
`);

const versions = query(`
  SELECT app_version, COUNT(DISTINCT client_id) AS clients, COUNT(*) AS events
  FROM analytics_events
  GROUP BY app_version
  ORDER BY events DESC, app_version DESC
  LIMIT 3;
`);

const generatedAt = new Date().toISOString().replace("T", " ").replace(/\.\d{3}Z$/, " UTC");
const lastEvent = summary.last_event_at ? `${summary.last_event_at} UTC` : "暂无数据";

const svg = renderSvg({ summary, topEvents, versions, generatedAt, lastEvent });
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, `${svg}\n`, "utf8");

function query(sql) {
  const output = execFileSync(
    "npx",
    ["wrangler", "d1", "execute", databaseName, "--remote", "--json", "--command", compactSql(sql)],
    { cwd: resolve(repoRoot, "analytics-worker"), encoding: "utf8", stdio: ["ignore", "pipe", "inherit"] },
  );
  const parsed = JSON.parse(output);
  const result = Array.isArray(parsed) ? parsed[0] : parsed;
  if (!result?.success) {
    throw new Error(`D1 查询失败：${JSON.stringify(result)}`);
  }
  return result.results || [];
}

function firstRow(rows) {
  return rows[0] || {};
}

function compactSql(sql) {
  return sql.replace(/\s+/g, " ").trim();
}

function renderSvg({ summary, topEvents, versions, generatedAt, lastEvent }) {
  const eventRows = topEvents.length ? topEvents : [{ event_name: "暂无事件", count: 0 }];
  const versionRows = versions.length ? versions : [{ app_version: "暂无版本", clients: 0, events: 0 }];
  const maxEventCount = Math.max(1, ...eventRows.map((row) => Number(row.count) || 0));

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="760" height="360" viewBox="0 0 760 360" role="img" aria-label="LoveACE 匿名使用统计">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#f7fbff"/>
      <stop offset="100%" stop-color="#fff7fb"/>
    </linearGradient>
    <linearGradient id="accent" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#7c3aed"/>
      <stop offset="100%" stop-color="#06b6d4"/>
    </linearGradient>
    <filter id="shadow" x="-10%" y="-10%" width="120%" height="120%">
      <feDropShadow dx="0" dy="10" stdDeviation="12" flood-color="#1f2937" flood-opacity="0.12"/>
    </filter>
  </defs>

  <rect width="760" height="360" rx="28" fill="url(#bg)"/>
  <rect x="24" y="24" width="712" height="312" rx="24" fill="#ffffff" filter="url(#shadow)"/>
  <rect x="24" y="24" width="712" height="6" rx="3" fill="url(#accent)"/>

  <text x="52" y="66" fill="#111827" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="24" font-weight="700">LoveACE 匿名使用统计</text>
  <text x="52" y="92" fill="#6b7280" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="13">只统计匿名事件，不展示任何明文学号或业务内容</text>

  ${metric(52, 124, "总事件", formatNumber(summary.total_events), "#7c3aed")}
  ${metric(224, 124, "客户端", formatNumber(summary.clients), "#0891b2")}
  ${metric(396, 124, "匿名用户", formatNumber(summary.users), "#db2777")}
  ${metric(568, 124, "近 24 小时", formatNumber(summary.events_24h), "#16a34a")}

  <text x="52" y="226" fill="#111827" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="16" font-weight="700">事件分布</text>
  ${eventRows.map((row, index) => eventBar(row, index, maxEventCount)).join("\n")}

  <text x="432" y="226" fill="#111827" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="16" font-weight="700">版本概览</text>
  ${versionRows.map((row, index) => versionLine(row, index)).join("\n")}

  <text x="52" y="320" fill="#9ca3af" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="12">最近事件：${escapeXml(lastEvent)}</text>
  <text x="520" y="320" fill="#9ca3af" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="12">更新：${escapeXml(generatedAt)}</text>
</svg>`;
}

function metric(x, y, label, value, color) {
  return `<g>
    <rect x="${x}" y="${y}" width="140" height="72" rx="16" fill="${color}" opacity="0.10"/>
    <text x="${x + 18}" y="${y + 28}" fill="#6b7280" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="13">${escapeXml(label)}</text>
    <text x="${x + 18}" y="${y + 58}" fill="${color}" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="28" font-weight="800">${escapeXml(value)}</text>
  </g>`;
}

function eventBar(row, index, maxCount) {
  const y = 246 + index * 18;
  const count = Number(row.count) || 0;
  const width = Math.round((count / maxCount) * 150);
  return `<g>
    <text x="52" y="${y}" fill="#374151" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="12">${escapeXml(row.event_name)}</text>
    <rect x="190" y="${y - 10}" width="150" height="8" rx="4" fill="#eef2ff"/>
    <rect x="190" y="${y - 10}" width="${width}" height="8" rx="4" fill="url(#accent)"/>
    <text x="352" y="${y}" fill="#6b7280" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="12" text-anchor="end">${formatNumber(count)}</text>
  </g>`;
}

function versionLine(row, index) {
  const y = 246 + index * 24;
  return `<g>
    <text x="432" y="${y}" fill="#374151" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="13" font-weight="600">v${escapeXml(row.app_version)}</text>
    <text x="520" y="${y}" fill="#6b7280" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="12">客户端 ${formatNumber(row.clients)}</text>
    <text x="622" y="${y}" fill="#6b7280" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif" font-size="12">事件 ${formatNumber(row.events)}</text>
  </g>`;
}

function formatNumber(value) {
  return new Intl.NumberFormat("zh-CN").format(Number(value) || 0);
}

function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}
