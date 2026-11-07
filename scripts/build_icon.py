"""Generate multi-resolution Windows ICO from the approved transparent logo."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
source = ROOT / "assets" / "ir_remote_studio.png"
destination = ROOT / "assets" / "ir_remote_studio.ico"
image = Image.open(source).convert("RGBA")
image = image.resize((256, 256), Image.Resampling.LANCZOS)
image.save(destination, format="ICO",
           sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                  (64, 64), (128, 128), (256, 256)])
print(f"Created {destination}")
