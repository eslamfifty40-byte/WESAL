from PIL import Image
src = Image.open("icon1.png").convert("RGBA")
bg = src.getpixel((src.width // 2, 6))
bg = bg[:3] if bg[3] > 200 else (20, 0, 30)
res = "android/app/src/main/res"
sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
for d, s in sizes.items():
    fg = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    a = int(s * 0.62)
    art = src.resize((a, a), Image.LANCZOS)
    fg.paste(art, ((s - a) // 2, (s - a) // 2), art)
    fg.save(f"{res}/mipmap-{d}/ic_launcher_foreground.png")
open(f"{res}/values/ic_launcher_background.xml", "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#%02x%02x%02x</color>\n</resources>\n' % bg)
