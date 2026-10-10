# Builds the launcher icon layers from the logo on a black background:
#   python3 tools/make_icon.py logo-dark.png patcher/src/main/resources/nx/icon app/src/main/res
import sys, os
from PIL import Image
import numpy as np
src, out_patcher, out_app = sys.argv[1], sys.argv[2], sys.argv[3]
im = np.asarray(Image.open(src).convert("RGB")).astype(float)
m = im.max(axis=2)
alpha = np.clip((m - 8) / (150 - 8), 0, 1)
rgb = np.where(alpha[..., None] > 0, im / np.maximum(alpha[..., None], 1e-6), 0)
rgb = np.clip(rgb, 0, 255)
rgba = np.dstack([rgb, alpha * 255]).astype(np.uint8)
logo = Image.fromarray(rgba, "RGBA")
logo = logo.crop(logo.getbbox())
master = 1728  # 108dp * 16
canvas = Image.new("RGBA", (master, master), (0, 0, 0, 0))
w = int(master * 0.58)
h = int(logo.height * w / logo.width)
canvas.alpha_composite(logo.resize((w, h), Image.LANCZOS), ((master - w) // 2, (master - h) // 2))
sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
os.makedirs(out_patcher, exist_ok=True)
for d, s in sizes.items():
    fg = canvas.resize((s, s), Image.LANCZOS)
    fg.save(os.path.join(out_patcher, f"fg_{d}.png"), optimize=True)
    Image.new("RGBA", (s, s), (242, 242, 242, 255)).save(os.path.join(out_patcher, f"bg_{d}.png"), optimize=True)
    app_dir = os.path.join(out_app, f"mipmap-{d}")
    os.makedirs(app_dir, exist_ok=True)
    fg.save(os.path.join(app_dir, "ic_launcher_foreground.png"), optimize=True)
# Preview: light and dark circle masks.
prev = Image.new("RGB", (900, 450), (255, 255, 255))
for i, bg in enumerate([(242, 242, 242), (0, 0, 0)]):
    tile = Image.new("RGBA", (432, 432), bg + (255,))
    tile.alpha_composite(canvas.resize((432, 432), Image.LANCZOS))
    mask = Image.new("L", (432, 432), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(mask).ellipse((54, 54, 378, 378), fill=255)
    prev.paste(tile.convert("RGB").crop((54, 54, 378, 378)), (i * 450 + 63, 63), mask.crop((54, 54, 378, 378)))
prev.save(os.path.join(os.path.dirname(out_patcher.rstrip("/")), "preview.png"))
print("ok")
