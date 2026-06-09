#!/usr/bin/env python3
"""LoveACE 发版 & 公告 CLI"""
import hashlib
import json
from pathlib import Path
from typing import Optional

import typer
from rich import print
from rich.console import Console
from rich.panel import Panel
from rich.prompt import Confirm, Prompt
from rich.table import Table

from config import get_settings
from manifest import Announcement, ChangelogEntry, LoveACEManifest, OTA, PlatformRelease
from s3_client import S3Client

PLATFORMS = ["android", "ios", "windows", "macos", "linux"]

app = typer.Typer(help="LoveACE 发版管理工具")
console = Console()

MANIFEST_KEY = "loveace/manifest.json"
DOWNLOAD_PAGE_KEY = "loveace/download.html"
FAVICON_KEY = "loveace/favicon.png"


def get_file_md5(file_path: str) -> str:
    """计算文件 MD5"""
    md5 = hashlib.md5()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            md5.update(chunk)
    return md5.hexdigest()


def load_manifest(client: S3Client) -> LoveACEManifest:
    """加载现有 manifest"""
    data = client.get_json(MANIFEST_KEY)
    if data:
        return LoveACEManifest.model_validate(data)
    return LoveACEManifest()


def save_manifest(client: S3Client, manifest: LoveACEManifest) -> str:
    """保存 manifest"""
    content = manifest.model_dump_json(indent=2, exclude_none=True)
    return client.upload_content(content, MANIFEST_KEY)


@app.command()
def announce(
    title: str = typer.Option(..., "--title", "-t", help="公告标题"),
    content: str = typer.Option(..., "--content", "-c", help="公告内容"),
    confirm_require: bool = typer.Option(False, "--confirm", help="是否需要用户确认"),
):
    """发布公告"""
    client = S3Client()
    manifest = load_manifest(client)

    announcement = Announcement(
        title=title,
        content=content,
        confirm_require=confirm_require,
    )
    manifest.announcement = announcement

    url = save_manifest(client, manifest)

    console.print(Panel.fit(
        f"[bold green]✅ 公告发布成功[/]\n\n"
        f"[cyan]标题:[/] {title}\n"
        f"[cyan]MD5:[/] {announcement.md5}\n"
        f"[cyan]需确认:[/] {'是' if confirm_require else '否'}",
        title="公告",
    ))
    print(f"[dim]Manifest URL: {url}[/]")


@app.command()
def clear_announce():
    """清除公告"""
    client = S3Client()
    manifest = load_manifest(client)
    manifest.announcement = None
    save_manifest(client, manifest)
    console.print("[green]✅ 公告已清除[/]")


@app.command()
def notice(
    content: str = typer.Option(..., "--content", "-c", help="通知内容（显示在下载页面）"),
):
    """设置下载页面通知横幅（如兼容性警告）"""
    client = S3Client()
    manifest = load_manifest(client)

    if not manifest.ota:
        manifest.ota = OTA()
    manifest.ota.notice = content
    url = save_manifest(client, manifest)

    console.print(Panel.fit(
        f"[bold green]✅ 通知已设置[/]\n\n"
        f"[cyan]内容:[/] {content}",
        title="下载页通知",
    ))
    print(f"[dim]Manifest URL: {url}[/]")


@app.command()
def clear_notice():
    """清除下载页面通知横幅"""
    client = S3Client()
    manifest = load_manifest(client)
    if manifest.ota:
        manifest.ota.notice = None
        save_manifest(client, manifest)
    console.print("[green]✅ 通知已清除[/]")


