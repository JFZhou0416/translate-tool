# -*- coding: utf-8 -*-
"""
识图翻译 API 冒烟测试 —— 不依赖手机，直接验计划书 3.3 的调用规格。

用法（key 走环境变量，不要写进文件）：
    set DEEPSEEK_KEY=sk-xxx
    python tools/api_smoke_test.py "D:\\无明工作\\_vision_test.png"

验证点：
  1. 图能连通（image_url + base64 data URL）
  2. thinking:{type:"disabled"} 被接受（不报 400）
  3. 耗时（一期验收要求端到端 ≤3s）
  4. 输出格式是否含【原文】/【译文】（译文卡片的解析依赖这个）
  5. 术语陷阱词 vault 是否译成"仓库"（v2 实测基线）
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
MODEL = "deepseek-v4-flash"

PROMPT = """你是屏幕翻译助手。识别图中所有可见文字（保持原有版式顺序），然后翻译成中文。输出格式：
【原文】...
【译文】...
要求：术语准确、简洁，不要任何解释。"""


def main() -> int:
    key = os.environ.get("DEEPSEEK_KEY", "").strip()
    if not key:
        print("缺少环境变量 DEEPSEEK_KEY")
        return 2
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    glossary = ""
    for a in sys.argv[1:]:
        if a.startswith("--glossary="):
            glossary = a.split("=", 1)[1]
    if not args:
        print("用法: python tools/api_smoke_test.py <图片路径> [--glossary=vault=仓库]")
        return 2

    path = args[0]
    with open(path, "rb") as f:
        raw = f.read()
    print(f"图片: {path}\n大小: {len(raw)/1024:.1f} KB")

    prompt = PROMPT
    if glossary:
        # 跟 App 里 DeepSeekClient.promptWithGlossary 保持一致
        block = "\n".join(x.strip() for x in glossary.replace(";", "\n").splitlines() if x.strip())
        prompt += (
            "\n\n术语对照（必须严格遵守，优先于你的默认译法）：\n" + block +
            "\n\n人名、地名、作品名等专有名词：上表里有的必须用表中译名；"
            "上表没有的，用通行译名，并保证同一个名称前后译法一致，不要出现多种译法。"
        )
        print(f"术语表: {block}")

    body = {
        "model": MODEL,
        "thinking": {"type": "disabled"},
        "max_tokens": 1500,
        "temperature": 0.1,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url",
                 "image_url": {"url": "data:image/png;base64," + base64.b64encode(raw).decode()}},
                {"type": "text", "text": prompt},
            ],
        }],
    }

    req = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
    )

    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            resp = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"[失败] HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:500]}")
        return 1
    dt = time.time() - t0

    content = resp["choices"][0]["message"]["content"]
    usage = resp.get("usage", {})

    print(f"\n耗时: {dt:.2f}s")
    print(f"tokens: 入 {usage.get('prompt_tokens')} / 出 {usage.get('completion_tokens')}")
    print(f"实际模型: {resp.get('model')}")
    print("-" * 60)
    print(content)
    print("-" * 60)

    # 验收断言
    ok = True
    checks = [
        ("端到端 ≤3s", dt <= 3.0, f"{dt:.2f}s"),
        ("含【原文】", "【原文】" in content, ""),
        ("含【译文】", "【译文】" in content, ""),
    ]
    for name, passed, extra in checks:
        print(f"[{'OK ' if passed else 'NG '}] {name} {extra}")
        ok = ok and passed

    vault_ok = "仓库" in content
    print(f"[{'OK ' if vault_ok else 'NG '}] 术语 vault → 仓库（v2 基线）")
    ok = ok and vault_ok

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
