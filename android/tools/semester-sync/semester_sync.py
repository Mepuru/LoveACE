"""学期时间同步管理工具 - 使用 OpenDAL 上传到阿里云 OSS，Textual TUI 管理界面"""

import json
import os
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from dotenv import load_dotenv
import opendal
from textual import on
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import (
    Button,
    DataTable,
    Footer,
    Header,
    Input,
    Label,
    Static,
)

DATA_FILE = Path(__file__).parent / "semesters.json"
ENV_FILE = Path(__file__).parent / "config.env"
TZ = ZoneInfo("Asia/Shanghai")

# 加载 config.env
load_dotenv(ENV_FILE)

TERM_NAME_MAP = {
    "1": "第一学期（秋季）",
    "2": "第二学期（春季）",
}


# ── 数据操作 ──


def load_data() -> dict:
    if DATA_FILE.exists():
        return json.loads(DATA_FILE.read_text("utf-8"))
    return {"version": 1, "updated_at": "", "semesters": []}


def save_data(data: dict):
    data["updated_at"] = datetime.now(TZ).isoformat()
    DATA_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), "utf-8")


def semester_display_name(sem: dict) -> str:
    """将学期代号转化为可读文本，如 2025-2026-1 -> 2025-2026学年 第一学期（秋季）"""
    code = sem.get("code", "")
    parts = code.rsplit("-", 1)
    if len(parts) == 2:
        year_part, term_num = parts
        term_text = TERM_NAME_MAP.get(term_num, f"第{term_num}学期")
        return f"{year_part}学年 {term_text}"
    return sem.get("name", code)


def compute_status(today: date | None = None) -> dict:
    """计算当前学期状态，返回给首页使用的信息"""
    data = load_data()
    if today is None:
        today = datetime.now(TZ).date()

    semesters = sorted(data.get("semesters", []), key=lambda s: s["start_date"])
    result = {"status": "vacation", "message": "假期中", "detail": ""}

    for i, sem in enumerate(semesters):
        start = date.fromisoformat(sem["start_date"])
        weeks = sem.get("weeks", 18)
        end = start + timedelta(weeks=weeks) - timedelta(days=1)
        display = semester_display_name(sem)

        if today < start:
            # 还没开学 -> 假期中，显示即将到来的学期
            days_until = (start - today).days
            result = {
                "status": "vacation",
                "message": "假期中",
                "detail": f"即将到来：{display}，{sem['start_date']} 开学（还有 {days_until} 天）",
                "next_semester_code": sem["code"],
                "next_semester_name": display,
                "next_start_date": sem["start_date"],
                "days_until_start": days_until,
            }
            return result

        if start <= today <= end:
            week_num = (today - start).days // 7 + 1
            remaining_weeks = weeks - week_num

            if remaining_weeks <= 2:
                msg = f"{display} 第{week_num}周（学期即将结束）"
            else:
                msg = f"{display} 第{week_num}周"

            result = {
                "status": "in_session",
                "message": msg,
                "detail": f"本学期共{weeks}周，剩余{remaining_weeks}周",
                "semester_code": sem["code"],
                "semester_name": display,
                "start_date": sem["start_date"],
                "end_date": end.isoformat(),
                "current_week": week_num,
                "total_weeks": weeks,
                "remaining_weeks": remaining_weeks,
            }

            # 查找下一学期信息
            if i + 1 < len(semesters):
                next_sem = semesters[i + 1]
                result["next_semester_code"] = next_sem["code"]
                result["next_semester_name"] = semester_display_name(next_sem)
                result["next_start_date"] = next_sem["start_date"]

            return result

    # 所有学期都已过去
    return result


# ── OpenDAL 上传 ──


def upload_to_oss() -> str:
    """使用 OpenDAL 将 semesters.json 上传到阿里云 OSS (S3 兼容)"""
    endpoint = os.environ.get("OSS_ENDPOINT", "")
    bucket = os.environ.get("OSS_BUCKET", "")
    access_key_id = os.environ.get("OSS_ACCESS_KEY_ID", "")
    access_key_secret = os.environ.get("OSS_ACCESS_KEY_SECRET", "")
    upload_path = os.environ.get("OSS_UPLOAD_PATH", "loveace/semesters.json")

    if not all([endpoint, bucket, access_key_id, access_key_secret]):
        return "❌ 缺少 OSS 环境变量配置，请检查 OSS_ENDPOINT / OSS_BUCKET / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET"

    try:
        op = opendal.Operator(
            "s3",
            endpoint=endpoint,
            bucket=bucket,
            access_key_id=access_key_id,
            secret_access_key=access_key_secret,
            region="auto",
            enable_virtual_host_style="true",
        )
        content = DATA_FILE.read_bytes()
        op.write(upload_path, content)
        return f"✅ 上传成功 -> {bucket}/{upload_path}"
    except Exception as e:
        return f"❌ 上传失败: {e}"


# ── Textual TUI ──