@app.command()
def web_release(
    version: str = typer.Option(..., "--version", "-v", help="版本号"),
    platform: str = typer.Option(..., "--platform", "-p", help="平台 (ios 等)"),
    url: str = typer.Option(..., "--url", "-u", help="Web App 地址"),
    changelog: str = typer.Option("", "--changelog", help="本次更新日志"),
):
    """发布 Web 平台版本（无安装包，仅跳转链接）"""
    if platform not in PLATFORMS:
        console.print(f"[red]❌ 不支持的平台: {platform}，支持: {', '.join(PLATFORMS)}[/]")
        raise typer.Exit(1)

    client = S3Client()
    manifest = load_manifest(client)

    changelogs = []
    if changelog:
        changelogs.append(ChangelogEntry(version=version, changes=changelog))
    if manifest.ota and manifest.ota.changelog:
        for entry in manifest.ota.changelog:
            if entry.version != version and len(changelogs) < 10:
                changelogs.append(entry)

    if manifest.ota:
        ota = manifest.ota
        ota.changelog = changelogs
    else:
        ota = OTA(changelog=changelogs)

    platform_release = PlatformRelease(
        version=version,
        url=url,
        type="web",
    )
    setattr(ota, platform, platform_release)
    manifest.ota = ota

    manifest_url = save_manifest(client, manifest)

    console.print(Panel.fit(
        f"[bold green]✅ Web 版本发布成功[/]\n\n"
        f"[cyan]版本:[/] {version}\n"
        f"[cyan]平台:[/] {platform}\n"
        f"[cyan]Web App:[/] {url}",
        title="Web 发布",
    ))
    print(f"[dim]Manifest URL: {manifest_url}[/]")


@app.command()
def release(
    version: str = typer.Option(..., "--version", "-v", help="版本号"),
    platform: str = typer.Option(..., "--platform", "-p", help="平台 (android/ios/windows/macos/linux)"),
    file: Path = typer.Option(..., "--file", "-f", help="安装包路径"),
    force: bool = typer.Option(False, "--force", help="该平台强制更新"),
    content: str = typer.Option("", "--content", "-c", help="OTA 弹窗内容（所有平台共享）"),
    changelog: str = typer.Option("", "--changelog", help="本次更新日志"),
):
    """发布新版本（单平台）
    
    每个平台独立管理版本号和强制更新标志。
    content 和 changelog 为所有平台共享。
    """
    if platform not in PLATFORMS:
        console.print(f"[red]❌ 不支持的平台: {platform}，支持: {', '.join(PLATFORMS)}[/]")
        raise typer.Exit(1)

    if not file.exists():
        console.print(f"[red]❌ 文件不存在: {file}[/]")
        raise typer.Exit(1)

    client = S3Client()
    manifest = load_manifest(client)

    # 计算 MD5
    file_md5 = get_file_md5(str(file))
    console.print(f"[dim]文件 MD5: {file_md5}[/]")

    # 上传安装包
    s3_key = f"loveace/releases/{platform}/{version}/{file.name}"
    with console.status(f"[bold blue]上传 {file.name}..."):
        download_url = client.upload_file(str(file), s3_key)

    # 构建 changelog
    changelogs = []
    if changelog:
        changelogs.append(ChangelogEntry(version=version, changes=changelog))

    # 保留旧的 changelog (最多9条，去重)
    if manifest.ota and manifest.ota.changelog:
        for entry in manifest.ota.changelog:
            if entry.version != version and len(changelogs) < 10:
                changelogs.append(entry)

    # 创建或更新 OTA
    if manifest.ota:
        # 更新现有 OTA
        ota = manifest.ota
        ota.changelog = changelogs
        if content:
            ota.content = content
    else:
        # 新建 OTA
        ota = OTA(
            content=content,
            changelog=changelogs,
        )

    # 设置平台下载信息（每个平台独立版本号和强制更新标志）
    platform_release = PlatformRelease(
        version=version,
        force_ota=force,
        url=download_url,
        md5=file_md5,
    )
    setattr(ota, platform, platform_release)

    manifest.ota = ota
    url = save_manifest(client, manifest)

    console.print(Panel.fit(
        f"[bold green]✅ 版本发布成功[/]\n\n"
        f"[cyan]版本:[/] {version}\n"
        f"[cyan]平台:[/] {platform}\n"
        f"[cyan]MD5:[/] {file_md5}\n"
        f"[cyan]强制更新:[/] {'是' if force else '否'}\n"
        f"[cyan]下载地址:[/] {download_url}",
        title="OTA 发布",
    ))
    print(f"[dim]Manifest URL: {url}[/]")


