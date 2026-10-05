from pathlib import Path
import os, re
root=Path(os.environ.get('FX_WORK','/tmp/fx2276'))

# Manifest: remove persistent-background capabilities and boot restart.
p=root/'AndroidManifest.xml'
s=p.read_text()
s=re.sub(r'\s*<uses-permission android:name="android\.permission\.FOREGROUND_SERVICE"/>','',s, count=1)
s=re.sub(r'\s*<uses-permission android:name="android\.permission\.FOREGROUND_SERVICE_SPECIAL_USE"/>','',s, count=1)
s=re.sub(r'\s*<uses-permission android:name="android\.permission\.RECEIVE_BOOT_COMPLETED"/>','',s, count=1)
s=re.sub(r'\s*<receiver[^>]*android:name="com\.konchan\.chappyfx\.MonitoringBootReceiver"[^>]*>.*?</receiver>','',s, count=1, flags=re.S)
s=re.sub(r'<service android:exported="false" android:foregroundServiceType="specialUse" android:name="com\.konchan\.chappyfx\.MonitoringService">\s*<property[^>]*/>\s*</service>',
         '<service android:exported="false" android:name="com.konchan.chappyfx.MonitoringService"/>',s,count=1,flags=re.S)
p.write_text(s)

# MainActivity: regular service, honest labels.
p=root/'smali/com/konchan/chappyfx/MainActivity.smali'
s=p.read_text()
s=s.replace('->startForegroundService(Landroid/content/Intent;)Landroid/content/ComponentName;', '->startService(Landroid/content/Intent;)Landroid/content/ComponentName;', 1)
s=s.replace('バックグラウンド監視：ON','表示中のみ通信')
s=s.replace('アプリを閉じても監視を継続し、端末再起動後も自動で監視を再開します。Android設定でアプリを強制停止した場合だけ、再開には一度アプリを起動してください。',
            '通信はアプリを表示している間だけ行います。画面OFF・ホームへ戻る・アプリ終了中は常時接続しません。')
p.write_text(s)

# MonitoringService: do not enter foreground / do not post persistent notification / no sticky restart.
p=root/'smali/com/konchan/chappyfx/MonitoringService.smali'
s=p.read_text()
s=s.replace('    invoke-direct {p0}, Lcom/konchan/chappyfx/MonitoringService;->ensureForeground()V\n\n','',1)
a=s.index('.method private updateOngoing()V')
b=s.index('.end method',a)+len('.end method')
s=s[:a]+'.method private updateOngoing()V\n    .locals 0\n\n    return-void\n.end method'+s[b:]
a=s.index('.method public onStartCommand(Landroid/content/Intent;II)I')
b=s.index('.end method',a)+len('.end method')
block=s[a:b]
block=block.replace('    :cond_3\n    return p3', '    :cond_3\n    const/4 p3, 0x2\n\n    return p3')
s=s[:a]+block+s[b:]
p.write_text(s)

manifest=(root/'AndroidManifest.xml').read_text()
acts=re.findall(r'<activity[^>]*android:name="([^"]+)"',manifest)

def activity_smali(name):
    if name.startswith('.'):
        name='com.konchan.chappyfx'+name
    return root/'smali'/Path(name.replace('.','/')+'.smali')

def bump_locals(method, add=2):
    m=re.search(r'(?m)^    \.locals (\d+)$',method)
    if not m:
        raise RuntimeError('no locals')
    n=int(m.group(1)); new=n+add
    method=method[:m.start(1)]+str(new)+method[m.end(1):]
    return method,n,n+1

def start_code(v0,v1):
    return f'''\n    new-instance v{v0}, Landroid/content/Intent;\n\n    const-class v{v1}, Lcom/konchan/chappyfx/MonitoringService;\n\n    invoke-direct {{v{v0}, p0, v{v1}}}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V\n\n    const-string v{v1}, "com.konchan.chappyfx.START"\n\n    invoke-virtual {{v{v0}, v{v1}}}, Landroid/content/Intent;->setAction(Ljava/lang/String;)Landroid/content/Intent;\n\n    invoke-virtual {{p0, v{v0}}}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;\n'''

def stop_code(v0,v1):
    return f'''\n    new-instance v{v0}, Landroid/content/Intent;\n\n    const-class v{v1}, Lcom/konchan/chappyfx/MonitoringService;\n\n    invoke-direct {{v{v0}, p0, v{v1}}}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V\n\n    invoke-virtual {{p0, v{v0}}}, Landroid/content/Context;->stopService(Landroid/content/Intent;)Z\n'''

patched=[]
for name in acts:
    fp=activity_smali(name)
    if not fp.exists():
        print('WARN missing activity smali',name,fp); continue
    txt=fp.read_text()
    cls=name if not name.startswith('.') else 'com.konchan.chappyfx'+name
    key='.method protected onResume()V'
    if key in txt:
        a=txt.index(key); b=txt.index('.end method',a)+len('.end method'); method=txt[a:b]
        if 'MonitoringService;-><init>' not in method:
            method,v0,v1=bump_locals(method)
            needle='->onResume()V\n'
            pos=method.index(needle)+len(needle)
            method=method[:pos]+start_code(v0,v1)+method[pos:]
            txt=txt[:a]+method+txt[b:]
    else:
        method=f'''\n.method protected onResume()V\n    .locals 2\n\n    invoke-super {{p0}}, Landroid/app/Activity;->onResume()V\n{start_code(0,1)}\n    return-void\n.end method\n'''
        txt += method
    key='.method protected onPause()V'
    if key in txt:
        a=txt.index(key); b=txt.index('.end method',a)+len('.end method'); method=txt[a:b]
        if '->stopService(Landroid/content/Intent;)Z' not in method:
            method,v0,v1=bump_locals(method)
            m=re.search(r'(?m)^    \.locals \d+\n',method)
            pos=m.end()
            method=method[:pos]+stop_code(v0,v1)+method[pos:]
            txt=txt[:a]+method+txt[b:]
    else:
        method=f'''\n.method protected onPause()V\n    .locals 2\n{stop_code(0,1)}\n    invoke-super {{p0}}, Landroid/app/Activity;->onPause()V\n\n    return-void\n.end method\n'''
        txt += method
    fp.write_text(txt)
    patched.append(cls)

p=root/'apktool.yml'
s=p.read_text().replace('versionCode: 200275','versionCode: 200276').replace('versionName: 2.275','versionName: 2.276')
p.write_text(s)

print('patched activities:',len(patched))
for x in patched: print(' ',x)
