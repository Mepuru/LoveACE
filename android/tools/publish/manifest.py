import hashlib
from typing import Optional
from pydantic import BaseModel, Field, computed_field


class Announcement(BaseModel):
    """公告模型"""
    title: str = Field(..., description="公告标题")
    content: str = Field(..., description="公告内容")
    confirm_require: bool = Field(default=False, description="是否需要用户确认")

    @computed_field
    @property
    def md5(self) -> str:
        """根据 title + content 计算 MD5"""
        combined = f"{self.title}{self.content}"
        return hashlib.md5(combined.encode("utf-8")).hexdigest()


class ChangelogEntry(BaseModel):
    """版本更新日志条目"""
    version: str = Field(..., description="版本号")
    changes: str = Field(..., description="更新内容")


class PlatformRelease(BaseModel):
    """单平台发布信息
    
    每个平台可以有独立的版本号和强制更新标志，
    因为不同平台的发布进度可能不同。
    type="web" 表示该平台没有 native 安装包，url 指向 Web App。
    """
    version: str = Field(..., description="该平台的版本号")
    force_ota: bool = Field(default=False, description="该平台是否强制更新")
    url: str = Field(..., description="下载地址或 Web App 地址")
    md5: Optional[str] = Field(default=None, description="安装包 MD5（web 类型无需填写）")
    type: str = Field(default="native", description="发布类型: native（安装包）或 web（Web App 跳转）")


class OTA(BaseModel):
    """OTA 更新模型
    
    每个平台独立管理版本号和强制更新标志。
    content 和 changelog 为所有平台共享的更新说明。
    notice 用于在下载页面展示重要提示（如兼容性警告）。
    """
    content: str = Field(default="", description="OTA 弹窗内容（所有平台共享）")
    notice: Optional[str] = Field(default=None, description="下载页面通知横幅（如兼容性提示）")
    changelog: list[ChangelogEntry] = Field(
        default_factory=list,
        max_length=10,
        description="近10个版本的更新日志（所有平台共享）",
    )
    # 各平台下载信息（每个平台独立版本号和强制更新标志）
    android: Optional[PlatformRelease] = Field(default=None, description="Android 平台")
    ios: Optional[PlatformRelease] = Field(default=None, description="iOS 平台")
    windows: Optional[PlatformRelease] = Field(default=None, description="Windows 平台")
    macos: Optional[PlatformRelease] = Field(default=None, description="macOS 平台")
    linux: Optional[PlatformRelease] = Field(default=None, description="Linux 平台")


class LoveACEManifest(BaseModel):
    """LoveACE 应用 Manifest 模型
    
    用于管理应用公告和 OTA 更新信息。
    
    示例 JSON:
    {
        "announcement": {
            "title": "系统维护通知",
            "content": "系统将于今晚进行维护...",
            "confirm_require": true
        },
        "ota": {
            "content": "本次更新包含重要功能改进",
            "notice": "⚠️ 本版本与旧版彩带小工具不兼容，请先卸载旧版",
            "changelog": [
                {"version": "1.1.4", "changes": "新增 iOS Web 版入口；修复兼容性问题"},
                {"version": "1.0.2", "changes": "修复了一些问题"}
            ],
            "android": {
                "version": "1.1.4",
                "force_ota": false,
                "url": "https://example.com/app-1.1.4.apk",
                "md5": "abc123..."
            },
            "ios": {
                "version": "1.1.4",
                "force_ota": false,
                "url": "https://ace.linota.cn",
                "type": "web"
            }
        }
    }
    """
    announcement: Optional[Announcement] = Field(default=None, description="公告信息")
    ota: Optional[OTA] = Field(default=None, description="OTA 更新信息")