@app.command()
def status():
    """查看当前状态"""
    client = S3Client()
    manifest = load_manifest(client)

    # 公告状态
    if manifest.announcement:
        a = manifest.announcement
        console.print(Panel(
            f"[cyan]标题:[/] {a.title}\n"
            f"[cyan]内容:[/] {a.content}\n"
            f"[cyan]MD5:[/] {a.md5}\n"
            f"[cyan]需确认:[/] {'是' if a.confirm_require else '否'}",
            title="📢 当前公告",
        ))
    else:
        console.print("[dim]📢 暂无公告[/]")

    # OTA 状态
    if manifest.ota:
        o = manifest.ota
        ota_info = f"[cyan]弹窗内容:[/] {o.content or '(无)'}"
        if o.notice:
            ota_info += f"\n[yellow]通知横幅:[/] {o.notice}"
        console.print(Panel(ota_info, title="📦 OTA 配置"))

        # 平台信息表格
        platform_table = Table(title="平台发布信息")
        platform_table.add_column("平台", style="cyan")
        platform_table.add_column("类型", style="magenta")
        platform_table.add_column("版本", style="green")
        platform_table.add_column("强制更新", style="yellow")
        platform_table.add_column("MD5 / URL")

        for p in PLATFORMS:
            release = getattr(o, p, None)
            if release:
                release_type = release.type if hasattr(release, "type") else "native"
                md5_or_url = release.url if release_type == "web" else (release.md5[:16] + "..." if release.md5 else "-")
                platform_table.add_row(
                    p.upper(),
                    release_type,
                    release.version,
                    "是" if release.force_ota else "否",
                    md5_or_url,
                )
        console.print(platform_table)

        if o.changelog:
            table = Table(title="更新日志")
            table.add_column("版本", style="cyan")
            table.add_column("更新内容")
            for entry in o.changelog:
                table.add_row(entry.version, entry.changes)
            console.print(table)
    else:
        console.print("[dim]📦 暂无版本信息[/]")


@app.command()
def set_force(
    platform: str = typer.Option(..., "--platform", "-p", help="平台 (android/ios/windows/macos/linux)"),
    force: bool = typer.Option(..., "--force", "-f", help="是否强制更新"),
):
    """设置指定平台的强制更新标志"""
    if platform not in PLATFORMS:
        console.print(f"[red]❌ 不支持的平台: {platform}，支持: {', '.join(PLATFORMS)}[/]")
        raise typer.Exit(1)

    client = S3Client()
    manifest = load_manifest(client)

    if not manifest.ota:
        console.print("[red]❌ 暂无 OTA 配置[/]")
        raise typer.Exit(1)

    release = getattr(manifest.ota, platform, None)
    if not release:
        console.print(f"[red]❌ 平台 {platform} 暂无发布信息[/]")
        raise typer.Exit(1)

    # 更新强制更新标志
    release.force_ota = force
    setattr(manifest.ota, platform, release)

    save_manifest(client, manifest)
    console.print(f"[green]✅ 已{'启用' if force else '禁用'} {platform.upper()} 平台的强制更新[/]")


@app.command()
def deploy_page():
    """部署下载页面和 Logo"""
    client = S3Client()
    base_path = Path(__file__).parent
    html_path = base_path / "download.html"
    favicon_path = base_path.parent / "web" / "favicon.png"

    if not html_path.exists():
        console.print("[red]❌ download.html 不存在[/]")
        raise typer.Exit(1)

    with console.status("[bold blue]上传下载页面..."):
        html_url = client.upload_file(str(html_path), DOWNLOAD_PAGE_KEY)

    console.print(f"[green]✅ 下载页面已部署[/]")
    print(f"[dim]URL: {html_url}[/]")

    if favicon_path.exists():
        with console.status("[bold blue]上传 Favicon..."):
            favicon_url = client.upload_file(str(favicon_path), FAVICON_KEY)
        console.print(f"[green]✅ Favicon 已部署[/]")
        print(f"[dim]URL: {favicon_url}[/]")
    else:
        console.print("[yellow]⚠️ favicon.png 不存在，跳过[/]")


if __name__ == "__main__":
    app()
