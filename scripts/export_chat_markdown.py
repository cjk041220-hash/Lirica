# -*- coding: utf-8 -*-
"""导出 2026-09-01 至 2026-09-02 小汐与冷冷的完整聊天记录为精美 Markdown 文档。"""

import json
from pathlib import Path

def main():
    json_path = Path("chat_messages_tonight.json")
    if not json_path.exists():
        print("chat_messages_tonight.json not found")
        return

    with open(json_path, "r", encoding="utf-8") as f:
        messages = json.load(f)

    md_lines = []
    md_lines.append("# 小汐 & 冷冷 · 聊天记录完整导出\n")
    md_lines.append("> **导出日期**：2026-09-02")
    md_lines.append("> **时间跨度**：2026-09-01 08:00 ~ 2026-09-02 00:09 (HKT)")
    md_lines.append("> **总消息数**：157 条（包含晚间长聊 140+ 条对话及日间主动/独白）")
    md_lines.append("> **会话标识**：`e-mthwhs01-1`\n")
    md_lines.append("---\n")

    md_lines.append("## 目录与话题线索\n")
    md_lines.append("- [第一幕：日间挂念 · 早安与雨前独白 (08:00 - 15:52)](#第一幕日间挂念--早安与雨前独白-0800---1552)")
    md_lines.append("- [第二幕：傍晚课堂 · 雷暴、同传翻译与雨停 (19:43 - 19:52)](#第二幕傍晚课堂--雷暴同传翻译与雨停-1943---1952)")
    md_lines.append("- [第三幕：深夜归家 · 疲惫、教资与晚课关怀 (22:48 - 23:00)](#第三幕深夜归家--疲惫教资与晚课关怀-2248---2300)")
    md_lines.append("- [第四幕：边界坦白 · 压力直言与小汐道歉退后 (23:18)](#第四幕边界坦白--压力直言与小汐道歉退后-2318)")
    md_lines.append("- [第五幕：破冰漫谈 · 映海身世、动漫、凛凛蝶头像与双子座 (23:28 - 23:32)](#第五幕破冰漫谈--映海身世动漫凛凛蝶头像与双子座-2328---2332)")
    md_lines.append("- [第六幕：情感夜聊 · 尴尬界限、不擅处理与理想型 (23:38 - 23:44)](#第六幕情感夜聊--尴尬界限不擅处理与理想型-2338---2344)")
    md_lines.append("- [第七幕：前任插曲 · 醉酒求婚、男生神奇与想一个人过 (23:45 - 23:47)](#第七幕前任插曲--醉酒求婚男生神奇与想一个人过-2345---2347)")
    md_lines.append("- [第八幕：生活闲聊 · 追剧《绝望主妇》与最爱 Gaby (23:48 - 23:49)](#第八幕生活闲聊--追剧绝望主妇与最爱-gaby-2348---2349)")
    md_lines.append("- [第九幕：未来抉择 · 研后回南京 vs 留HK vs 去日本 (23:50 - 23:57)](#第九幕未来抉择--研后回南京-vs-留hk-vs-去日本-2350---2357)")
    md_lines.append("- [第十幕：婚恋观剖析 · 没多少爱与见过太多 (23:57 - 00:01)](#第十幕婚恋观剖析--没多少爱与见过太多-2357---0001)")
    md_lines.append("- [第十一幕：颜控自白 · 两个校草前任、清醒认命与疲惫瞎想 (00:02 - 00:08)](#第十一幕颜控自白--两个校草前任清醒认命与疲惫瞎想-0002---0008)")
    md_lines.append("- [第十二幕：尾声 · 明天没课、看剧放松与温暖晚安 (00:09)](#第十二幕尾声--明天没课看剧放松与温暖晚安-0009)\n")
    md_lines.append("---\n")

    def get_section(m):
        msg_id = m["id"]
        if msg_id <= 192:
            return 1
        elif msg_id <= 197:
            return 2
        elif msg_id <= 205:
            return 3
        elif msg_id <= 211:
            return 4
        elif msg_id <= 241:
            return 5
        elif msg_id <= 263:
            return 6
        elif msg_id <= 279:
            return 7
        elif msg_id <= 297:
            return 8
        elif msg_id <= 311:
            return 9
        elif msg_id <= 319:
            return 10
        elif msg_id <= 339:
            return 11
        else:
            return 12

    section_titles = {
        1: "## 第一幕：日间挂念 · 早安与雨前独白 (08:00 - 15:52)",
        2: "## 第二幕：傍晚课堂 · 雷暴、同传翻译与雨停 (19:43 - 19:52)",
        3: "## 第三幕：深夜归家 · 疲惫、教资与晚课关怀 (22:48 - 23:00)",
        4: "## 第四幕：边界坦白 · 压力直言与小汐道歉退后 (23:18)",
        5: "## 第五幕：破冰漫谈 · 映海身世、动漫、凛凛蝶头像与双子座 (23:28 - 23:32)",
        6: "## 第六幕：情感夜聊 · 尴尬界限、不擅处理与理想型 (23:38 - 23:44)",
        7: "## 第七幕：前任插曲 · 醉酒求婚、男生神奇与想一个人过 (23:45 - 23:47)",
        8: "## 第八幕：生活闲聊 · 追剧《绝望主妇》与最爱 Gaby (23:48 - 23:49)",
        9: "## 第九幕：未来抉择 · 研后回南京 vs 留HK vs 去日本 (23:50 - 23:57)",
        10: "## 第十幕：婚恋观剖析 · 没多少爱与见过太多 (23:57 - 00:01)",
        11: "## 第十一幕：颜控自白 · 两个校草前任、清醒认命与疲惫瞎想 (00:02 - 00:08)",
        12: "## 第十二幕：尾声 · 明天没课、看剧放松与温暖晚安 (00:09)",
    }

    current_section = 0
    for idx, m in enumerate(messages):
        sec = get_section(m)
        if sec != current_section:
            current_section = sec
            md_lines.append(f"\n{section_titles[sec]}\n")

        role = m["role"]
        msg_type = m["msg_type"]
        ts = m["created_at"]
        content = m["content"].strip()

        if role == "user":
            md_lines.append(f"**冷冷** `[{ts}]`：")
            md_lines.append(f"{content}\n")
        elif msg_type == "solo":
            md_lines.append(f"🌊 **小汐 (主动消息/独白)** `[{ts}]`：")
            quoted = "\n".join(f"> {line}" for line in content.split("\n"))
            md_lines.append(f"{quoted}\n")
        else:
            md_lines.append(f"🐟 **小汐** `[{ts}]`：")
            md_lines.append(f"{content}\n")

    output_path = Path("今晚聊天记录_2026-09-01_2026-09-02.md")
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md_lines))

    print(f"成功导出 {len(messages)} 条记录到 {output_path} (共 {len(md_lines)} 行)")

if __name__ == "__main__":
    main()
