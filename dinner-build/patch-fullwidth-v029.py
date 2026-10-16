from pathlib import Path
import re

p=Path('/tmp/dinner029')
js=p/'app/src/main/assets/app.js'
s=js.read_text(encoding='utf-8')
assert "const VERSION = '0.2.8';" in s, 'source version mismatch'
s=s.replace("const VERSION = '0.2.8';", "const VERSION = '0.2.9';", 1)
assert "const thumbs = new Map();" in s
s=s.replace("const thumbs = new Map();", "const thumbs = new Map();\n  const dayFullImageUrls = new Set();", 1)
old="$('dayContent').querySelectorAll('img[data-photo-id]').forEach(img=>bindThumbnail(img,img.dataset.photoId));"
assert s.count(old)==1, 'day image binding changed'
s=s.replace(old, "$('dayContent').querySelectorAll('img[data-photo-id]').forEach(img=>bindDayFullImage(img,img.dataset.photoId));",1)
injection="""  async function bindDayFullImage(img, itemId) {
    try {
      const row = await getImage(itemId);
      if (!row?.blob || !img.isConnected || !$('dayDialog').open || img.dataset.photoId !== itemId) return;
      const url = URL.createObjectURL(row.blob);
      dayFullImageUrls.add(url);
      img.src = url;
    } catch (error) { console.warn('画像読み込み失敗',error); }
  }
  $('dayDialog').addEventListener('close',()=>{
    for (const url of dayFullImageUrls) URL.revokeObjectURL(url);
    dayFullImageUrls.clear();
  });
"""
marker="  $('closeDayBtn').onclick=()=>$('dayDialog').close();"
assert marker in s
s=s.replace(marker, injection+marker,1)
js.write_text(s,encoding='utf-8')

css=p/'app/src/main/assets/style.css'
css.write_text(css.read_text(encoding='utf-8')+"""
#dayDialog {width:calc(100vw - 12px); max-width:none; max-height:92dvh; padding:10px 8px; box-sizing:border-box;}
#dayDialog .dialog-head {padding:2px 4px}
#dayContent {width:100%}
#dayContent .day-photo {display:flex; flex-direction:column; align-items:stretch; gap:9px; min-width:0; padding:6px 0 17px;}
#dayContent .day-photo img {display:block; width:100%; max-width:100%; height:auto; max-height:none; object-fit:contain; border-radius:8px;}
#dayContent .day-photo .details {width:100%; min-width:0; padding:0 4px}
""", encoding='utf-8')
html=p/'app/src/main/assets/index.html'
h=html.read_text(encoding='utf-8')
assert 'v0.2.8' in h
html.write_text(h.replace('v0.2.8','v0.2.9'),encoding='utf-8')
gradle=p/'app/build.gradle'
g=gradle.read_text(encoding='utf-8')
gradle.write_text(re.sub(r"versionName\s+'[^']+'", "versionName '0.2.9'", re.sub(r'versionCode\s+\d+', 'versionCode 12', g)), encoding='utf-8')
print('Patched dinner version 0.2.9 to full-width images')
