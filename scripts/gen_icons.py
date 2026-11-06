#!/usr/bin/env python3
"""原创绘制应用图标与 UI 图标。

设计概念：
  三个授权节点 -> 向下箭头（安装）-> 开口托盘（落位）。
  全部矢量路径与配色均为本实现原创。

输出：
  icons/*.svg                       可读矢量源
  android/app/src/main/res/**/*      Android vector drawable + 各密度 PNG
运行: python3 scripts/gen_icons.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
ICONS = ROOT / "icons"
ICONS.mkdir(exist_ok=True)

BG = "#0E5C52"
FG = "#FFFFFF"
ACCENT = "#78E3C1"

DENSITIES = {
    "mipmap-mdpi-v4": (48, 108),
    "mipmap-hdpi-v4": (72, 162),
    "mipmap-xhdpi-v4": (96, 216),
    "mipmap-xxhdpi-v4": (144, 324),
    "mipmap-xxxhdpi-v4": (192, 432),
}


def P(scale):
    return lambda x, y: (int(round(x * scale)), int(round(y * scale)))


def draw_glyph(d, scale, fg=FG, accent=ACCENT, stroke_scale=1.0):
    p = P(scale)
    to_rgb = lambda hx: tuple(int(hx[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
    fg_c, ac_c = to_rgb(fg), to_rgb(accent)
    w_arrow = max(1, int(round(6 * scale * stroke_scale)))
    w_tray = max(1, int(round(5 * scale * stroke_scale)))
    w_arc = max(1, int(round(2 * scale * stroke_scale)))

    # --- 授权节点：三个圆点 + 一段上弧 ---
    d.line([p(38, 24), p(46, 16), p(62, 16), p(70, 24)], fill=ac_c, width=w_arc, joint="curve")
    for (x, y) in ((38, 24), (54, 15), (70, 24)):
        cx, cy = p(x, y)
        r = max(1, int(round(3.2 * scale)))
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ac_c)

    # --- 向下箭头 ---
    d.line([p(54, 32), p(54, 60)], fill=fg_c, width=w_arrow)
    d.line([p(40, 52), p(54, 66), p(68, 52)], fill=fg_c, width=w_arrow, joint="curve")
    for (x, y) in ((54, 32), (40, 52), (68, 52)):
        cx, cy = p(x, y)
        r = w_arrow / 2
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fg_c)

    # --- 开口托盘 ---
    tray = [p(28, 68), p(28, 76), p(30, 79), p(34, 81), p(74, 81), p(78, 79), p(80, 76), p(80, 68)]
    d.line(tray, fill=fg_c, width=w_tray, joint="curve")
    for pt in (tray[0], tray[-1]):
        r = w_tray / 2
        d.ellipse((pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r), fill=fg_c)


def make_png(size, *, background=None, round_mask=False, foreground_only=False, monochrome=False):
    scale = size / 108.0
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if background:
        d0 = ImageDraw.Draw(img)
        d0.rectangle((0, 0, size, size), fill=background)
    d = ImageDraw.Draw(img)
    draw_glyph(d, scale, fg="#FFFFFF" if monochrome else FG,
               accent="#FFFFFF" if monochrome else ACCENT)
    if round_mask:
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4, size * 4), fill=255)
        mask = mask.resize((size, size), Image.LANCZOS)
        img.putalpha(mask)
    return img


# ---------------------------------------------------------------- SVG 源
def svg(color_fg=FG, color_accent=ACCENT, with_bg=True):
    bg = f'<rect width="108" height="108" fill="{BG}"/>' if with_bg else ""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <title>Shizuku Installer - original icon</title>
  {bg}
  <g fill="none" stroke="{color_accent}" stroke-width="2" stroke-linecap="round">
    <path d="M38,24 Q54,8 70,24"/>
  </g>
  <g fill="{color_accent}">
    <circle cx="38" cy="24" r="3.2"/><circle cx="54" cy="15" r="3.2"/><circle cx="70" cy="24" r="3.2"/>
  </g>
  <g fill="none" stroke="{color_fg}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round">
    <path d="M54,32 L54,60"/>
    <path d="M40,52 L54,66 L68,52"/>
  </g>
  <path d="M28,68 L28,76 Q28,82 34,82 L74,82 Q80,82 80,76 L80,68"
        fill="none" stroke="{color_fg}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
'''


(ICONS / "icon_source.svg").write_text(svg(), encoding="utf-8")
(ICONS / "adaptive_foreground.svg").write_text(svg(with_bg=False), encoding="utf-8")
(ICONS / "adaptive_monochrome.svg").write_text(
    svg(color_fg="#FFFFFF", color_accent="#FFFFFF", with_bg=False), encoding="utf-8")

# ---------------------------------------------------------------- vector drawables
def vector_xml(monochrome=False):
    fg = "#FFFFFF" if monochrome else FG
    ac = "#FFFFFF" if monochrome else ACCENT
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M38,24 Q54,8 70,24" android:strokeColor="{ac}"
        android:strokeWidth="2" android:strokeLineCap="round" android:fillColor="#00000000"/>
    <path android:pathData="M38,24 m-3.2,0 a3.2,3.2 0 1,0 6.4,0 a3.2,3.2 0 1,0 -6.4,0 M54,15 m-3.2,0 a3.2,3.2 0 1,0 6.4,0 a3.2,3.2 0 1,0 -6.4,0 M70,24 m-3.2,0 a3.2,3.2 0 1,0 6.4,0 a3.2,3.2 0 1,0 -6.4,0"
        android:fillColor="{ac}"/>
    <path android:pathData="M54,32 L54,60 M40,52 L54,66 L68,52"
        android:strokeColor="{fg}" android:strokeWidth="6" android:strokeLineCap="round"
        android:strokeLineJoin="round" android:fillColor="#00000000"/>
    <path android:pathData="M28,68 L28,76 Q28,82 34,82 L74,82 Q80,82 80,76 L80,68"
        android:strokeColor="{fg}" android:strokeWidth="5" android:strokeLineCap="round"
        android:strokeLineJoin="round" android:fillColor="#00000000"/>
</vector>
'''


(RES / "drawable/ic_launcher_foreground.xml").write_text(vector_xml(False), encoding="utf-8")
(RES / "drawable/ic_launcher_monochrome.xml").write_text(vector_xml(True), encoding="utf-8")

(RES / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">{BG}</color>\n'
    '    <color name="brand_primary">#0E5C52</color>\n'
    '    <color name="brand_accent">#78E3C1</color>\n'
    '</resources>\n', encoding="utf-8")

adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
(RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(adaptive, encoding="utf-8")
(RES / "mipmap-anydpi-v26/ic_launcher_round.xml").write_text(adaptive, encoding="utf-8")

# ---------------------------------------------------------------- PNG（各密度）
for folder, (legacy, fore) in DENSITIES.items():
    out = RES / folder
    out.mkdir(parents=True, exist_ok=True)
    bg_rgba = tuple(int(BG[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
    make_png(legacy, background=bg_rgba).save(out / "ic_launcher.png")
    make_png(legacy, background=bg_rgba, round_mask=True).save(out / "ic_launcher_round.png")
    make_png(fore, foreground_only=True).save(out / "ic_launcher_foreground.png")

# ---------------------------------------------------------------- 通知 / UI 图标
UI_ICONS = {
    # 24dp viewport，全部为原创路径
    "ic_action_install": 'M12,3 L12,14 M7,10 L12,15 L17,10 M4,18 L4,20 L20,20 L20,18',
    "ic_action_uninstall": 'M5,7 L19,7 M9,7 L9,5 L15,5 L15,7 M7,7 L8,20 L16,20 L17,7 M10,10 L10,17 M14,10 L14,17',
    "ic_action_shizuku": 'M12,3 L20,7 L20,13 Q20,18 12,21 Q4,18 4,13 L4,7 Z M9,12 L11,14 L15,10',
    "ic_action_settings": 'M12,8 A4,4 0 1,0 12,16 A4,4 0 1,0 12,8 M12,2 L13.4,4.2 L16,4.8 L15.4,7.4 L17,9.5 L15.6,11.8 L16.2,14.4 L13.6,15 L12,17.2 L10.4,15 L7.8,14.4 L8.4,11.8 L7,9.5 L8.6,7.4 L8,4.8 L10.6,4.2 Z',
    "ic_action_file": 'M6,2 L14,2 L18,6 L18,22 L6,22 Z M14,2 L14,6 L18,6 M9,12 L15,12 M9,16 L15,16',
    "ic_action_launch": 'M5,5 L19,5 L19,19 L5,19 Z M9,15 L15,9 M11,9 L15,9 L15,13',
    "ic_action_copy": 'M8,8 L20,8 L20,20 L8,20 Z M16,8 L16,4 L4,4 L4,16 L8,16',
    "ic_action_notification": 'M12,3 L12,14 M7,10 L12,15 L17,10 M4,18 L4,20 L20,20 L20,18',
}
for name, path in UI_ICONS.items():
    stroke = "2" if name != "ic_action_shizuku" else "1.8"
    (RES / f"drawable/{name}.xml").write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?android:attr/colorControlNormal">
    <path android:pathData="{path}" android:strokeColor="#FFFFFF"
        android:strokeWidth="{stroke}" android:strokeLineCap="round"
        android:strokeLineJoin="round" android:fillColor="#00000000"/>
</vector>
''', encoding="utf-8")

print("generated:", len(list(RES.rglob("*.png"))), "png +",
      len(list(RES.rglob("*.xml"))), "xml")
