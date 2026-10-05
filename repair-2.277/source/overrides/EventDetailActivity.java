package com.konchan.chappyfx;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class EventDetailActivity extends Activity {
    private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(248,251,253), MUTED=Color.rgb(205,215,224), UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), WARN=Color.rgb(241,199,95), ACCENT=Color.rgb(104,170,255);
    private DemoStore store;
    private ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView statusText,typeText,headlineText,outlookText,dataText,watchText,scenarioText,sourceText;
    private Button refreshBtn,sourceBtn;
    private String source,title,category,direction,url,dateText; private int risk;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);store=new DemoStore(this);
        Intent i=getIntent();source=i.getStringExtra("source");title=i.getStringExtra("title");category=i.getStringExtra("category");direction=i.getStringExtra("direction");url=i.getStringExtra("url");dateText=i.getStringExtra("dateText");risk=i.getIntExtra("risk",0);
        buildUi();runAnalysis();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(16),dp(12),dp(28));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applyInsets(sv,root);

        TextView pageTitle=txt("イベント事前分析",20,TEXT,true);root.addView(pageTitle,lp(-1,-2,0,0,0,8));

        LinearLayout event=card();event.addView(txt(level(risk)+" "+risk+"%  "+safe(source),12,riskColor(risk),true));
        TextView ttl=txt(safe(title),17,TEXT,true);ttl.setLineSpacing(dp(2),1.05f);event.addView(ttl,lp(-1,-2,0,8,0,0));
        event.addView(txt(safe(dateText),11,MUTED,false),lp(-1,-2,0,6,0,0));
        event.addView(txt("分類: "+safe(category)+" / "+safe(direction),11,MUTED,false),lp(-1,-2,0,4,0,0));
        root.addView(event,lp(-1,-2,0,0,0,10));

        LinearLayout state=card();state.addView(txt("事前分析",14,TEXT,true));
        statusText=txt("分析中…",13,WARN,true);state.addView(statusText,lp(-1,-2,0,8,0,0));
        typeText=txt("",11,MUTED,true);state.addView(typeText,lp(-1,-2,0,5,0,0));
        headlineText=txt("",15,TEXT,true);state.addView(headlineText,lp(-1,-2,0,7,0,0));
        outlookText=txt("",12,TEXT,false);outlookText.setLineSpacing(dp(3),1.06f);state.addView(outlookText,lp(-1,-2,0,7,0,0));
        root.addView(state,lp(-1,-2,0,0,0,10));

        LinearLayout data=card();data.addView(txt("判断材料",14,TEXT,true));
        dataText=txt("取得中…",12,TEXT,false);dataText.setLineSpacing(dp(4),1.07f);data.addView(dataText,lp(-1,-2,0,8,0,0));
        root.addView(data,lp(-1,-2,0,0,0,10));

        LinearLayout watch=card();watch.addView(txt("発表前に見るポイント",14,TEXT,true));
        watchText=txt("",12,TEXT,false);watchText.setLineSpacing(dp(3),1.07f);watch.addView(watchText,lp(-1,-2,0,8,0,0));
        root.addView(watch,lp(-1,-2,0,0,0,10));

        LinearLayout scenario=card();scenario.addView(txt("ドル円シナリオ",14,TEXT,true));
        scenarioText=txt("",12,TEXT,false);scenarioText.setLineSpacing(dp(4),1.07f);scenario.addView(scenarioText,lp(-1,-2,0,8,0,0));
        root.addView(scenario,lp(-1,-2,0,0,0,10));

        LinearLayout src=card();src.addView(txt("分析情報",14,TEXT,true));
        sourceText=txt("",11,MUTED,false);sourceText.setLineSpacing(dp(3),1.06f);src.addView(sourceText,lp(-1,-2,0,8,0,0));
        refreshBtn=button("事前分析を再取得");src.addView(refreshBtn,lp(-1,dp(46),0,9,0,0));
        if(url!=null&&!url.trim().isEmpty()){
            sourceBtn=button("公式情報を開く");src.addView(sourceBtn,lp(-1,dp(46),0,7,0,0));
            sourceBtn.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception ignored){}});
        }
        refreshBtn.setOnClickListener(v->runAnalysis());
        root.addView(src,lp(-1,-2,0,0,0,10));

        root.addView(txt("※事前分析は将来の結果を保証するものではありません。市場コンセンサスが取得できないイベントは、公式データ・現在の金利・ドル円テクニカルを中心にシナリオ分析します。",10,MUTED,false));
    }

    private void runAnalysis(){
        refreshBtn.setEnabled(false);refreshBtn.setText("分析中…");statusText.setText("公式データと相場状況を分析中…");statusText.setTextColor(WARN);
        worker.execute(()->{
            MacroEventAnalysisClient.Result r=new MacroEventAnalysisClient().analyze(source,title,category,direction,store);
            runOnUiThread(()->render(r));
        });
    }

    private void render(MacroEventAnalysisClient.Result r){
        refreshBtn.setEnabled(true);refreshBtn.setText("事前分析を再取得");
        statusText.setText("分析完了  "+new SimpleDateFormat("M/d HH:mm:ss",Locale.JAPAN).format(new Date(r.checkedAt)));
        statusText.setTextColor(r.fetchSucceeded?UP:WARN);
        typeText.setText("イベント種別: "+safe(r.eventType)+" / 分析信頼度 "+r.confidence+"%");
        headlineText.setText(safe(r.headline));
        outlookText.setText(safe(r.outlook));
        dataText.setText(safe(r.dataSummary));
        watchText.setText(safe(r.watchPoints));
        scenarioText.setText("【上方向シナリオ】\n"+safe(r.upScenario)+"\n\n【下方向シナリオ】\n"+safe(r.downScenario));
        String extra="";
        if(r.eventType.contains("雇用")){
            int n=store.employmentResolvedCount();double mae=store.employmentMaeK(),cal=store.employmentCalibrationK();
            extra="\n\n予想検証: 解決済み "+n+"件";
            if(!Double.isNaN(mae))extra+=" / 平均誤差 "+String.format(Locale.JAPAN,"%.0f千人",mae);
            if(n>=3)extra+=" / 次回補正 "+String.format(Locale.JAPAN,"%+.0f千人",cal);
        }
        sourceText.setText(safe(r.sourceNote)+extra);
    }

    private String level(int v){return v>=85?"最重要":v>=65?"高":v>=45?"中":"低";}
    private int riskColor(int v){return v>=85?DOWN:v>=65?Color.rgb(255,159,67):v>=45?WARN:MUTED;}
    private String safe(String s){return s==null||s.trim().isEmpty()?"--":s.trim();}

    private void applyInsets(View src,View target){
        final int L=dp(12),T=dp(16),R=dp(12),B=dp(28);target.setPadding(L,T,R,B);
        src.setOnApplyWindowInsetsListener((v,i)->{int l=0,t=0,r=0,b=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets z=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());l=z.left;t=z.top;r=z.right;b=z.bottom;}else{l=i.getSystemWindowInsetLeft();t=i.getSystemWindowInsetTop();r=i.getSystemWindowInsetRight();b=i.getSystemWindowInsetBottom();}target.setPadding(L+l,T+t,R+r,B+b);return i;});src.requestApplyInsets();
    }
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(11);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
