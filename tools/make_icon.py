"""카페테리아 아이콘 생성 스크립트.

사용법: python3 tools/make_icon.py
결과물: branding/icon.png (512px), branding/icon.ico, 모드 아이콘(128px)
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SIZE = 1024  # 크게 그린 뒤 줄여서 가장자리를 부드럽게 만든다.

BACKGROUND = (38, 26, 20, 255)
CUP = (245, 233, 214, 255)
COFFEE = (111, 69, 43, 255)
ACCENT = (242, 166, 90, 255)


def draw_icon() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=220, fill=BACKGROUND)

    # 손잡이: 컵 몸통 뒤에 깔리는 고리
    draw.ellipse((640, 470, 880, 710), outline=CUP, width=58)

    # 컵 몸통과 커피 표면
    draw.rounded_rectangle((230, 430, 730, 800), radius=110, fill=CUP)
    draw.rectangle((230, 430, 730, 520), fill=CUP)
    draw.ellipse((262, 446, 698, 534), fill=COFFEE)

    # 받침
    draw.rounded_rectangle((170, 820, 790, 866), radius=23, fill=CUP)

    # 김
    for x in (360, 480, 600):
        draw.line([(x, 370), (x - 34, 300), (x + 34, 230), (x, 160)], fill=ACCENT, width=38, joint="curve")
        for cx, cy in ((x, 370), (x, 160)):
            draw.ellipse((cx - 19, cy - 19, cx + 19, cy + 19), fill=ACCENT)

    return image


def main() -> None:
    icon = draw_icon()
    branding = ROOT / "branding"
    branding.mkdir(exist_ok=True)
    icon.resize((512, 512), Image.LANCZOS).save(branding / "icon.png")
    icon.resize((256, 256), Image.LANCZOS).save(branding / "icon.ico", sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    mod_icon = ROOT / "mod" / "src" / "main" / "resources" / "assets" / "cafeteria" / "icon.png"
    mod_icon.parent.mkdir(parents=True, exist_ok=True)
    icon.resize((128, 128), Image.LANCZOS).save(mod_icon)
    print("아이콘 생성 완료:", branding / "icon.png")


if __name__ == "__main__":
    main()