class AddSemesterScreen(ModalScreen[dict | None]):
    """添加学期的弹窗"""

    BINDINGS = [Binding("escape", "cancel", "取消")]

    def compose(self) -> ComposeResult:
        with Vertical(id="add-dialog"):
            yield Label("添加新学期", id="dialog-title")
            yield Label("学期代号（如 2026-2027-1）")
            yield Input(placeholder="2026-2027-1", id="input-code")
            yield Label("开学日期（如 2026-09-01）")
            yield Input(placeholder="2026-09-01", id="input-date")
            yield Label("学期周数（默认 18）")
            yield Input(placeholder="18", id="input-weeks", value="18")
            with Horizontal(id="dialog-buttons"):
                yield Button("确认添加", variant="success", id="btn-confirm")
                yield Button("取消", variant="default", id="btn-cancel")

    @on(Button.Pressed, "#btn-confirm")
    def on_confirm(self):
        code = self.query_one("#input-code", Input).value.strip()
        start_date = self.query_one("#input-date", Input).value.strip()
        weeks_str = self.query_one("#input-weeks", Input).value.strip()

        if not code or not start_date:
            self.notify("代号和日期不能为空", severity="error")
            return

        try:
            date.fromisoformat(start_date)
        except ValueError:
            self.notify("日期格式错误，请使用 YYYY-MM-DD", severity="error")
            return

        weeks = int(weeks_str) if weeks_str.isdigit() else 18
        display = semester_display_name({"code": code})

        self.dismiss(
            {
                "code": code,
                "name": display,
                "start_date": start_date,
                "weeks": weeks,
            }
        )

    @on(Button.Pressed, "#btn-cancel")
    def on_cancel(self):
        self.dismiss(None)

    def action_cancel(self):
        self.dismiss(None)


class SemesterSyncApp(App):
    """学期时间管理 TUI"""

    CSS = """
    #add-dialog {
        width: 60;
        height: auto;
        padding: 1 2;
        background: $surface;
        border: thick $primary;
    }
    #dialog-title {
        text-style: bold;
        color: $primary;
        margin-bottom: 1;
    }
    #dialog-buttons {
        margin-top: 1;
        height: 3;
    }
    #dialog-buttons Button {
        margin-right: 1;
    }
    #status-panel {
        height: auto;
        padding: 1 2;
        margin: 1 2;
        background: $boost;
        border: round $primary;
    }
    #action-bar {
        height: 3;
        margin: 0 2;
    }
    #action-bar Button {
        margin-right: 1;
    }
    DataTable {
        margin: 0 2;
        height: 1fr;
    }
    """

    TITLE = "学期时间同步管理"
    BINDINGS = [
        Binding("a", "add", "添加学期"),
        Binding("d", "delete", "删除选中"),
        Binding("u", "upload", "上传到 OSS"),
        Binding("q", "quit", "退出"),
    ]

    def compose(self) -> ComposeResult:
        yield Header()
        with VerticalScroll():
            yield Static("", id="status-panel")
            with Horizontal(id="action-bar"):
                yield Button("添加学期 [A]", variant="success", id="btn-add")
                yield Button("删除选中 [D]", variant="error", id="btn-delete")
                yield Button("上传到 OSS [U]", variant="primary", id="btn-upload")
            yield DataTable(id="semester-table")
        yield Footer()

    def on_mount(self):
        self.refresh_table()
        self.refresh_status()

    def refresh_table(self):
        table = self.query_one("#semester-table", DataTable)
        table.clear(columns=True)
        table.add_columns("学期代号", "学期名称", "开学日期", "周数", "结束日期")
        table.cursor_type = "row"

        data = load_data()
        for sem in sorted(data.get("semesters", []), key=lambda s: s["start_date"]):
            start = date.fromisoformat(sem["start_date"])
            weeks = sem.get("weeks", 18)
            end = start + timedelta(weeks=weeks) - timedelta(days=1)
            display = semester_display_name(sem)
            table.add_row(
                sem["code"],
                display,
                sem["start_date"],
                str(weeks),
                end.isoformat(),
                key=sem["code"],
            )

    def refresh_status(self):
        status = compute_status()
        panel = self.query_one("#status-panel", Static)
        lines = [f"📅 当前状态：{status['message']}"]
        if status.get("detail"):
            lines.append(f"   {status['detail']}")
        if status.get("current_week"):
            lines.append(
                f"   📍 第 {status['current_week']} / {status['total_weeks']} 周"
            )
        panel.update("\n".join(lines))

    @on(Button.Pressed, "#btn-add")
    def on_add_click(self):
        self.action_add()

    @on(Button.Pressed, "#btn-delete")
    def on_delete_click(self):
        self.action_delete()

    @on(Button.Pressed, "#btn-upload")
    def on_upload_click(self):
        self.action_upload()

    def action_add(self):
        def on_result(result: dict | None):
            if result is None:
                return
            data = load_data()
            # 去重
            codes = {s["code"] for s in data["semesters"]}
            if result["code"] in codes:
                self.notify(f"学期 {result['code']} 已存在", severity="warning")
                return
            data["semesters"].append(result)
            save_data(data)
            self.refresh_table()
            self.refresh_status()
            self.notify(f"已添加 {result['code']}", severity="information")

        self.push_screen(AddSemesterScreen(), callback=on_result)

    def action_delete(self):
        table = self.query_one("#semester-table", DataTable)
        if table.row_count == 0:
            self.notify("没有可删除的学期", severity="warning")
            return
        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        code = str(row_key)
        data = load_data()
        data["semesters"] = [s for s in data["semesters"] if s["code"] != code]
        save_data(data)
        self.refresh_table()
        self.refresh_status()
        self.notify(f"已删除 {code}", severity="information")

    def action_upload(self):
        self.notify("正在上传...", severity="information")
        result = upload_to_oss()
        severity = "information" if "✅" in result else "error"
        self.notify(result, severity=severity)


if __name__ == "__main__":
    SemesterSyncApp().run()
