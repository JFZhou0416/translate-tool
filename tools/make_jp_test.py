# -*- coding: utf-8 -*-
"""
生成日文人名测试图（用来验证术语表对"人名固定译法"是否生效）。

用法：
    python tools/make_jp_test.py [输出路径]

为什么要故意这样测：这张图不带术语表时，模型自己就能把 ヒロ 稳定译成「希罗」——
所以必须**故意指定一个模型绝不会自选的译名**（例如 ヒロ=英雄），
才能证明"术语表压过了模型默认"，否则测了等于没测。

验证命令：
    set DEEPSEEK_KEY=sk-xxx
    python tools/api_smoke_test.py tools/jp_test.png                                  # 基线：应为「希罗」
    python tools/api_smoke_test.py tools/jp_test.png --glossary="ヒロ=英雄;エマ=艾玛"   # 覆盖：应为「英雄」
"""
import sys

from PIL import Image, ImageDraw, ImageFont

JP_FONT_BOLD = "C:/Windows/Fonts/YuGothB.ttc"
JP_FONT = "C:/Windows/Fonts/YuGothR.ttc"

LINES = [
    "クエスト開始",
    "ヒロ「エマ、待たせたな」",
    "エマ「遅いよ、ヒロ」",
    "ヒロ「すまん、装備を整えてた」",
    "エマ「また武器屋に寄り道したでしょ」",
    "ヒロ「よく分かったな」",
]


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "tools/jp_test.png"
    w, h = 900, 620
    img = Image.new("RGB", (w, h), (18, 20, 26))
    d = ImageDraw.Draw(img)
    d.text((40, 30), LINES[0], font=ImageFont.truetype(JP_FONT_BOLD, 40), fill=(240, 210, 90))
    y = 120
    f = ImageFont.truetype(JP_FONT, 36)
    for ln in LINES[1:]:
        d.text((40, y), ln, font=f, fill=(235, 237, 242))
        y += 80
    img.save(out)
    print(f"saved {out} {img.size}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
