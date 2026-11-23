"""Play icon (512x512) and feature graphic (1024x500), drawn from the launcher icon's own vector paths
(app/android/src/main/res/drawable/ic_launcher_foreground.xml: an open ring and a dot, background #C2405A from
values/colors.xml). Run: python store/make_graphics.py"""
import re
from pathlib import Path
from playwright.sync_api import sync_playwright

HERE = Path(__file__).parent
res = HERE.parent / "app/android/src/main/res"
xml = (res / "drawable/ic_launcher_foreground.xml").read_text(encoding="utf-8")
ring, dot = re.findall(r'pathData="([^"]+)"', xml)
BG = re.search(r'name="ic_launcher_background">([^<]+)<', (res / "values/colors.xml").read_text(encoding="utf-8")).group(1)
# the 108-unit adaptive canvas; the visible icon is its middle 72 units (18..90)
icon = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="{{s}}" height="{{s}}"><rect x="0" y="0" width="108" height="108" fill="{BG}"/>'
        f'<path fill="none" stroke="#FFFFFF" stroke-width="6" stroke-linecap="round" d="{ring}"/><path fill="#FFFFFF" d="{dot}"/></svg>')

page_icon = f'<html><body style="margin:0">{icon.format(s=512)}</body></html>'
page_feature = f'''<html><body style="margin:0"><div id="f" style="width:1024px;height:500px;background:{BG};display:flex;align-items:center;gap:56px;padding-left:96px;box-sizing:border-box;font-family:'Segoe UI',Roboto,sans-serif;color:#fff">
<div style="border-radius:48px;overflow:hidden;width:260px;height:260px">{icon.format(s=260)}</div>
<div><div style="font-size:96px;font-weight:600;line-height:1">Ciclo</div>
<div style="font-size:32px;margin-top:20px;opacity:.92;line-height:1.35">Period, fertile days and moods<br>No account, nothing to sign up for</div></div></div></body></html>'''

with sync_playwright() as p:
    b = p.chromium.launch(headless=True, channel="msedge")
    pg = b.new_page(viewport={"width": 512, "height": 512})
    pg.set_content(page_icon)
    pg.screenshot(path=str(HERE / "icon-512.png"), clip={"x": 0, "y": 0, "width": 512, "height": 512})
    pg = b.new_page(viewport={"width": 1024, "height": 500})
    pg.set_content(page_feature)
    pg.screenshot(path=str(HERE / "feature-1024x500.png"), clip={"x": 0, "y": 0, "width": 1024, "height": 500})
    b.close()
print("ok")
