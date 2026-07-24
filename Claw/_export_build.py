#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os

WORKSPACE = r"C:\Users\YTO-02231406\WorkBuddy\Claw"

# 按学习路线图排序的 md 文件(排除无关文件)
ORDER = [
    ("一、总览与路线图", [
        "app_to_framework_guide.md",
        "framework_index_aosp14.md",
        "android_framework_paper.md",
    ]),
    ("二、编译烧录", [
        "android14_build.md",
        "aosp-build-guide.md",
    ]),
    ("三、Binder / AIDL", [
        "binder_aidl.md",
    ]),
    ("四、AMS / 四大组件", [
        "ams_deep_dive.md",
        "ams_modify_practice.md",
    ]),
    ("五、HAL / 外设适配", [
        "hal_android14.md",
        "hal_version_history.md",
        "hal_example_android14.md",
        "hal_learning_roadmap.md",
    ]),
    ("六、Settings / 系统裁剪", [
        "framework_settings_analysis.md",
        "settings_modify_practice.md",
    ]),
    ("七、WMS 窗口管理", [
        "wms_deep_dive.md",
    ]),
    ("八、Input 事件分发", [
        "input_deep_dive.md",
    ]),
    ("九、SystemUI 定制", [
        "systemui_customization.md",
    ]),
    ("十、SELinux 策略", [
        "selinux_policy.md",
    ]),
    ("十一、性能 / 排障 (Perfetto/ANR)", [
        "perfetto_anr_troubleshooting.md",
    ]),
    ("十二、新增纯系统服务 (含 AIDL)", [
        "system_service_aidl.md",
    ]),
]

toc_lines = ["# AOSP 14 Framework 学习笔记 · 导出合集", ""]
toc_lines.append("> 自动合并生成于脚本 `_export_build.py`。本合集汇总工作区全部 Framework 学习 Markdown 笔记，按学习路线图分章。")
toc_lines.append("> 适用版本：Android 14 (API 34, UpsideDownCake)。")
toc_lines.append("")
toc_lines.append("## 目录")
toc_lines.append("")

body = []
for sec_idx, (sec_title, files) in enumerate(ORDER, 1):
    toc_lines.append(f"### {sec_title}")
    body.append(f"\n\n---\n\n# {sec_title}\n")
    for f in files:
        path = os.path.join(WORKSPACE, f)
        if not os.path.isfile(path):
            body.append(f"\n## {f}\n\n> 文件缺失，跳过。\n")
            continue
        with open(path, "r", encoding="utf-8") as fh:
            content = fh.read().rstrip("\n")
        # TOC entry
        toc_lines.append(f"- [{f}]({f})")
        # section header
        body.append(f"\n\n## {f}\n\n")
        body.append(content)
        body.append("\n")

out = "\n".join(toc_lines) + "\n\n" + "\n".join(body) + "\n"

out_path = os.path.join(WORKSPACE, "framework_notes_export.md")
with open(out_path, "w", encoding="utf-8") as fh:
    fh.write(out)

print("WROTE:", out_path)
print("BYTES:", len(out.encode("utf-8")))
print("SECTIONS:", len(ORDER))
total_files = sum(len(fs) for _, fs in ORDER)
print("FILES_MERGED:", total_files)
