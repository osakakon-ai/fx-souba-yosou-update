package com.konchan.chappyfx;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

// System-bar inset handling for Android 15 edge-to-edge.
public class MainActivity extends Activity {
    private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(248,251,253), MUTED=Color.rgb(205,215,224), UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), WARN=Color.rgb(241,199,95), ACCENT=Color.rgb(104,170,255);
    private DemoStore store; private final Handler h=new Handler(Looper.getMainLooper());
    private TextView status,lastError,mainDir,readiness,agreement,indicatorAgreement,rapidMoveRisk,rapidMoveState,volatilityState,shortTrend,midTrend,longTrend,chartForecast,chartAxis,chartTarget,bidQuote,askQuote,quoteMeta,analysisProgress;
    private Button startBtn,stopBtn,refreshBtn,settingsBtn; private CandlestickView candleChart; private IndicatorChartView rciChart,macdChart;
    private LinearLayout pbContainer;
    private final Map<String,TextView> tfViews=new LinkedHashMap<>(); private final Map<String,LinearLayout> tfCells=new LinkedHashMap<>();
    private static final String[] DISPLAY_TF=DemoStore.DISPLAY_TF;
    private String selectedTf="M5";
    private final ExecutorService chartExecutor=Executors.newFixedThreadPool(2); private final Set<String> fetchingTf=Collections.synchronizedSet(new HashSet<>());
    private volatile double liveMid=Double.NaN; private volatile String liveMarketState=""; private volatile long liveQuoteAge=-1;
    private GestureDetector swipeDetector; private boolean switchingPage=false;
    private boolean immediateAnalysisPending=false; private long immediateAnalysisPressedAt=0;
    private final Runnable tick=new Runnable(){@Override public void run(){refreshUi();h.postDelayed(this,1000);}};

    @Override protected void onCreate(Bundle b){super.onCreate(b);store=new DemoStore(this);requestNotifications();buildUi();setupSwipe();startMonitor(false);}
    @Override protected void onResume(){super.onResume();h.post(tick);if(store.candles("MN").length()<48)fetchTimeframeNow("MN");}
    @Override protected void onPause(){h.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){chartExecutor.shutdownNow();super.onDestroy();}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(14),dp(12),dp(28));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applySystemBarInsets(sv,root,12,14,12,28);

        root.addView(buildPageNav(0),lp(-1,-2,0,0,0,6));
        root.addView(pageTitle("ドル円 相場分析"),lp(-1,dp(38),0,0,0,8));

        LinearLayout topbar=new LinearLayout(this);topbar.setOrientation(LinearLayout.HORIZONTAL);topbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout pair=new LinearLayout(this);pair.setOrientation(LinearLayout.VERTICAL);
        pair.addView(txt("USD / JPY",15,TEXT,true));pair.addView(txt("ドル円",11,MUTED,false),lp(-1,-2,0,1,0,0));
        topbar.addView(pair,new LinearLayout.LayoutParams(0,-2,1));
        settingsBtn=button("設定");topbar.addView(settingsBtn,new LinearLayout.LayoutParams(dp(66),dp(42)));
        root.addView(topbar,lp(-1,-2,0,0,0,6));

        LinearLayout quoteRow=new LinearLayout(this);quoteRow.setOrientation(LinearLayout.HORIZONTAL);quoteRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout bidBox=new LinearLayout(this);bidBox.setOrientation(LinearLayout.VERTICAL);
        TextView bidLabel=txt("Bid",12,ACCENT,true);bidBox.addView(bidLabel);
        bidQuote=txt("---.---",34,TEXT,true);bidQuote.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD));bidBox.addView(bidQuote);
        LinearLayout askBox=new LinearLayout(this);askBox.setOrientation(LinearLayout.VERTICAL);
        TextView askLabel=txt("Ask",12,ACCENT,true);askBox.addView(askLabel);
        askQuote=txt("---.---",34,TEXT,true);askQuote.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD));askBox.addView(askQuote);
        quoteRow.addView(bidBox,new LinearLayout.LayoutParams(0,-2,1));quoteRow.addView(space(12));quoteRow.addView(askBox,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(quoteRow,lp(-1,-2,0,2,0,0));
        quoteMeta=txt("スプレッド 0.2銭・5分足・更新待ち",11,MUTED,false);root.addView(quoteMeta,lp(-1,-2,0,2,0,5));

        LinearLayout tfCard=card();tfCard.setPadding(dp(8),dp(7),dp(8),dp(7));tfCard.addView(txt("時間足切替",14,TEXT,true));
        GridLayout grid=new GridLayout(this);grid.setColumnCount(5);grid.setRowCount(2);
        String[] tfs=DISPLAY_TF;for(int i=0;i<tfs.length;i++){String k=tfs[i];LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);cell.setPadding(dp(3),dp(2),dp(3),dp(2));cell.setBackground(makeTfBg(k.equals(selectedTf)));cell.setClickable(true);cell.setFocusable(true);TextView l=txt(tfCellLabel(k),9,MUTED,false);TextView v=txt("--",12,TEXT,true);l.setGravity(Gravity.CENTER);v.setGravity(Gravity.CENTER);cell.addView(l);cell.addView(v,lp(-1,-2,0,1,0,0));tfViews.put(k,v);tfCells.put(k,cell);cell.setOnClickListener(vw->selectTimeframe(k));GridLayout.LayoutParams gp=new GridLayout.LayoutParams(GridLayout.spec(i/5,1,1f),GridLayout.spec(i%5,1,1f));gp.width=0;gp.height=dp(44);gp.setMargins(dp(2),dp(2),dp(2),dp(2));grid.addView(cell,gp);}
        tfCard.addView(grid,lp(-1,-2,0,4,0,0));root.addView(tfCard,lp(-1,-2,0,0,0,6));

        candleChart=new CandlestickView(this);root.addView(candleChart,lp(-1,dp(252),0,0,0,6));
        rciChart=new IndicatorChartView(this);rciChart.setMode(IndicatorChartView.MODE_RCI);root.addView(rciChart,lp(-1,dp(142),0,0,0,4));
        macdChart=new IndicatorChartView(this);macdChart.setMode(IndicatorChartView.MODE_MACD);root.addView(macdChart,lp(-1,dp(142),0,0,0,8));

        LinearLayout pbCard=card();pbCard.addView(txt("ピークボトム一覧",16,TEXT,true));
        pbContainer=new LinearLayout(this);pbContainer.setOrientation(LinearLayout.VERTICAL);
        pbCard.addView(pbContainer,lp(-1,-2,0,6,0,0));root.addView(pbCard,lp(-1,-2,0,0,0,12));

        LinearLayout summary=card();summary.addView(txt("チャート分析 / 全時間足",15,TEXT,true));
        LinearLayout sr=new LinearLayout(this);sr.setOrientation(LinearLayout.HORIZONTAL);mainDir=metric(sr,"総合方向","→ 中立");readiness=metric(sr,"準備度","0%");agreement=metric(sr,"時間足一致","0%");summary.addView(sr,lp(-1,-2,0,7,0,0));
        LinearLayout tr=new LinearLayout(this);tr.setOrientation(LinearLayout.HORIZONTAL);indicatorAgreement=metric(tr,"指標一致","0%");rapidMoveRisk=metric(tr,"急変警戒度","0%");summary.addView(tr,lp(-1,-2,0,6,0,0));
        LinearLayout tr2=new LinearLayout(this);tr2.setOrientation(LinearLayout.HORIZONTAL);rapidMoveState=metric(tr2,"急変方向","未検出");volatilityState=metric(tr2,"ボラ判定","通常ボラ");summary.addView(tr2,lp(-1,-2,0,6,0,0));
        LinearLayout dirRow=new LinearLayout(this);dirRow.setOrientation(LinearLayout.HORIZONTAL);shortTrend=metric(dirRow,"短期 M5〜M30","→");midTrend=metric(dirRow,"中期 H1〜H4","→");longTrend=metric(dirRow,"長期 H8〜月","→");summary.addView(dirRow,lp(-1,-2,0,6,0,0));
        LinearLayout planRow=new LinearLayout(this);planRow.setOrientation(LinearLayout.HORIZONTAL);chartForecast=metric(planRow,"チャート予想","→ 待機");chartAxis=metric(planRow,"主軸","--");summary.addView(planRow,lp(-1,-2,0,6,0,0));
        chartTarget=txt("利確候補：方向一致待ち",12,MUTED,true);chartTarget.setGravity(Gravity.CENTER);summary.addView(chartTarget,lp(-1,-2,0,7,0,0));
        root.addView(summary,lp(-1,-2,0,0,0,10));

        LinearLayout serviceCard=card();
        serviceCard.addView(txt("監視・操作",15,TEXT,true));
        status=txt("--",13,WARN,true);serviceCard.addView(status,lp(-1,-2,0,7,0,0));
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setGravity(Gravity.CENTER);
        startBtn=button("監視を再起動");stopBtn=button("バックグラウンド監視：ON");stopBtn.setEnabled(false);refreshBtn=button("今すぐ解析");
        controls.addView(startBtn,new LinearLayout.LayoutParams(0,dp(46),1));controls.addView(space(6));controls.addView(stopBtn,new LinearLayout.LayoutParams(0,dp(46),1));controls.addView(space(6));controls.addView(refreshBtn,new LinearLayout.LayoutParams(0,dp(46),1));
        serviceCard.addView(controls,lp(-1,-2,0,9,0,0));
        analysisProgress=txt("今すぐ解析を押すと、受付と反映完了をここに表示します。",11,MUTED,false);analysisProgress.setLineSpacing(dp(2),1.04f);serviceCard.addView(analysisProgress,lp(-1,-2,0,8,0,0));
        root.addView(serviceCard,lp(-1,-2,0,0,0,10));

        lastError=txt("",11,DOWN,false);root.addView(lastError,lp(-1,-2,0,2,0,0));
        TextView note=txt("実売買は行いません。アプリを閉じても監視を継続し、端末再起動後も自動で監視を再開します。Android設定でアプリを強制停止した場合だけ、再開には一度アプリを起動してください。",11,MUTED,false);root.addView(note);

        startBtn.setOnClickListener(v->startMonitor(false));refreshBtn.setOnClickListener(v->startImmediateAnalysis());
        settingsBtn.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        selectTimeframe(selectedTf);
    }

    private TextView pageTitle(String s){
        TextView t=txt(s,20,TEXT,true);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;
    }

    private LinearLayout buildPageNav(int active){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels={"相場","デモ","成績","検証","情報"};
        Class<?>[] pages={MainActivity.class,DemoTradeActivity.class,DemoTradeActivity.TradeHistoryActivity.class,DemoTradeActivity.ResearchLabActivity.class,ImportantInfoActivity.class};
        for(int i=0;i<labels.length;i++){
            final Class<?> target=pages[i];Button b=navButton(labels[i],i==active);
            if(i!=active)b.setOnClickListener(v->openPage(target));
            row.addView(b,new LinearLayout.LayoutParams(0,dp(34),1));
            if(i<labels.length-1)row.addView(space(2));
        }
        return row;
    }
    private Button navButton(String s,boolean active){
        Button b=new Button(this);b.setText(s);b.setTextSize(10);b.setTextColor(active?TEXT:MUTED);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(active?Color.rgb(28,45,62):PANEL2);g.setCornerRadius(dp(8));g.setStroke(dp(active?2:1),active?ACCENT:Color.rgb(43,58,73));b.setBackground(g);
        return b;
    }

    private void setupSwipe(){
        swipeDetector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onFling(MotionEvent e1,MotionEvent e2,float velocityX,float velocityY){
                if(e1==null||e2==null||switchingPage)return false;
                float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();
                if(Math.abs(dx)<dp(80)||Math.abs(dx)<Math.abs(dy)*1.25f)return false;
                if(dx<0&&velocityX<-350){openPage(DemoTradeActivity.class);return true;}
                if(dx>0&&velocityX>350){openPage(ImportantInfoActivity.class);return true;}
                return false;
            }
        });
    }
    @Override public boolean dispatchTouchEvent(MotionEvent ev){
        if(swipeDetector!=null)swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void openPage(Class<?> cls){
        if(switchingPage)return;
        switchingPage=true;
        Intent i=new Intent(this,cls);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
        overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);
        h.postDelayed(()->switchingPage=false,450);
    }

    private void startMonitor(boolean now){Intent i=new Intent(this,MonitoringService.class);i.setAction(now&&store.isRunning()?MonitoringService.ACTION_RUN_NOW:MonitoringService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void startImmediateAnalysis(){
        if(immediateAnalysisPending)return;
        immediateAnalysisPending=true;immediateAnalysisPressedAt=System.currentTimeMillis();
        refreshBtn.setEnabled(false);refreshBtn.setText("解析中…");
        analysisProgress.setText("① ボタンを押しました  "+clock(immediateAnalysisPressedAt)+"\n② 解析結果を反映中…");
        analysisProgress.setTextColor(WARN);
        startMonitor(true);
    }
    private String clock(long t){return new SimpleDateFormat("HH:mm:ss",Locale.JAPAN).format(new Date(t));}
    private void stopMonitor(){Intent i=new Intent(this,MonitoringService.class);i.setAction(MonitoringService.ACTION_STOP);startService(i);}
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);}

    private void refreshUi(){
        BiquoteStream.Tick liveTick=BiquoteStream.latestTick();
        if(liveTick!=null&&!Double.isNaN(liveTick.mid)){
            liveMid=liveTick.mid;liveMarketState=liveTick.marketState==null?"":liveTick.marketState;
            long transportAge=liveTick.receivedAt<=0?0:Math.max(0,(System.currentTimeMillis()-liveTick.receivedAt)/1000L);
            liveQuoteAge=liveTick.quoteAgeSeconds>=0?liveTick.quoteAgeSeconds+transportAge:transportAge;
        }
        boolean running=store.isRunning(),rapid=store.shockRapid()&&store.shockRapidUntil()>System.currentTimeMillis(),ws=BiquoteStream.isConnected();
        String monitorText=!running?"現在：監視停止":ws?"現在：WebSocketリアルタイム（価格受信 / 軽量解析15秒 / 全足同期5分）":"現在：WebSocket再接続中（全足同期5分）";
        status.setText(monitorText);status.setTextColor(!running?WARN:rapid?DOWN:ws?UP:WARN);startBtn.setEnabled(!running);stopBtn.setEnabled(running);
        if(immediateAnalysisPending){
            long ok=store.lastAnalysisSuccess(),last=store.lastUpdate();
            if(ok>=immediateAnalysisPressedAt){
                immediateAnalysisPending=false;refreshBtn.setEnabled(true);refreshBtn.setText("今すぐ解析");
                analysisProgress.setText("① ボタンを押しました  "+clock(immediateAnalysisPressedAt)+"\n② 解析結果を反映しました  "+clock(ok));
                analysisProgress.setTextColor(UP);
            }else if(last>=immediateAnalysisPressedAt&&!store.lastError().isEmpty()){
                immediateAnalysisPending=false;refreshBtn.setEnabled(true);refreshBtn.setText("今すぐ解析");
                analysisProgress.setText("① ボタンを押しました  "+clock(immediateAnalysisPressedAt)+"\n② 解析に失敗しました  "+clock(last));
                analysisProgress.setTextColor(DOWN);
            }
        }
        double mid=Double.isNaN(liveMid)?store.lastMid():liveMid;
        bidQuote.setText(Double.isNaN(mid)?"---.---":String.format(Locale.JAPAN,"%.3f",DemoStore.bidFromMid(mid)));
        askQuote.setText(Double.isNaN(mid)?"---.---":String.format(Locale.JAPAN,"%.3f",DemoStore.askFromMid(mid)));
        if(candleChart!=null)candleChart.setLivePrice(mid);
        updateChart();
        renderPeakBottom();

        String md=overallTfDirection();mainDir.setText(("up".equals(md)?"↑ ":"down".equals(md)?"↓ ":"→ ")+("up".equals(md)?"上昇":"down".equals(md)?"下降":"中立"));mainDir.setTextColor(directionColor(md));
        readiness.setText(store.readiness()+"%");agreement.setText(store.agreement()+"%");
        updateIndicatorMetrics();updateVisualForecast();
        for(String k:DISPLAY_TF){String d=store.tfDir(k);TextView v=tfViews.get(k);if(v==null)continue;v.setText(("up".equals(d)?"↑":"down".equals(d)?"↓":"→")+store.tfStrength(k)+"%");v.setTextColor(directionColor(d));}
        String er=store.lastError();lastError.setText(er.isEmpty()?"":"通信/解析エラー: "+er);
    }

    private void renderPeakBottom(){
        if(pbContainer==null)return;
        pbContainer.removeAllViews();
        pbContainer.addView(buildPeakBottomPanel(),lp(-1,-2,0,0,0,0));
    }

    private LinearLayout buildPeakBottomPanel(){
        final String[] tfs=DemoStore.DISPLAY_TF;
        Map<String,List<MarketEngine.Candle>> raw=new LinkedHashMap<>();
        for(String tf:tfs)raw.put(tf,store.candleList(tf));
        PeakBottomEngine.MultiTimeframe all=PeakBottomEngine.analyzeAll(raw);
        boolean rapid=store.shockRapid()&&store.shockRapidUntil()>System.currentTimeMillis();

        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);
        GridLayout grid=new GridLayout(this);grid.setColumnCount(5);grid.setRowCount(2);
        for(int i=0;i<tfs.length;i++){
            String tf=tfs[i];PeakBottomEngine.State s=all.byTf.get(tf);
            boolean targetPeak=fallbackPbTarget(tf,raw);
            boolean live=false;
            String phase=targetPeak?"P待ち":"B待ち";
            String timing="目安 --";
            boolean updateCandidate=false;

            if(s!=null&&s.confirmedCount>0&&s.points.size()>=s.confirmedCount){
                PeakBottomEngine.Point confirmed=s.points.get(s.confirmedCount-1);
                targetPeak=!confirmed.peak;
                live=s.points.size()>s.confirmedCount;
                PeakBottomEngine.Point candidate=live?s.points.get(s.points.size()-1):null;
                if(live&&candidate!=null)targetPeak=candidate.peak;

                PeakBottomEngine.TurnEstimate e=PeakBottomEngine.estimateNextTurn(tf,raw);
                int hierarchy=PeakBottomEngine.updateContinuationScore(tf,targetPeak,raw,all);
                if(live){
                    phase="";
                    if(e!=null&&e.available&&e.nextOppAvailable)timing="次"+(targetPeak?"B":"P")+" "+pbEtaCompact(e.nextOppRemainingMinutes);
                    else timing="次"+(targetPeak?"B":"P")+" --";
                }else{
                    phase=targetPeak?"P待ち":"B待ち";
                    if(e!=null&&e.available)timing="目安 "+pbEtaCompact(e.remainingMinutes);
                }
                updateCandidate=!rapid&&live&&hierarchy>=70;
            }

            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(dp(3),dp(3),dp(3),dp(3));cell.setBackground(makePbOuterBg(updateCandidate));

            TextView pv=txt("P",17,targetPeak?UP:MUTED,true);pv.setGravity(Gravity.CENTER);pv.setBackground(makePbLampBg(UP,targetPeak));
            cell.addView(pv,new LinearLayout.LayoutParams(-1,dp(30)));

            String statusLine=phase.isEmpty()?timing:(phase+" "+timing);
            TextView mid=txt(pbTfShort(tf)+"\n"+statusLine,13,TEXT,true);
            mid.setGravity(Gravity.CENTER);mid.setLineSpacing(dp(1),1.0f);mid.setPadding(dp(1),dp(4),dp(1),dp(4));
            cell.addView(mid,new LinearLayout.LayoutParams(-1,dp(49)));

            TextView bv=txt("B",17,!targetPeak?DOWN:MUTED,true);bv.setGravity(Gravity.CENTER);bv.setBackground(makePbLampBg(DOWN,!targetPeak));
            cell.addView(bv,new LinearLayout.LayoutParams(-1,dp(30)));

            GridLayout.LayoutParams gp=new GridLayout.LayoutParams(GridLayout.spec(i/5,1,1f),GridLayout.spec(i%5,1,1f));
            gp.width=0;gp.height=GridLayout.LayoutParams.WRAP_CONTENT;gp.setMargins(dp(2),dp(3),dp(2),dp(3));grid.addView(cell,gp);
        }
        wrap.addView(grid,lp(-1,-2,-2,5,-2,0));
        TextView guide=txt("黄色い外枠＝現在のP/Bがさらに更新する可能性あり（判定は上位足のみ・急変時は消灯）",12,WARN,true);
        guide.setGravity(Gravity.CENTER);wrap.addView(guide,lp(-1,-2,0,5,0,0));
        return wrap;
    }

    private boolean fallbackPbTarget(String tf,Map<String,List<MarketEngine.Candle>> raw){
        String dir=store.tfDir(tf);
        if("up".equals(dir))return true;
        if("down".equals(dir))return false;
        List<MarketEngine.Candle> rows=raw==null?null:raw.get(tf);
        if(rows!=null&&rows.size()>=2){
            MarketEngine.Candle a=rows.get(rows.size()-2),b=rows.get(rows.size()-1);
            if(b.c>a.c)return true;
            if(b.c<a.c)return false;
        }
        return true;
    }

    private android.graphics.drawable.Drawable makePbOuterBg(boolean updateCandidate){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.rgb(20,29,39));g.setCornerRadius(dp(10));
        g.setStroke(dp(updateCandidate?2:1),updateCandidate?WARN:Color.rgb(50,66,82));
        return g;
    }

    private android.graphics.drawable.Drawable makePbLampBg(int accent,boolean active){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
        if(active){
            g.setColor(accent==UP?Color.rgb(17,52,40):Color.rgb(58,28,36));
            g.setStroke(dp(2),accent);
        }else{
            g.setColor(Color.rgb(24,34,45));
            g.setStroke(dp(1),Color.rgb(58,70,82));
        }
        g.setCornerRadius(dp(7));return g;
    }

    private int holdingPbContextScore(String tf,boolean targetPeak,PeakBottomEngine.MultiTimeframe all){
        String[] tfs=DemoStore.DISPLAY_TF;int ti=-1;for(int i=0;i<tfs.length;i++)if(tfs[i].equals(tf)){ti=i;break;}
        int wanted=targetPeak?PeakBottomEngine.UP:PeakBottomEngine.DOWN;double support=0,total=0;
        for(int i=0;i<tfs.length;i++){
            if(i==ti)continue;PeakBottomEngine.State s=all.byTf.get(tfs[i]);
            if(s==null||s.confirmedCount<=0||s.points.size()<s.confirmedCount)continue;
            PeakBottomEngine.Point p=s.points.get(s.confirmedCount-1);int dir=p.peak?PeakBottomEngine.DOWN:PeakBottomEngine.UP;
            double w=i>ti?Math.min(2.2,1.0+.20*(i-ti)):Math.min(.75,.35+.05*Math.abs(ti-i));
            total+=w;if(dir==wanted)support+=w;
        }
        return total<=0?50:Math.max(0,Math.min(100,(int)Math.round(100.0*support/total)));
    }

    private String pbEtaCompact(long min){
        if(min<=0)return "まもなく";
        if(min<60)return min+"分";
        if(min<1440){double h=min/60.0;return h<10?String.format(Locale.JAPAN,"%.1fh",h):Math.round(h)+"h";}
        double d=min/1440.0;return d<10?String.format(Locale.JAPAN,"%.1f日",d):Math.round(d)+"日";
    }

    private String pbTfShort(String tf){
        if("M5".equals(tf))return "5分";if("M15".equals(tf))return "15分";if("M30".equals(tf))return "30分";
        if("H1".equals(tf))return "1H";if("H2".equals(tf))return "2H";if("H4".equals(tf))return "4H";if("H8".equals(tf))return "8H";
        if("D".equals(tf))return "日";if("W".equals(tf))return "週";if("MN".equals(tf))return "月";return tf;
    }

    private void selectTimeframe(String tf){
        if(tf==null||!tfViews.containsKey(tf))return;
        selectedTf=tf;updateTfCellStyles();updateChart();updateIndicatorMetrics();
        int cachedBars=store.candles(tf).length();
        if(cachedBars<2||("MN".equals(tf)&&cachedBars<48))fetchTimeframeNow(tf);
    }
    private void updateIndicatorMetrics(){
        if(indicatorAgreement==null||rapidMoveRisk==null||rapidMoveState==null||volatilityState==null)return;
        int ia=store.indicatorAgreement(selectedTf),rr=store.rapidMoveRisk(selectedTf);String rd=store.rapidMoveDirection(selectedTf);
        indicatorAgreement.setText(ia+"%");indicatorAgreement.setTextColor(ia>=75?UP:ia>=50?WARN:MUTED);
        rapidMoveRisk.setText(rr+"%");rapidMoveRisk.setTextColor(rr>=75?DOWN:rr>=45?WARN:MUTED);
        rapidMoveState.setText(rapidStateText(rr,rd));rapidMoveState.setTextColor(rr>=80?DOWN:rr>=40?WARN:rr>=15?ACCENT:MUTED);
        String vol=store.volatilityRegime();double vr=store.volatilityRatio();
        volatilityState.setText(vol+" / "+String.format(Locale.JAPAN,"%.2f倍",vr));
        volatilityState.setTextColor("急拡大".equals(vol)?DOWN:"高ボラ".equals(vol)?WARN:"通常ボラ".equals(vol)?ACCENT:MUTED);
    }
    private String rapidStateText(int risk,String dir){
        if(risk<15)return "未検出";
        if(risk<40)return "up".equals(dir)?"やや上昇傾向":"down".equals(dir)?"やや下落傾向":"小さな変化";
        if(risk<65)return "up".equals(dir)?"上昇警戒":"down".equals(dir)?"下落警戒":"変動警戒";
        if(risk<80)return "そろそろ急変注意";
        return "急変注意";
    }
    private void updateTfCellStyles(){
        for(Map.Entry<String,LinearLayout> e:tfCells.entrySet())e.getValue().setBackground(makeTfBg(e.getKey().equals(selectedTf)));
    }
    private String tfCellLabel(String tf){
        if("D".equals(tf))return "日足";if("W".equals(tf))return "週足";if("MN".equals(tf))return "月足";
        String v=MarketEngine.LABEL.get(tf);return v==null?tf:v;
    }
    private String selectedTfLabel(){
        if("D".equals(selectedTf))return "日足";if("W".equals(selectedTf))return "週足";if("MN".equals(selectedTf))return "月足";
        String v=MarketEngine.LABEL.get(selectedTf);return (v==null?selectedTf:v)+"足";
    }
    private void updateChart(){
        if(candleChart==null)return;
        candleChart.setTimeframeLabel(selectedTfLabel());
        candleChart.setPeakBottomContext(store.peakBottomSummary());
        org.json.JSONArray indicatorRows=store.candles(selectedTf);candleChart.setCandles(indicatorRows);
        if(rciChart!=null)rciChart.setCandles(indicatorRows);if(macdChart!=null)macdChart.setCandles(indicatorRows);
        candleChart.setLoading(fetchingTf.contains(selectedTf));
        double mid=Double.isNaN(liveMid)?store.lastMid():liveMid;candleChart.setLivePrice(mid);
        renderQuoteMeta();
    }
    private void renderQuoteMeta(){
        if(quoteMeta==null)return;
        if(fetchingTf.contains(selectedTf)){quoteMeta.setText(selectedTfLabel()+"・取得中…");return;}
        String live="";
        if(!Double.isNaN(liveMid)){
            if("closed".equalsIgnoreCase(liveMarketState))live="・市場休止";
            else if(liveQuoteAge>=0)live="・リアルタイム 約"+liveQuoteAge+"秒遅延";
            else live="・リアルタイム";
        }
        String updated=store.lastUpdate()==0?"":"・足更新 "+new SimpleDateFormat("HH:mm:ss",Locale.JAPAN).format(new Date(store.lastUpdate()));
        String history="MN".equals(selectedTf)?"・履歴"+store.candles("MN").length()+"本":"";
        quoteMeta.setText("スプレッド 0.2銭・"+selectedTfLabel()+history+live+updated);
    }
    private void fetchTimeframeNow(String tf){
        if(!fetchingTf.add(tf))return;
        if(tf.equals(selectedTf))updateChart();
        chartExecutor.execute(()->{
            try{
                List<MarketEngine.Candle> rows=new BiquoteClient().fetchTimeframe(tf);
                if("MN".equals(tf)&&rows.size()<48)throw new IllegalStateException("月足履歴不足: "+rows.size()+"本");
                store.saveCandles(tf,rows);if("W".equals(tf)||"MN".equals(tf)){Map<String,List<MarketEngine.Candle>> one=new HashMap<>();one.put(tf,rows);store.saveExtendedAnalysis(one);}
                runOnUiThread(()->{fetchingTf.remove(tf);if(tf.equals(selectedTf))updateChart();});
            }catch(Exception e){
                runOnUiThread(()->{
                    fetchingTf.remove(tf);
                    if(tf.equals(selectedTf)){
                        candleChart.setLoading(false);
                        quoteMeta.setText(selectedTfLabel()+"・取得エラー");
                        Toast.makeText(this,selectedTfLabel()+"の取得に失敗しました",Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private String overallTfDirection(){int up=0,down=0;for(String k:DISPLAY_TF){String d=store.tfDir(k);if("up".equals(d))up++;else if("down".equals(d))down++;}if(up>down&&up>=5)return "up";if(down>up&&down>=5)return "down";return "neutral";}

    private void updateVisualForecast(){
        String s=dominantDirection("M5","M15","M30"),m=dominantDirection("H1","H2","H4"),l=dominantDirection("H8","D","W","MN");
        setDirectionView(shortTrend,s);setDirectionView(midTrend,m);setDirectionView(longTrend,l);
        String bias=forecastBias(s,m,l);
        if("wait".equals(bias)){
            chartForecast.setText("→ 待機");chartForecast.setTextColor(WARN);
            chartAxis.setText("--");chartAxis.setTextColor(MUTED);
            chartTarget.setText("利確候補：方向一致待ち");chartTarget.setTextColor(MUTED);
            return;
        }
        chartForecast.setText(("up".equals(bias)?"↑ 買い":"↓ 売り"));chartForecast.setTextColor(directionColor(bias));
        String axis=chooseAxis(bias);chartAxis.setText(axis);chartAxis.setTextColor(ACCENT);
        double mid=store.lastMid();String horizon=axis.contains("H4")?"long":axis.contains("H1")?"mid":"short";
        double minMove,maxMove;String time;
        if("long".equals(horizon)){minMove=.35;maxMove=.70;time="8〜36時間";}
        else if("mid".equals(horizon)){minMove=.20;maxMove=.45;time="2〜12時間";}
        else{minMove=.10;maxMove=.22;time="30分〜3時間";}
        double confidence=Math.max(.80,Math.min(1.25,.80+store.agreement()/220.0));minMove*=confidence;maxMove*=confidence;
        if(Double.isNaN(mid)){chartTarget.setText("利確候補：約"+String.format(Locale.JAPAN,"%.2f〜%.2f円",minMove,maxMove)+" / "+time);}
        else{double p1="up".equals(bias)?mid+minMove:mid-minMove,p2="up".equals(bias)?mid+maxMove:mid-maxMove;chartTarget.setText("利確候補："+price(p1)+"〜"+price(p2)+" / "+time);}
        chartTarget.setTextColor(directionColor(bias));
    }
    private void setDirectionView(TextView v,String dir){if(v==null)return;v.setText("up".equals(dir)?"↑":"down".equals(dir)?"↓":"→");v.setTextColor(directionColor(dir));}
    private int directionColor(String dir){return "up".equals(dir)?UP:"down".equals(dir)?DOWN:WARN;}

    private String buildMarketNarrative(){
        int up=0,down=0,neutral=0,totalStrength=0;for(String k:DISPLAY_TF){String d=store.tfDir(k);if("up".equals(d))up++;else if("down".equals(d))down++;else neutral++;totalStrength+=store.tfStrength(k);}
        if(up==0&&down==0&&neutral==DISPLAY_TF.length&&totalStrength==0)return "まだ十分な解析データがありません。";
        String shortDir=dominantDirection("M5","M15","M30"),midDir=dominantDirection("H1","H2","H4"),longDir=dominantDirection("H8","D","W","MN"),bias=forecastBias(shortDir,midDir,longDir);double mid=store.lastMid();
        if("wait".equals(bias)){
            StringBuilder b=new StringBuilder();b.append("【売買予想】今は見送り寄りです。短期と中長期の方向がそろっていません。\n");
            if(!"neutral".equals(shortDir)){b.append("【短期で狙うなら】").append(tradeSideText(shortDir)).append("はM15〜M30を軸に、M5が同方向へ再加速した場面だけ。逆張り気味なので利確は浅めが無難です。");appendTargetGuide(b,shortDir,mid,"short");b.append("\n");}
            if(!"neutral".equals(longDir)){b.append("【本命候補】").append(tradeSideText(longDir)).append("はH1〜H4を軸に、M15とM30が").append(directionJp(longDir)).append("へ戻ってから。");appendTargetGuide(b,longDir,mid,"mid");b.append("\n");}
            b.append("【現状】短期 ").append(directionJp(shortDir)).append(" / 中期 ").append(directionJp(midDir)).append(" / 長期 ").append(directionJp(longDir)).append("。準備度").append(store.readiness()).append("%、一致度").append(store.agreement()).append("%。");
            return b.toString();
        }
        String axis=chooseAxis(bias);StringBuilder b=new StringBuilder();b.append("【売買予想】").append(tradeSideText(bias)).append("優勢です。").append("up".equals(bias)?"押し目を待ってからの買いを優先。":"戻りを待ってからの売りを優先。");
        b.append("\n【軸にする時間足】").append(axis).append("。");if(axis.contains("M15"))b.append("M5で再転換を確認して入る形が候補です。");else if(axis.contains("H1"))b.append("M15〜M30の調整終了を確認して入る形が候補です。");else b.append("H1〜H4の押し戻りを待ち、短期足が同方向へ戻った場面が候補です。");
        b.append("\n【利確予想】");appendTargetGuide(b,bias,mid,axis.contains("H4")?"long":axis.contains("H1")?"mid":"short");
        b.append("\n【反対シナリオ】M15とM30が反対方向でそろったら新規は見送り、H1まで反転したら予想を組み直します。");
        b.append("\n【現状】短期 ").append(directionJp(shortDir)).append(" / 中期 ").append(directionJp(midDir)).append(" / 長期 ").append(directionJp(longDir)).append("。準備度").append(store.readiness()).append("%、一致度").append(store.agreement()).append("%。");
        return b.toString();
    }

    private String dominantDirection(String... keys){int up=0,down=0,us=0,ds=0;for(String k:keys){String d=store.tfDir(k);if("up".equals(d)){up++;us+=store.tfStrength(k);}else if("down".equals(d)){down++;ds+=store.tfStrength(k);}}if(up>down)return "up";if(down>up)return "down";if(us>ds)return "up";if(ds>us)return "down";return "neutral";}
    private String forecastBias(String s,String m,String l){if(!"neutral".equals(s)&&!s.equals(l)&&!"neutral".equals(l))return "wait";if(!"neutral".equals(m)&&m.equals(l))return m;if(!"neutral".equals(s)&&s.equals(m))return s;if(store.agreement()>=65){String all=overallTfDirection();if("up".equals(all)||"down".equals(all))return all;}return "wait";}
    private String chooseAxis(String d){int s=supportCount(d,"M5","M15","M30"),m=supportCount(d,"H1","H2","H4"),l=supportCount(d,"H4","H8","D");if(l>=2&&m>=2)return "H1〜H4";if(m>=2)return "H1〜H2";if(s>=2)return "M15〜M30";return "M15〜H1";}
    private int supportCount(String d,String... keys){int n=0;for(String k:keys)if(d.equals(store.tfDir(k)))n++;return n;}
    private String directionJp(String d){return "up".equals(d)?"上向き":"down".equals(d)?"下向き":"中立";}
    private String rapidDirJp(String d){return "up".equals(d)?"上方向警戒":"down".equals(d)?"下方向警戒":"方向未確定";}
    private String tradeSideText(String d){return "up".equals(d)?"ロング（買い）":"down".equals(d)?"ショート（売り）":"見送り";}
    private void appendTargetGuide(StringBuilder b,String dir,double mid,String horizon){double minMove,maxMove;String time;if("long".equals(horizon)){minMove=.35;maxMove=.70;time="8〜36時間";}else if("mid".equals(horizon)){minMove=.20;maxMove=.45;time="2〜12時間";}else{minMove=.10;maxMove=.22;time="30分〜3時間";}double confidence=Math.max(.80,Math.min(1.25,.80+store.agreement()/220.0));minMove*=confidence;maxMove*=confidence;if(!Double.isNaN(mid)){double p1="up".equals(dir)?mid+minMove:mid-minMove,p2="up".equals(dir)?mid+maxMove:mid-maxMove;b.append("現在値 ").append(price(mid)).append(" を基準に、").append(price(p1)).append("〜").append(price(p2)).append(" 付近を利確候補。");}else b.append("現在値から約").append(String.format(Locale.JAPAN,"%.2f",minMove)).append("〜").append(String.format(Locale.JAPAN,"%.2f",maxMove)).append("円を利確幅の目安。");b.append("時間の目安は").append(time).append("。");if("short".equals(horizon))b.append("短期狙いなので伸びなければ早めの撤退を優先。");else b.append("途中でM15/M30が逆向きにそろえば利確を前倒し。");}

    private void applySystemBarInsets(View insetSource,View target,int leftDp,int topDp,int rightDp,int bottomDp){final int baseLeft=dp(leftDp),baseTop=dp(topDp),baseRight=dp(rightDp),baseBottom=dp(bottomDp);target.setPadding(baseLeft,baseTop,baseRight,baseBottom);insetSource.setOnApplyWindowInsetsListener((v,insets)->{int left=0,top=0,right=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;}else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}target.setPadding(baseLeft+left,baseTop+top,baseRight+right,baseBottom+bottom);return insets;});insetSource.requestApplyInsets();}
    private TextView metric(LinearLayout parent,String label,String value){LinearLayout c=miniCard();c.addView(txt(label,10,MUTED,false));TextView v=txt(value,18,TEXT,true);c.addView(v,lp(-1,-2,0,4,0,0));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));parent.addView(space(5));return v;}
    private LinearLayout miniCard(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(7),dp(7),dp(7),dp(7));c.setBackground(makeBg(PANEL2,dp(10)));return c;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(11);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private View space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(dp(d),1));return s;}
    private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
    private android.graphics.drawable.Drawable makeTfBg(boolean selected){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(PANEL2);g.setCornerRadius(dp(12));g.setStroke(dp(selected?2:1),selected?ACCENT:Color.rgb(43,58,73));return g;}
    private LinearLayout.LayoutParams lp(int w,int hgt,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,hgt);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);} private String price(double v){return Double.isNaN(v)?"---.---":String.format(Locale.JAPAN,"%.3f",v);}
}
