package com.konchan.chappyfx;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.app.DownloadManager;
import android.database.Cursor;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

// GitHub direct updater: public distribution repo contains only update manifest and signed APK
public class SettingsActivity extends Activity {
    private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(238,244,249), MUTED=Color.rgb(145,164,183), DOWN=Color.rgb(255,102,118), ACCENT=Color.rgb(104,170,255);
    private DemoStore store;
    private TextView updateStatus;
    private Button updateBtn;
    private boolean updateBusy=false;
    private boolean pendingInstallPermission=false;
    private String pendingApkUrl;
    private String pendingSha256;
    private String pendingVersion;
    private static final String UPDATE_MANIFEST_URL="https://raw.githubusercontent.com/osakakon-ai/fx-souba-yosou-update/main/latest.json";

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        store=new DemoStore(this);
        buildUi();
    }

    @Override protected void onResume(){
        super.onResume();
        if(pendingInstallPermission && canInstallPackages()){
            pendingInstallPermission=false;
            if(pendingApkUrl!=null) downloadAndInstall(pendingApkUrl,pendingSha256,pendingVersion);
        }
    }

    private void buildUi(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(16),dp(12),dp(28));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applySystemBarInsets(sv,root,12,16,12,28);

        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("← 戻る");header.addView(back,new LinearLayout.LayoutParams(dp(82),dp(42)));
        TextView title=txt("設定",24,TEXT,true);header.addView(title,lp(0,-2,10,0,0,0,1));
        root.addView(header,lp(-1,-2,0,0,0,14,0));
        back.setOnClickListener(v->finish());

        LinearLayout battery=card();
        battery.addView(txt("バッテリー / バックグラウンド監視",14,TEXT,true));
        battery.addView(txt("Androidの省電力機能で監視が止まりにくいよう、FX相場予想のバッテリー使用を『制限なし』に設定する場合に使います。",11,MUTED,false),lp(-1,-2,0,7,0,0,0));
        Button batteryBtn=button("バッテリー最適化設定を開く");battery.addView(batteryBtn,lp(-1,dp(48),0,10,0,0,0));
        batteryBtn.setOnClickListener(v->openBatterySettings());
        root.addView(battery,lp(-1,-2,0,0,0,12,0));

        LinearLayout update=card();
        update.addView(txt("アプリの更新",14,TEXT,true));
        updateStatus=txt("現在のバージョン: Ver "+currentVersion()+"\nボタンを押すと最新版を確認し、更新があればAPKを自動取得します。",11,MUTED,false);
        update.addView(updateStatus,lp(-1,-2,0,7,0,0,0));
        updateBtn=button("アップデートを確認・実行");update.addView(updateBtn,lp(-1,dp(48),0,10,0,0,0));
        updateBtn.setOnClickListener(v->checkForUpdate());
        root.addView(update,lp(-1,-2,0,0,0,12,0));


        LinearLayout demo=card();
        demo.addView(txt("デモ口座",14,TEXT,true));
        demo.addView(txt("残高・勝敗・取引履歴を消して、デモ資金10万円から再スタートします。誤操作防止のため2段階で確認します。",11,MUTED,false),lp(-1,-2,0,7,0,0,0));
        Button resetBtn=button("デモ口座を10万円にリセット");resetBtn.setTextColor(DOWN);demo.addView(resetBtn,lp(-1,dp(48),0,10,0,0,0));
        resetBtn.setOnClickListener(v->confirmReset());
        root.addView(demo,lp(-1,-2,0,0,0,12,0));

        LinearLayout info=card();
        info.addView(txt("このアプリについて",14,TEXT,true));
        info.addView(txt("USD/JPY専用のデモトレードです。実際のFX注文は行いません。",11,MUTED,false),lp(-1,-2,0,7,0,0,0));
        root.addView(info,lp(-1,-2,0,0,0,0,0));
    }

    private String currentVersion(){
        try{
            String v=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
            return v==null?"--":v;
        }catch(Exception e){
            return "--";
        }
    }

    private long currentVersionCode(){
        try{
            android.content.pm.PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);
            return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;
        }catch(Exception e){
            return 0;
        }
    }

    private boolean canInstallPackages(){
        return Build.VERSION.SDK_INT<26 || getPackageManager().canRequestPackageInstalls();
    }

    private void checkForUpdate(){
        if(updateBusy)return;
        updateBusy=true;
        updateBtn.setEnabled(false);
        updateStatus.setText("最新版を確認中…");
        new Thread(()->{
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(UPDATE_MANIFEST_URL+"?t="+System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept","application/json");
                conn.setRequestProperty("Cache-Control","no-cache, no-store, max-age=0");
                conn.setRequestProperty("Pragma","no-cache");
                conn.setIfModifiedSince(0);
                int code=conn.getResponseCode();
                if(code!=200)throw new IOException("更新情報 HTTP "+code);
                String json=readText(conn.getInputStream());
                JSONObject o=new JSONObject(json);
                long latestCode=o.getLong("versionCode");
                String latestName=o.getString("versionName");
                String apkUrl=o.getString("apkUrl");
                String sha=o.optString("sha256","");
                if(latestCode<=currentVersionCode()){
                    runOnUiThread(()->finishUpdateUi("現在の Ver "+currentVersion()+" が最新版です。"));
                    return;
                }
                pendingApkUrl=apkUrl;
                pendingSha256=sha;
                pendingVersion=latestName;
                if(!canInstallPackages()){
                    pendingInstallPermission=true;
                    runOnUiThread(()->{
                        finishUpdateUi("Ver "+latestName+" が利用できます。初回だけ「この提供元のアプリを許可」をONにしてください。");
                        new AlertDialog.Builder(this)
                            .setTitle("更新のインストール許可")
                            .setMessage("アップデートAPKをインストールするため、初回だけFX相場予想に「不明なアプリのインストール」を許可する必要があります。設定後、この画面へ戻ると更新を続けます。")
                            .setNegativeButton("キャンセル",(d,w)->pendingInstallPermission=false)
                            .setPositiveButton("設定を開く",(d,w)->{
                                Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));
                                startActivity(i);
                            }).show();
                    });
                    return;
                }
                String freshApkUrl=cacheBustedUrl(apkUrl,"v="+latestCode+"&t="+System.currentTimeMillis());
                pendingApkUrl=freshApkUrl;
                runOnUiThread(()->{
                    updateBusy=false;
                    updateBtn.setEnabled(true);
                    downloadAndInstall(freshApkUrl,sha,latestName);
                });
            }catch(Exception e){
                String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                runOnUiThread(()->finishUpdateUi("更新確認に失敗しました: "+msg));
            }finally{
                if(conn!=null)conn.disconnect();
            }
        }).start();
    }

    private void downloadAndInstall(String apkUrl,String expectedSha,String versionName){
        if(updateBusy)return;
        updateBusy=true;
        updateBtn.setEnabled(false);
        updateStatus.setText("Ver "+versionName+" をダウンロード中…");
        try{
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(dir==null)throw new IOException("ダウンロード保存先を利用できません");
            File old=new File(dir,"FX-Souba-Yosou-update.apk");
            if(old.exists())old.delete();

            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(apkUrl));
            req.addRequestHeader("Cache-Control","no-cache");
            req.addRequestHeader("Pragma","no-cache");
            req.setTitle("FX相場予想 Ver "+versionName);
            req.setDescription("アップデートをダウンロードしています");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"FX-Souba-Yosou-update.apk");

            DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            long id=dm.enqueue(req);
            new Thread(()->waitForDownload(dm,id,expectedSha,versionName)).start();
        }catch(Exception e){
            finishUpdateUi("ダウンロード開始に失敗しました: "+e.getMessage());
        }
    }

    private String cacheBustedUrl(String url,String suffix){
        if(url==null||url.trim().isEmpty())return url;
        return url+(url.contains("?")?"&":"?")+suffix;
    }

    private void waitForDownload(DownloadManager dm,long id,String expectedSha,String versionName){
        try{
            for(int i=0;i<240;i++){
                DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
                try(Cursor cur=dm.query(q)){
                    if(cur!=null && cur.moveToFirst()){
                        int status=cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if(status==DownloadManager.STATUS_SUCCESSFUL){
                            Uri uri=dm.getUriForDownloadedFile(id);
                            if(uri==null)throw new IOException("ダウンロード済みAPKを開けません");
                            if(expectedSha!=null && !expectedSha.isEmpty()){
                                String actual=sha256(uri);
                                if(!expectedSha.equalsIgnoreCase(actual))throw new SecurityException("APKのSHA-256が一致しません");
                            }
                            runOnUiThread(()->{
                                updateBusy=false;
                                updateBtn.setEnabled(true);
                                updateStatus.setText("Ver "+versionName+" のダウンロード完了。Androidの更新確認を開きます。");
                                openInstaller(uri);
                            });
                            return;
                        }
                        if(status==DownloadManager.STATUS_FAILED){
                            int reason=cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                            throw new IOException("ダウンロード失敗 code="+reason);
                        }
                    }
                }
                Thread.sleep(500);
            }
            throw new IOException("ダウンロードがタイムアウトしました");
        }catch(Exception e){
            String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            runOnUiThread(()->finishUpdateUi("更新ダウンロードに失敗しました: "+msg));
        }
    }

    private void openInstaller(Uri uri){
        try{
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri,"application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }catch(Exception e){
            finishUpdateUi("Androidの更新画面を開けませんでした: "+e.getMessage());
        }
    }

    private String sha256(Uri uri)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("APKを読み込めません");
            byte[] buf=new byte[8192];
            int n;
            while((n=in.read(buf))>0)md.update(buf,0,n);
        }
        StringBuilder b=new StringBuilder();
        for(byte x:md.digest())b.append(String.format(Locale.US,"%02x",x));
        return b.toString();
    }

    private String readText(InputStream in)throws IOException{
        try(InputStream src=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];
            int n;
            while((n=src.read(buf))>0)out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void finishUpdateUi(String message){
        updateBusy=false;
        if(updateBtn!=null)updateBtn.setEnabled(true);
        if(updateStatus!=null)updateStatus.setText(message);
    }

    private void openBatterySettings(){
        try{
            Intent i=new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
        }catch(Exception e){
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        }
    }

    private void confirmReset(){
        new AlertDialog.Builder(this)
            .setTitle("デモ口座をリセットしますか？")
            .setMessage("このボタンを押しただけではリセットされません。\n\n残高・勝敗・取引履歴・チャッピーの検証データを消して、デモ資金10万円からやり直します。")
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("次の確認へ",(d,w)->showFinalResetConfirmation())
            .show();
    }

    private void showFinalResetConfirmation(){
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("最終確認")
            .setMessage("本当にデモ口座をリセットしますか？\n\nこの操作は元に戻せません。\n間違えて開いた場合は「キャンセル」を押してください。")
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("本当にリセットする",null)
            .create();
        dialog.setOnShowListener(x->{
            android.widget.Button reset=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            reset.setTextColor(DOWN);
            reset.setEnabled(false);
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                if(dialog.isShowing())reset.setEnabled(true);
            },1200);
            reset.setOnClickListener(v->{
                boolean running=store.isRunning();
                store.reset();
                if(running)store.setRunning(true);
                Toast.makeText(this,"デモ口座を10万円にリセットしました",Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }


    private void applySystemBarInsets(View insetSource,View target,int leftDp,int topDp,int rightDp,int bottomDp){
        final int baseLeft=dp(leftDp),baseTop=dp(topDp),baseRight=dp(rightDp),baseBottom=dp(bottomDp);
        target.setPadding(baseLeft,baseTop,baseRight,baseBottom);
        insetSource.setOnApplyWindowInsetsListener((v,insets)->{
            int left=0,top=0,right=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;
            }else{
                left=insets.getSystemWindowInsetLeft();
                top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();
                bottom=insets.getSystemWindowInsetBottom();
            }
            target.setPadding(baseLeft+left,baseTop+top,baseRight+right,baseBottom+bottom);
            return insets;
        });
        insetSource.requestApplyInsets();
    }

    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(11);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b,int weight){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h,weight);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
