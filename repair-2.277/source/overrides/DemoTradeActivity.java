package com.konchan.chappyfx;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class DemoTradeActivity extends Activity {
    private static final int DEMO_TEXT_SP=14;
    private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(248,251,253), MUTED=Color.rgb(205,215,224), UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), WARN=Color.rgb(241,199,95), ACCENT=Color.rgb(104,170,255);
    private DemoStore store; private final Handler h=new Handler(Looper.getMainLooper());
    private TextView position,lastAction,lastUpdate,demoBid,demoAsk;
    private TextView marginBalance,marginNetAssets,marginRequired,marginAvailable,marginRatio,marginState,leverage;
    private TextView summaryFunds,summaryNetAssets,summaryTotalPnl,summaryWinRate,summaryRecord,summaryPositionCount;
    private TextView shockMode,shockMove,shockCause,shockDetail,shockSources,shockChecked;
    private LinearLayout holdingsList;
    private TextView holdingsEmpty;
    private TextView thoughtState,thoughtReason,thoughtNext;
    private Meter longMeter,shortMeter,takeMeter,stopMeter;
    private GestureDetector swipeDetector; private boolean switchingPage=false;
    private final Runnable tick=new Runnable(){@Override public void run(){refreshUi();h.postDelayed(this,1000);}};

    private static final class Meter {
        final ProgressBar bar; final TextView pct;
        Meter(ProgressBar bar,TextView pct){this.bar=bar;this.pct=pct;}
    }
    private static final class Thought {
        int longScore,shortScore,takeScore,stopScore;
        String state,reason,next;
    }

    @Override protected void onCreate(Bundle b){super.onCreate(b);store=new DemoStore(this);buildUi();setupSwipe();}
    @Override protected void onResume(){super.onResume();h.post(tick);}
    @Override protected void onPause(){h.removeCallbacks(tick);super.onPause();}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(14),dp(12),dp(28));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applySystemBarInsets(sv,root,12,14,12,28);

        root.addView(buildPageNav(1),lp(-1,-2,0,0,0,6));
        root.addView(pageTitle("チャッピーのデモトレード"),lp(-1,dp(38),0,0,0,8));

        LinearLayout margin=card();margin.addView(txt("証拠金・取引余力",16,TEXT,true));
        LinearLayout marginRow1=new LinearLayout(this);marginRow1.setOrientation(LinearLayout.HORIZONTAL);
        marginBalance=metricSmall(marginRow1,"受入証拠金","¥100,000");
        marginNetAssets=metricSmall(marginRow1,"純資産","¥100,000");
        marginRequired=metricSmall(marginRow1,"必要証拠金","¥0");
        margin.addView(marginRow1,lp(-1,-2,0,8,0,0));
        LinearLayout marginRow2=new LinearLayout(this);marginRow2.setOrientation(LinearLayout.HORIZONTAL);
        marginAvailable=metricSmall(marginRow2,"取引余力","¥100,000");
        marginRatio=metricSmall(marginRow2,"証拠金維持率","--");
        leverage=metricSmall(marginRow2,"実効レバレッジ","0.0倍 / 上限25倍");
        margin.addView(marginRow2,lp(-1,-2,0,6,0,0));
        marginState=txt("スタンダード25倍 / ロスカット50%",13,MUTED,true);margin.addView(marginState,lp(-1,-2,0,7,0,0));
        margin.addView(txt("証拠金ルール：必要証拠金4%。実効レバレッジは保有建玉と純資産から自動計算し、ポジションなしは0.0倍。両建は大きい側の建玉を基準に表示します。100%未満は追証判定水準、50%未満で全建玉をロスカット。",12,MUTED,false),lp(-1,-2,0,7,0,0));
        root.addView(margin,lp(-1,-2,0,0,0,10));

        LinearLayout summary=card();summary.addView(txt("総合成績",16,TEXT,true));
        LinearLayout summaryRow1=new LinearLayout(this);summaryRow1.setOrientation(LinearLayout.HORIZONTAL);
        summaryFunds=summaryMetric(summaryRow1,"所持金","¥100,000");summaryNetAssets=summaryMetric(summaryRow1,"純資産","¥100,000");summaryTotalPnl=summaryMetric(summaryRow1,"累計損益","¥0");summary.addView(summaryRow1,lp(-1,-2,0,8,0,0));
        LinearLayout summaryRow2=new LinearLayout(this);summaryRow2.setOrientation(LinearLayout.HORIZONTAL);
        summaryWinRate=summaryMetric(summaryRow2,"勝率","--");summaryRecord=summaryMetric(summaryRow2,"成績","0勝0敗");summaryPositionCount=summaryMetric(summaryRow2,"保有","0件");summary.addView(summaryRow2,lp(-1,-2,0,6,0,0));
        root.addView(summary,lp(-1,-2,0,0,0,10));

        LinearLayout holdings=card();holdings.addView(txt("現在の保有ポジション一覧",16,TEXT,true));
        holdingsList=new LinearLayout(this);holdingsList.setOrientation(LinearLayout.VERTICAL);
        holdingsEmpty=txt("現在の保有ポジションはありません。",14,MUTED,false);
        holdings.addView(holdingsList,lp(-1,-2,0,0,0,0));root.addView(holdings,lp(-1,-2,0,0,0,10));

        LinearLayout shock=card();shock.addView(txt("急変監視",16,TEXT,true));
        LinearLayout shockRow=new LinearLayout(this);shockRow.setOrientation(LinearLayout.HORIZONTAL);
        shockMode=metricSmall(shockRow,"監視モード","通常");
        shockMove=metricSmall(shockRow,"直近急変","--");
        shock.addView(shockRow,lp(-1,-2,0,7,0,0));
        shockCause=txt("急変はまだ検出されていません。",13,TEXT,true);shock.addView(shockCause,lp(-1,-2,0,7,0,0));
        root.addView(shock,lp(-1,-2,0,0,0,10));

        TextView note=txt("実売買は行いません。右へ進むと成績・履歴ページを確認できます。",12,MUTED,false);root.addView(note);
    }

    private TextView pageTitle(String s){
        TextView t=txt(s,20,TEXT,true);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;
    }

    private LinearLayout buildPageNav(int active){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels={"相場","デモ","成績","検証","情報"};
        Class<?>[] pages={MainActivity.class,DemoTradeActivity.class,TradeHistoryActivity.class,ResearchLabActivity.class,ImportantInfoActivity.class};
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
                if(dx<0&&velocityX<-350){openPage(TradeHistoryActivity.class);return true;}
                if(dx>0&&velocityX>350){openPage(MainActivity.class);return true;}
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

    private void refreshUi(){
        int lc=store.longPositionCount(),sc=store.shortPositionCount(),pc=store.positionCount();
        BiquoteStream.Tick live=BiquoteStream.latestTick();double mid=live!=null&&!Double.isNaN(live.mid)?live.mid:store.lastMid(),unreal=store.unrealized(mid),eq=store.netAssets(mid),req=store.requiredMargin(mid),avail=store.availableMargin(mid),ratio=store.marginMaintenanceRatio(mid);
        marginBalance.setText(yen(store.balance()));marginNetAssets.setText(yen(eq));marginRequired.setText(yen(req));marginAvailable.setText(yen(avail));
        marginAvailable.setTextColor(avail>=0?TEXT:DOWN);
        int maxSideUnits=Math.max(store.longUnits(),store.shortUnits());
        double effectiveLeverage=(maxSideUnits<=0||Double.isNaN(mid)||mid<=0||eq<=0)?0.0:(mid*maxSideUnits/eq);
        leverage.setText(String.format(Locale.JAPAN,"%.1f倍 / 上限25倍",effectiveLeverage));
        leverage.setTextColor(effectiveLeverage>=25.0?DOWN:effectiveLeverage>=20.0?WARN:TEXT);
        marginRatio.setText(Double.isNaN(ratio)?"--":String.format(Locale.JAPAN,"%.1f%%",ratio));
        marginRatio.setTextColor(Double.isNaN(ratio)?MUTED:ratio<=100?DOWN:ratio<=120?WARN:TEXT);
        marginState.setText(store.marginStatus(mid));marginState.setTextColor(Double.isNaN(ratio)?MUTED:ratio<50?DOWN:ratio<100?DOWN:ratio<=120?WARN:UP);

        summaryFunds.setText(yen(store.balance()));summaryNetAssets.setText(yen(eq));summaryTotalPnl.setText(signedYen(store.totalPnl()));
        summaryTotalPnl.setTextColor(store.totalPnl()>0?UP:store.totalPnl()<0?DOWN:TEXT);
        summaryWinRate.setText(store.closed()==0?"--":String.format(Locale.JAPAN,"%.1f%%",store.wins()*100.0/store.closed()));
        summaryRecord.setText(store.wins()+"勝 "+store.losses()+"敗 / "+store.closed()+"回");summaryPositionCount.setText(pc+"件");

        boolean rapid=store.shockRapid()&&store.shockRapidUntil()>System.currentTimeMillis();
        boolean ws=BiquoteStream.isConnected();
        shockMode.setText(rapid?"急変 / WebSocketリアルタイム":ws?"WebSocketリアルタイム":"WebSocket再接続中 / 5分同期");
        shockMode.setTextColor(rapid?DOWN:ws?ACCENT:WARN);
        shockMode.setSingleLine(true);
        double sm=store.shockMoveYen();int sw=store.shockWindowSec();
        shockMove.setText(Double.isNaN(sm)||sw<=0?"--":String.format(Locale.JAPAN,"%+.3f円 / %d秒",sm,sw));
        shockMove.setTextColor(Double.isNaN(sm)?MUTED:sm>0?UP:sm<0?DOWN:TEXT);
        shockCause.setText(store.shockCauseSummary()+"  [確度 "+store.shockCauseConfidence()+"]");

        renderHoldings();
    }

    private void renderHoldings(){
        if(holdingsList==null)return;holdingsList.removeAllViews();JSONArray ps=store.positions();
        if(ps.length()==0){holdingsList.addView(holdingsEmpty,lp(-1,-2,0,8,0,0));return;}
        double mid=store.lastMid();
        for(int i=0;i<ps.length();i++){
            JSONObject x=ps.optJSONObject(i);if(x==null)continue;
            boolean isLong="long".equals(x.optString("dir"));
            String dir=isLong?"ロング":"ショート";
            LinearLayout card=miniCard();
            TextView title=txt("USD/JPY　"+dir,15,isLong?UP:DOWN,true);card.addView(title);

            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView en=holdingMetricLarge(row,"建値",price(x.optDouble("entry",Double.NaN)));
            TextView qty=holdingMetricLarge(row,"数量",String.format(Locale.JAPAN,"%,d",x.optInt("units",0)));
            double u=store.positionUnrealized(x,mid);
            TextView pnl=holdingMetricLarge(row,"評価損益",(u>=0?"+":"")+yen(u));pnl.setTextColor(u>0?UP:u<0?DOWN:TEXT);
            card.addView(row,lp(-1,-2,0,8,0,0));

            card.addView(buildPositionThoughtPanel(x,mid),lp(-1,-2,0,8,0,0));
            holdingsList.addView(card,lp(-1,-2,0,8,0,0));
        }
    }

    private LinearLayout buildPositionThoughtPanel(JSONObject x,double mid){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        boolean isLong="long".equals(x.optString("dir"));
        double en=x.optDouble("entry",Double.NaN),tg=x.optDouble("target",Double.NaN),st=x.optDouble("stop",Double.NaN);
        double profit=0,loss=0;
        if(!Double.isNaN(mid)&&!Double.isNaN(en)){
            if(isLong){
                profit=safeRatio(mid-en,tg-en);
                loss=safeRatio(en-mid,en-st);
            }else{
                profit=safeRatio(en-mid,en-tg);
                loss=safeRatio(mid-en,st-en);
            }
        }
        int pb=store.peakBottomTurnPressure(isLong?"long":"short");
        int take=clamp(Math.max((int)Math.round(profit*100),(int)Math.round(pb*.85)),0,100);
        int stop=clamp((int)Math.round(loss*100),0,100);
        int hold=clamp(100-Math.max(take,stop),0,100);

        addThoughtChip(row,"継続",hold,ACCENT);
        addThoughtChip(row,"利確",take,UP);
        addThoughtChip(row,"損切",stop,DOWN);
        return row;
    }

    private void addThoughtChip(LinearLayout parent,String label,int pct,int color){
        TextView v=txt(label+" "+pct+"%",13,color,true);v.setGravity(Gravity.CENTER);
        v.setBackground(makeBg(PANEL,dp(9)));
        parent.addView(v,new LinearLayout.LayoutParams(0,dp(36),1));
        if(parent.getChildCount()<5)parent.addView(space(5));
    }

    private void renderThought(){
        Thought t=buildThought();
        thoughtState.setText(t.state);
        thoughtState.setTextColor(t.stopScore>=70?DOWN:t.takeScore>=70?ACCENT:t.longScore>=60?UP:t.shortScore>=60?DOWN:WARN);
        setMeter(longMeter,t.longScore);setMeter(shortMeter,t.shortScore);setMeter(takeMeter,t.takeScore);setMeter(stopMeter,t.stopScore);
        thoughtReason.setText(t.reason);thoughtNext.setText(t.next);
    }

    private Thought buildThought(){
        Thought t=new Thought();
        int[] bias=directionBias();t.longScore=bias[0];t.shortScore=bias[1];
        double mid=store.lastMid();
        if(!store.hasPosition()){
            t.takeScore=0;t.stopScore=0;
            String regime=store.trendRegime(),setup=store.entrySetupState(),side=store.tradeSide(),vol=store.volatilityRegime();
            double volRatio=store.volatilityRatio();int expansion=store.volatilityExpansionScore(),attack=store.entryAttackScore();
            int tr=store.tradeReadiness(),ta=store.tradeAgreement(),bg=store.backgroundRisk(),tech=store.technicalScore();
            if("up".equals(regime)){t.longScore=Math.max(t.longScore,70);t.shortScore=Math.min(t.shortScore,25);}
            else if("down".equals(regime)){t.shortScore=Math.max(t.shortScore,70);t.longScore=Math.min(t.longScore,25);}
            if(store.freshHighInfoRisk())t.state="重要情報待ち";else t.state=setup;
            String trendJa="up".equals(regime)?"上昇寄り":"down".equals(regime)?"下降寄り":"混在・中立";
            String sideJa="up".equals(side)?"ロング":"down".equals(side)?"ショート":"方向待ち";
            t.reason="全10時間足の背景："+trendJa+" / 実際の狙い："+sideJa+"。\n先行攻め点："+attack+" / 必要 "+store.adaptiveAttackScore()+"点以上 / 基本リスク "+store.adaptiveAttackRiskPct()+"%。\n"+
                    "ボラ判定："+vol+"（ATR比 "+String.format(Locale.JAPAN,"%.2f",volRatio)+"倍） / ボラ拡大予兆 "+expansion+"点。\n"+store.technicalSummary()+"\n"+
                    "短期準備度 "+tr+"% / 短期一致度 "+ta+"% / 上位足逆風 "+bg+"% / 総合指標点 "+tech+"%。";
            if("up".equals(side))t.next="狙い：ロング。全時間足を確認したうえで、M5とM15/M30のどちらかに上方向が成立すれば、上位足が逆でも見送り固定にはせず、小さめの先行攻めで試します。上位足逆風が強いほどサイズと利確幅を抑えます。";
            else if("down".equals(side))t.next="狙い：ショート。全時間足を確認したうえで、M5とM15/M30のどちらかに下方向が成立すれば、上位足が逆でも見送り固定にはせず、小さめの先行攻めで試します。上位足逆風が強いほどサイズと利確幅を抑えます。";
            else t.next="全10時間足を監視しつつ、M5とM15/M30で独立した短期方向が成立するのを待ちます。上位足の完全一致は必須にしません。";
            return t;
        }

        if(store.positionCount()>1){
            JSONArray ps=store.positions();double maxTake=0,maxStop=0;int lc=0,sc=0,maxPbPressure=0;
            for(int i=0;i<ps.length();i++){
                JSONObject x=ps.optJSONObject(i);if(x==null)continue;String pd=x.optString("dir","");if("long".equals(pd))lc++;else if("short".equals(pd))sc++;
                double en=x.optDouble("entry",Double.NaN),tg=x.optDouble("target",Double.NaN),st=x.optDouble("stop",Double.NaN);
                double mark="long".equals(pd)?DemoStore.bidFromMid(mid):DemoStore.askFromMid(mid),pp=0,lp=0;
                if(!Double.isNaN(mark)&&!Double.isNaN(en)){if("long".equals(pd)){pp=safeRatio(mark-en,tg-en);lp=safeRatio(en-mark,en-st);}else{pp=safeRatio(en-mark,en-tg);lp=safeRatio(mark-en,st-en);}}
                maxTake=Math.max(maxTake,pp);maxStop=Math.max(maxStop,lp);maxPbPressure=Math.max(maxPbPressure,store.peakBottomTurnPressure(pd));
            }
            t.takeScore=clamp(Math.max((int)Math.round(maxTake*100),(int)Math.round(maxPbPressure*.85)),0,100);t.stopScore=clamp((int)Math.round(maxStop*100),0,100);
            t.state=maxPbPressure>=75?"PB転換接近を確認中":"複数ポジション管理中";
            t.reason="保有 "+ps.length()+"件（ロング "+lc+" / ショート "+sc+"）。評価損益合計 "+yen(store.unrealized(mid))+"。\n各ポジションを個別管理し、全10時間足のPB位相を比較して暫定ピーク/ボトムの更新余地と次の転換目安を見ています。";
            t.next="上位足がまだ同方向のピーク/ボトム待ちなら下位足の更新継続を重く見ます。利確/損切り到達や総合指標の反転も合わせ、PB予測だけでは自動決済しません。";
            return t;
        }

        String dir=store.posDir();double en=store.entry(),tg=store.target(),st=store.stop();
        double profitProgress=0,lossProgress=0;
        if(!Double.isNaN(mid)){
            if("long".equals(dir)){
                profitProgress=safeRatio(mid-en,tg-en);
                lossProgress=safeRatio(en-mid,en-st);
            }else{
                profitProgress=safeRatio(en-mid,en-tg);
                lossProgress=safeRatio(mid-en,st-en);
            }
        }
        t.takeScore=clamp((int)Math.round(profitProgress*100),0,100);
        t.stopScore=clamp((int)Math.round(lossProgress*100),0,100);
        int pbTurnPressure=store.peakBottomTurnPressure(dir);
        t.takeScore=Math.max(t.takeScore,clamp((int)Math.round(pbTurnPressure*.85),0,85));
        boolean opposite=("long".equals(dir)&&t.shortScore>=65)||("short".equals(dir)&&t.longScore>=65);
        if(opposite)t.stopScore=Math.max(t.stopScore,Math.min(100,55+store.tradeAgreement()/2));

        if(t.stopScore>=70)t.state="損切りを警戒中";
        else if(pbTurnPressure>=75)t.state=("long".equals(dir)?"次ピーク":"次ボトム")+"接近を確認中";
        else if(t.takeScore>=70)t.state="利確を検討中";
        else t.state=("long".equals(dir)?"LONG":"SHORT")+"保有を継続判断中";

        long held=Math.max(0,(System.currentTimeMillis()-store.openedAt())/60000);
        t.reason="現在値 "+price(mid)+" / 建値 "+price(en)+"。保有 "+held+"分。\n"+
                "利確ライン "+price(tg)+" まで "+distance(mid,tg)+"、損切りライン "+price(st)+" まで "+distance(mid,st)+"。\n"+
                "PB保有目安\n"+store.peakBottomHoldGuide(dir);
        t.next="次に見る条件：利確到達度 "+t.takeScore+"% / 損切り接近度 "+t.stopScore+"%。"+
                " 全10時間足のPBを比較し、上位足がまだ同方向の転換待ちなら現在の暫定ピーク/ボトム更新を優先して見ます。総合指標の反転や反対方向シグナルも重なれば利確警戒を強めます。";
        return t;
    }

    private int[] directionBias(){
        String[] keys=DemoStore.DISPLAY_TF;double[] weights={1.8,2.8,2.8,1.8,.7,.45,.30,.20,.12,.08};double up=0,down=0;
        for(int i=0;i<keys.length;i++){
            String d=store.tfDir(keys[i]);double s=Math.max(20,store.tfStrength(keys[i]));double w=weights[Math.min(i,weights.length-1)];
            if("up".equals(d))up+=w*s;else if("down".equals(d))down+=w*s;
        }
        double total=up+down;if(total<=0)return new int[]{50,50};
        String regime=store.trendRegime();if("up".equals(regime)){up=Math.max(up,down*2.6);down=Math.min(down,up*.28);}else if("down".equals(regime)){down=Math.max(down,up*2.6);up=Math.min(up,down*.28);}total=up+down;
        double raw=100.0*up/total;
        double conf=Math.max(.35,Math.min(1.0,(store.tradeReadiness()*.55+store.tradeAgreement()*.45)/100.0));
        int ls=clamp((int)Math.round(50+(raw-50)*conf),0,100);return new int[]{ls,100-ls};
    }

    private String dirJa(String d){return "up".equals(d)?"上向き":"down".equals(d)?"下向き":"中立";}
    private String bucketDir(String... keys){
        int up=0,down=0;for(String k:keys){String d=store.tfDir(k);if("up".equals(d))up++;else if("down".equals(d))down++;}
        return up>down?"上向き":down>up?"下向き":"中立";
    }
    private double safeRatio(double n,double d){if(Double.isNaN(n)||Double.isNaN(d)||Math.abs(d)<1e-9)return 0;return Math.max(0,Math.min(1,n/d));}
    private int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private String distance(double a,double b){if(Double.isNaN(a)||Double.isNaN(b))return "--";return String.format(Locale.JAPAN,"%.3f円",Math.abs(a-b));}

    private Meter addMeter(LinearLayout parent,String label,int color){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l=txt(label,11,TEXT,true);row.addView(l,new LinearLayout.LayoutParams(0,-2,1));
        TextView pct=txt("0%",11,TEXT,true);pct.setGravity(Gravity.RIGHT);row.addView(pct,new LinearLayout.LayoutParams(dp(54),-2));
        parent.addView(row,lp(-1,-2,0,8,0,0));
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(color));bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(47,62,77)));
        parent.addView(bar,lp(-1,dp(10),0,3,0,0));return new Meter(bar,pct);
    }
    private void setMeter(Meter m,int value){m.bar.setProgress(value);m.pct.setText(value+"%");}

    private void applySystemBarInsets(View insetSource,View target,int leftDp,int topDp,int rightDp,int bottomDp){
        final int baseLeft=dp(leftDp),baseTop=dp(topDp),baseRight=dp(rightDp),baseBottom=dp(bottomDp);target.setPadding(baseLeft,baseTop,baseRight,baseBottom);
        insetSource.setOnApplyWindowInsetsListener((v,insets)->{int left=0,top=0,right=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;}else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}target.setPadding(baseLeft+left,baseTop+top,baseRight+right,baseBottom+bottom);return insets;});insetSource.requestApplyInsets();
    }
    private TextView metric(LinearLayout parent,String label,String value){LinearLayout c=miniCard();c.addView(txt(label,10,MUTED,false));TextView v=txt(value,18,TEXT,true);c.addView(v,lp(-1,-2,0,4,0,0));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));parent.addView(space(5));return v;}
    private TextView metricSmall(LinearLayout parent,String label,String value){return sharedMetricCard(this,parent,label,value,PANEL2,MUTED,TEXT,12,14);}
    private static TextView sharedMetricCard(Activity a,LinearLayout parent,String label,String value,int panel2,int muted,int text,int labelSp,int valueSp){
        float density=a.getResources().getDisplayMetrics().density;
        java.util.function.IntUnaryOperator dpv=v->(int)(v*density+.5f);
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dpv.applyAsInt(7),dpv.applyAsInt(7),dpv.applyAsInt(7),dpv.applyAsInt(7));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(panel2);bg.setCornerRadius(dpv.applyAsInt(10));bg.setStroke(dpv.applyAsInt(1),Color.rgb(43,58,73));box.setBackground(bg);
        TextView l=new TextView(a);l.setText(label);l.setTextSize(labelSp);l.setTextColor(muted);box.addView(l);
        TextView v=new TextView(a);v.setText(value);v.setTextSize(valueSp);v.setTextColor(text);v.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);vp.setMargins(0,dpv.applyAsInt(4),0,0);box.addView(v,vp);
        parent.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        Space sp=new Space(a);sp.setLayoutParams(new LinearLayout.LayoutParams(dpv.applyAsInt(4),1));parent.addView(sp);
        return v;
    }
    private TextView summaryMetric(LinearLayout parent,String label,String value){return sharedMetricCard(this,parent,label,value,PANEL2,MUTED,TEXT,9,11);}
    private TextView holdingMetric(LinearLayout parent,String label,String value){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(6),0,dp(6),0);c.addView(txt(label,9,MUTED,false));TextView v=txt(value,12,TEXT,true);c.addView(v,lp(-1,-2,0,3,0,0));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));return v;}
    private TextView holdingMetricLarge(LinearLayout parent,String label,String value){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(5),0,dp(5),0);TextView l=txt(label,12,MUTED,false);l.setTextSize(12);c.addView(l);TextView v=txt(value,15,TEXT,true);v.setTextSize(15);c.addView(v,lp(-1,-2,0,3,0,0));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));return v;}
    private LinearLayout miniCard(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(7),dp(7),dp(7),dp(7));c.setBackground(makeBg(PANEL2,dp(10)));return c;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(DEMO_TEXT_SP);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private View space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(dp(d),1));return s;}
    private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private String yen(double v){return (v<0?"-¥":"¥")+String.format(Locale.JAPAN,"%,.0f",Math.abs(v));}
    private String signedYen(double v){return (v>0?"+":v<0?"-":"")+"¥"+String.format(Locale.JAPAN,"%,.0f",Math.abs(v));}
    private String price(double v){return Double.isNaN(v)?"--":String.format(Locale.JAPAN,"%.3f",v);}


    private LinearLayout buildPeakBottomPanel(){
        final String[] tfs=DemoStore.DISPLAY_TF;
        Map<String,List<MarketEngine.Candle>> raw=new LinkedHashMap<>();
        for(String tf:tfs)raw.put(tf,store.candleList(tf));
        PeakBottomEngine.MultiTimeframe all=PeakBottomEngine.analyzeAll(raw);

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
                int ctx=holdingPbContextScore(tf,targetPeak,all);
                if(live){
                    phase="";
                    if(e!=null&&e.available&&e.nextOppAvailable)timing="次"+(targetPeak?"B":"P")+" "+pbEtaCompact(e.nextOppRemainingMinutes);
                    else timing="次"+(targetPeak?"B":"P")+" --";
                }else{
                    phase=targetPeak?"P待ち":"B待ち";
                    if(e!=null&&e.available)timing="目安 "+pbEtaCompact(e.remainingMinutes);
                }
                updateCandidate=ctx>=65;
            }

            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(dp(3),dp(3),dp(3),dp(3));cell.setBackground(makePbOuterBg(updateCandidate));

            TextView pv=txt("P",17,targetPeak?UP:MUTED,true);pv.setTextSize(17);pv.setGravity(Gravity.CENTER);pv.setBackground(makePbLampBg(UP,targetPeak));
            cell.addView(pv,new LinearLayout.LayoutParams(-1,dp(30)));

            String statusLine=phase.isEmpty()?timing:(phase+" "+timing);
            String middle=pbTfShort(tf)+"\n"+statusLine;
            TextView mid=txt(middle,13,TEXT,true);
            mid.setTextSize(13);mid.setGravity(Gravity.CENTER);mid.setLineSpacing(dp(1),1.0f);mid.setPadding(dp(1),dp(4),dp(1),dp(4));
            cell.addView(mid,new LinearLayout.LayoutParams(-1,dp(49)));

            TextView bv=txt("B",17,!targetPeak?DOWN:MUTED,true);bv.setTextSize(17);bv.setGravity(Gravity.CENTER);bv.setBackground(makePbLampBg(DOWN,!targetPeak));
            cell.addView(bv,new LinearLayout.LayoutParams(-1,dp(30)));

            GridLayout.LayoutParams gp=new GridLayout.LayoutParams(GridLayout.spec(i/5,1,1f),GridLayout.spec(i%5,1,1f));
            gp.width=0;gp.height=GridLayout.LayoutParams.WRAP_CONTENT;gp.setMargins(dp(2),dp(3),dp(2),dp(3));grid.addView(cell,gp);
        }
        wrap.addView(grid,lp(-1,-2,-2,5,-2,0));

        TextView guide=txt("黄色い外枠＝P/Bが更新される可能性が高い時間足",12,WARN,true);
        guide.setTextSize(12);guide.setGravity(Gravity.CENTER);wrap.addView(guide,lp(-1,-2,0,5,0,0));
        return wrap;
    }

    private boolean fallbackPbTarget(String tf,Map<String,List<MarketEngine.Candle>> raw){
        String d=store.tfDir(tf);
        if("up".equals(d))return true;
        if("down".equals(d))return false;
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
            PeakBottomEngine.Point p=s.points.get(s.confirmedCount-1);int d=p.peak?PeakBottomEngine.DOWN:PeakBottomEngine.UP;
            double w=i>ti?Math.min(2.2,1.0+.20*(i-ti)):Math.min(.75,.35+.05*Math.abs(ti-i));
            total+=w;if(d==wanted)support+=w;
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
    public static class ResearchLabActivity extends Activity {
        private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(248,251,253), MUTED=Color.rgb(205,215,224), UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), WARN=Color.rgb(241,199,95), ACCENT=Color.rgb(104,170,255);
        private static final String[] TF_KEYS={"M5","M15","M30","H1","H2","H4","H8","D","W","MN"};
        private static final String[] TF_NAMES={"5分","15分","30分","1時間","2時間","4時間","8時間","日足","週足","月足"};
        private final ExecutorService worker=Executors.newSingleThreadExecutor();
        private final Handler handler=new Handler(Looper.getMainLooper());
        private Spinner tfSpinner;
        private Button selectedBtn,allBtn;
        private TextView status,bestSummary,lastSummary,realtimeStatus,realtimeTopNote,realtimeRegime,realtimeLog;
        private LinearLayout results,realtimeTopList;
        private final Runnable realtimeUiTick=new Runnable(){@Override public void run(){refreshRealtime();handler.postDelayed(this,5000);}};
        private GestureDetector swipeDetector;
        private boolean switching=false;

        private interface SignalRule { int signal(List<MarketEngine.Candle> rows,int i); }
        private static final class Strategy {
            final String name,components; final SignalRule rule;
            Strategy(String name,String components,SignalRule rule){this.name=name;this.components=components;this.rule=rule;}
        }
        private static final class TradeSample {
            final double pnl; TradeSample(double pnl){this.pnl=pnl;}
        }
        private static final class Result {
            String tf,name,components;
            int trades,wins,testTrades,componentCount;
            double winRate,trainWinRate,testWinRate,pf,avgPips,stability,score;
            boolean candidate;
        }

        @Override protected void onCreate(Bundle b){
            super.onCreate(b);buildUi();setupSwipe();loadLastSummary();
        }
        @Override protected void onResume(){super.onResume();handler.removeCallbacks(realtimeUiTick);handler.post(realtimeUiTick);}
        @Override protected void onPause(){handler.removeCallbacks(realtimeUiTick);super.onPause();}
        @Override protected void onDestroy(){handler.removeCallbacks(realtimeUiTick);worker.shutdownNow();super.onDestroy();}

        private void buildUi(){
            ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(14),dp(12),dp(28));
            sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applySystemBarInsets(sv,root,12,14,12,28);

            root.addView(buildPageNav(3),lp(-1,-2,0,0,0,6));
            root.addView(pageTitle("検証ラボ"),lp(-1,dp(38),0,0,0,8));

            LinearLayout intro=card();
            intro.addView(txt("検証ラボの仕組み",16,TEXT,true));
            intro.addView(txt("指標の期間・初期設定値は現在の既定値を維持します。相場環境を先に判定し、トレンド相場ではMACD・MA・ADX/DMI・ROC・ドンチャン・PB、レンジではRCI・RSI・ストキャス・Williams %R・CCI・ボリンジャー・反転足を中心に役割を切り替えて検証します。",14,MUTED,false),lp(-1,-2,0,7,0,0));
            intro.addView(txt("同系統のオシレーターを何票も重ねて多数決にはしません。トレンド系・タイミング系・ブレイク系を分け、相場環境に合う役割が確認できる組み合わせを優先します。条件を増やして悪化するなら、より単純な組み合わせを優先します。",14,ACCENT,false),lp(-1,-2,0,7,0,0));
            intro.addView(txt("過去検証で候補を絞り、5分ごとの全時間足同期時に各手法を独立した仮想トレーダーとして並列検証します。価格のリアルタイム受信はWebSocketへ任せ、同じ足を無駄に再計算せず、現在トップ/次点候補・相場環境別成績・直近成績を蓄積します。",14,WARN,false),lp(-1,-2,0,7,0,0));
            intro.addView(txt("有望手法では標準ATR・小幅・短期決済・トレーリング・反対シグナル追従の出口条件も比較します。ATRから低ボラ・通常ボラ・高ボラ・急拡大の4段階を判定し、低ボラでは反転系、高ボラではトレンド/ブレイク系を優先、急拡大では新規仕掛けを止めます。高ボラ・急拡大時はスプレッド拡大と模擬スリッページも損益へ反映します。",14,MUTED,false),lp(-1,-2,0,7,0,0));
            intro.setVisibility(View.GONE);

            LinearLayout live=card();live.addView(txt("リアルタイム手法ランキング",16,TEXT,true));
            realtimeStatus=txt("監視データ待ちです。",14,MUTED,false);realtimeStatus.setLineSpacing(dp(2),1.06f);live.addView(realtimeStatus,lp(-1,-2,0,8,0,0));
            live.addView(txt("リアルタイム TOP 3",16,ACCENT,true),lp(-1,-2,0,12,0,0));
            realtimeTopNote=txt("",13,ACCENT,false);realtimeTopNote.setLineSpacing(dp(2),1.05f);live.addView(realtimeTopNote,lp(-1,-2,0,6,0,0));
            realtimeTopList=new LinearLayout(this);realtimeTopList.setOrientation(LinearLayout.VERTICAL);live.addView(realtimeTopList,lp(-1,-2,0,5,0,0));
            live.addView(txt("相場環境別リーダー",15,TEXT,true),lp(-1,-2,0,12,0,0));
            realtimeRegime=txt("--",13,MUTED,false);realtimeRegime.setLineSpacing(dp(3),1.08f);live.addView(realtimeRegime,lp(-1,-2,0,7,0,0));
            live.addView(txt("チャッピー発見ログ",15,TEXT,true),lp(-1,-2,0,12,0,0));
            realtimeLog=txt("--",13,MUTED,false);realtimeLog.setLineSpacing(dp(3),1.08f);live.addView(realtimeLog,lp(-1,-2,0,7,0,0));
            root.addView(live,lp(-1,-2,0,0,0,10));
            Button infoToggle=button("検証ラボの説明を表示");
            infoToggle.setOnClickListener(v->{boolean show=intro.getVisibility()!=View.VISIBLE;intro.setVisibility(show?View.VISIBLE:View.GONE);infoToggle.setText(show?"検証ラボの説明を閉じる":"検証ラボの説明を表示");});
            root.addView(infoToggle,lp(-1,dp(48),0,0,0,8));root.addView(intro,lp(-1,-2,0,0,0,10));

            LinearLayout control=card();control.addView(txt("過去データ検証",16,TEXT,true));
            tfSpinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,TF_NAMES);tfSpinner.setAdapter(adapter);control.addView(tfSpinner,lp(-1,dp(48),0,7,0,0));
            LinearLayout br=new LinearLayout(this);br.setOrientation(LinearLayout.HORIZONTAL);
            selectedBtn=button("選択足を検証");allBtn=button("全時間足を一括");
            br.addView(selectedBtn,new LinearLayout.LayoutParams(0,dp(46),1));br.addView(space(6));br.addView(allBtn,new LinearLayout.LayoutParams(0,dp(46),1));control.addView(br,lp(-1,-2,0,7,0,0));
            status=txt("未検証です。",14,MUTED,false);control.addView(status,lp(-1,-2,0,7,0,0));root.addView(control,lp(-1,-2,0,0,0,10));

            LinearLayout best=card();best.addView(txt("現在のトップ3",16,TEXT,true));
            bestSummary=txt("検証すると、サンプル数・勝率・未使用区間勝率・PF・平均損益を比較し、成績上位3組を表示します。",14,TEXT,false);bestSummary.setLineSpacing(dp(2),1.06f);best.addView(bestSummary,lp(-1,-2,0,7,0,0));
            root.addView(best,lp(-1,-2,0,0,0,10));

            LinearLayout previous=card();previous.addView(txt("前回の結果",16,TEXT,true));
            lastSummary=txt("まだ保存された検証結果はありません。",14,MUTED,false);previous.addView(lastSummary,lp(-1,-2,0,7,0,0));root.addView(previous,lp(-1,-2,0,0,0,10));

            root.addView(txt("比較結果",16,TEXT,true),lp(-1,-2,0,2,0,6));
            results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);root.addView(results);

            TextView note=txt("検証方法：シグナル発生時の終値から3本後の終値までを1取引として、スプレッド0.2銭を差し引いて評価します。時系列の前半70%と後半30%を分け、後半を未使用区間として表示します。全2要素組み合わせを広く比較し、上位候補を3〜6要素へ拡張します。条件数が増えるほど複雑度ペナルティを加えます。これは簡易バックテストで、将来の利益や勝率を保証するものではありません。",13,MUTED,false);
            note.setLineSpacing(dp(2),1.05f);root.addView(note,lp(-1,-2,0,10,0,0));

            selectedBtn.setOnClickListener(v->runSelected());
            allBtn.setOnClickListener(v->runAll());
        }

        private void loadLastSummary(){
            String s=getSharedPreferences("research_lab",MODE_PRIVATE).getString("lastSummary","");
            if(!s.isEmpty())lastSummary.setText(s);
        }
        private void refreshRealtime(){
            if(realtimeStatus==null)return;ResearchLabEngine.UiSnapshot s=ResearchLabEngine.ui(this);
            realtimeStatus.setText(friendlyExitLabel(s.status));renderRealtimeTop(friendlyExitLabel(s.top3));
            realtimeRegime.setText(friendlyExitLabel(s.regimes));realtimeLog.setText(friendlyExitLabel(s.log));
        }

        private String friendlyExitLabel(String s){return s==null?"":s.replace("短期固定","小幅・短期決済");}

        private void renderRealtimeTop(String raw){
            if(realtimeTopList==null)return;realtimeTopList.removeAllViews();realtimeTopNote.setText("");
            if(raw==null||raw.trim().isEmpty()){realtimeTopList.addView(txt("--",14,MUTED,false));return;}
            String[] blocks=raw.split("\\n\\n");
            for(String block:blocks){
                String b=block.trim();if(b.isEmpty())continue;
                if(b.startsWith("評価基準：")){realtimeTopNote.setText(b);continue;}
                if(!b.startsWith("【")){
                    TextView plain=txt(b,14,MUTED,false);plain.setLineSpacing(dp(3),1.08f);
                    realtimeTopList.addView(plain,lp(-1,-2,0,5,0,7));continue;
                }
                String[] lines=b.split("\\n");
                LinearLayout row=miniCard();row.setPadding(dp(12),dp(12),dp(12),dp(12));
                row.addView(txt(lines.length>0?lines[0]:"",15,TEXT,true));
                String metrics=lines.length>1?lines[1]:"";String score="",rest=metrics;
                int dot=metrics.indexOf("・");
                if(dot>=0){String first=metrics.substring(0,dot).trim();rest=metrics.substring(dot+1).trim();if(first.startsWith("評価 "))score=first.substring(3).trim();}
                else if(metrics.startsWith("評価 "))score=metrics.substring(3).trim();
                if(!score.isEmpty()){
                    TextView badge=txt("評価点 "+score,18,WARN,true);badge.setGravity(Gravity.CENTER);
                    badge.setPadding(dp(12),dp(7),dp(12),dp(7));badge.setBackground(makeScoreBg());
                    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.setMargins(0,dp(8),0,dp(8));row.addView(badge,bp);
                }
                if(!rest.isEmpty()){TextView m=txt(rest,14,TEXT,false);m.setLineSpacing(dp(3),1.08f);row.addView(m,lp(-1,-2,0,2,0,0));}
                if(lines.length>2){StringBuilder extra=new StringBuilder();for(int i=2;i<lines.length;i++){if(extra.length()>0)extra.append("\n");extra.append(lines[i]);}
                    TextView e=txt(extra.toString(),14,MUTED,false);e.setLineSpacing(dp(3),1.08f);row.addView(e,lp(-1,-2,0,6,0,0));}
                realtimeTopList.addView(row,lp(-1,-2,0,3,0,9));
            }
        }

        private void runSelected(){
            final String tf=TF_KEYS[tfSpinner.getSelectedItemPosition()];
            setBusy(true,"「"+TF_NAMES[tfSpinner.getSelectedItemPosition()]+"」のデータを取得して検証中…");
            worker.execute(()->{
                try{
                    List<MarketEngine.Candle> rows=new BiquoteClient().fetchTimeframe(tf);
                    ArrayList<Result> out=testTf(tf,rows);
                    handler.post(()->showResults(out,false));
                }catch(Exception e){handler.post(()->fail("検証に失敗しました: "+shortError(e)));}
            });
        }

        private void runAll(){
            setBusy(true,"全時間足のデータを取得して一括検証中…");
            worker.execute(()->{
                try{
                    Map<String,List<MarketEngine.Candle>> all=new BiquoteClient().fetchAll();
                    ArrayList<Result> out=new ArrayList<>();
                    for(String tf:TF_KEYS){List<MarketEngine.Candle> rows=all.get(tf);if(rows!=null)out.addAll(testTf(tf,rows));}
                    handler.post(()->showResults(out,true));
                }catch(Exception e){handler.post(()->fail("一括検証に失敗しました: "+shortError(e)));}
            });
        }

        private void setBusy(boolean busy,String message){
            selectedBtn.setEnabled(!busy);allBtn.setEnabled(!busy);status.setText(message);status.setTextColor(busy?WARN:MUTED);
            if(busy){results.removeAllViews();bestSummary.setText("検証中です。");}
        }
        private void fail(String message){setBusy(false,message);status.setTextColor(DOWN);}

        private ArrayList<Result> testTf(String tf,List<MarketEngine.Candle> rows){
            ArrayList<Result> out=new ArrayList<>();if(rows==null||rows.size()<70)return out;
            ArrayList<Strategy> base=strategies();
            ArrayList<Result> baseResults=new ArrayList<>();
            for(Strategy s:base){Result r=backtest(tf,rows,s);if(r.trades>0){baseResults.add(r);out.add(r);}}

            // 2要素は全ベース候補を横断して比較。
            addCompositeResults(tf,rows,base,2,0,new ArrayList<Strategy>(),out);

            // 上位の単独候補を種にして3〜6要素へ段階的に拡張。
            baseResults.sort((a,b)->Double.compare(b.score,a.score));
            ArrayList<Strategy> seeds=new ArrayList<>();
            for(Result r:baseResults){
                if(r.trades<8)continue;
                for(Strategy s:base)if(s.name.equals(r.name)){seeds.add(s);break;}
                if(seeds.size()>=8)break;
            }
            for(int size=3;size<=6&&seeds.size()>=size;size++)addCompositeResults(tf,rows,seeds,size,0,new ArrayList<Strategy>(),out);
            return out;
        }

        private void addCompositeResults(String tf,List<MarketEngine.Candle> rows,List<Strategy> seeds,int target,int start,ArrayList<Strategy> pick,ArrayList<Result> out){
            if(pick.size()==target){
                Strategy s=combine(pick);Result r=backtest(tf,rows,s);if(r.trades>0)out.add(r);return;
            }
            for(int i=start;i<=seeds.size()-(target-pick.size());i++){pick.add(seeds.get(i));addCompositeResults(tf,rows,seeds,target,i+1,pick,out);pick.remove(pick.size()-1);}
        }

        private Strategy combine(List<Strategy> parts){
            ArrayList<Strategy> frozen=new ArrayList<>(parts);LinkedHashSet<String> tags=new LinkedHashSet<>();
            for(Strategy s:frozen)tags.addAll(componentTags(s.components));
            final int needed=frozen.size()/2+1;
            String joined=joinTags(tags),name="自動探索 "+tags.size()+"要素";
            return new Strategy(name,joined,(r,i)->{
                int up=0,down=0;
                for(Strategy s:frozen){int x=s.rule.signal(r,i);if(x>0)up++;else if(x<0)down++;}
                if(up>=needed&&down==0)return 1;if(down>=needed&&up==0)return -1;return 0;
            });
        }

        private Result backtest(String tf,List<MarketEngine.Candle> rows,Strategy s){
            ArrayList<TradeSample> samples=new ArrayList<>();final int horizon=3;
            for(int i=55;i+horizon<rows.size();i++){
                int dir=s.rule.signal(rows,i);if(dir==0)continue;
                String rg=labRegime(rows,i);if(!manualRegimeCompatible(s,rg,dir))continue;
                double entry=rows.get(i).c,exit=rows.get(i+horizon).c;
                double pnl=dir*(exit-entry)-DemoStore.SPREAD;
                samples.add(new TradeSample(pnl));i+=horizon-1;
            }
            Result r=new Result();r.tf=tf;r.name=s.name;r.components=s.components;r.componentCount=componentTags(s.components).size();r.trades=samples.size();
            if(samples.isEmpty())return r;
            int split=Math.max(1,(int)Math.floor(samples.size()*.70)),wins=0,trainWins=0,testWins=0;double gp=0,gl=0,sum=0;
            for(int i=0;i<samples.size();i++){
                double p=samples.get(i).pnl;sum+=p;if(p>0){wins++;gp+=p;}else gl+=-p;
                if(i<split){if(p>0)trainWins++;}else if(p>0)testWins++;
            }
            r.wins=wins;r.testTrades=Math.max(0,samples.size()-split);
            r.winRate=100.0*wins/samples.size();
            r.trainWinRate=100.0*trainWins/split;
            r.testWinRate=r.testTrades==0?0:100.0*testWins/r.testTrades;
            r.pf=gl<=1e-9?(gp>0?9.99:0):Math.min(9.99,gp/gl);
            r.avgPips=(sum/samples.size())/0.01;
            r.stability=Math.max(0,100-Math.abs(r.trainWinRate-r.testWinRate));
            double pfScore=Math.min(100,r.pf*40.0),sampleFactor=.60+.40*Math.min(1.0,r.trades/30.0);
            double complexityPenalty=Math.max(.84,1.0-Math.max(0,r.componentCount-2)*.04);
            r.score=(r.testWinRate*.45+r.winRate*.20+pfScore*.20+r.stability*.15)*sampleFactor*complexityPenalty;
            r.candidate=r.trades>=15&&r.testTrades>=5&&r.testWinRate>=55&&r.pf>=1.10&&r.avgPips>0;
            return r;
        }

        private void showResults(ArrayList<Result> out,boolean all){
            setBusy(false,all?"全時間足の一括検証が完了しました。":"選択時間足の検証が完了しました。");status.setTextColor(UP);
            out.sort((a,b)->Double.compare(b.score,a.score));results.removeAllViews();
            if(out.isEmpty()){bestSummary.setText("十分なデータまたはシグナルがありませんでした。");return;}

            ArrayList<Result> ranked=new ArrayList<>();for(Result r:out)if(r.candidate)ranked.add(r);
            if(ranked.size()<3)for(Result r:out)if(!ranked.contains(r)){ranked.add(r);if(ranked.size()>=3)break;}
            int topN=Math.min(3,ranked.size());StringBuilder top=new StringBuilder();
            for(int i=0;i<topN;i++){
                Result r=ranked.get(i);if(i>0)top.append("\n\n");
                top.append("【").append(i+1).append("位】").append(tfLabel(r.tf)).append(" / ").append(r.name).append("（").append(r.componentCount).append("要素）\n")
                   .append("勝率 ").append(pct(r.winRate)).append("・未使用 ").append(pct(r.testWinRate)).append("・PF ").append(String.format(Locale.JAPAN,"%.2f",r.pf))
                   .append("・平均 ").append(signed(r.avgPips)).append("pips・取引 ").append(r.trades).append("回\n")
                   .append("構成: ").append(r.components);
            }
            String summary=top.toString();bestSummary.setText(summary);
            String saved=new SimpleDateFormat("M/d HH:mm",Locale.JAPAN).format(new Date())+"  "+summary.replace("\n"," / ");
            getSharedPreferences("research_lab",MODE_PRIVATE).edit().putString("lastSummary",saved).putLong("lastRun",System.currentTimeMillis()).apply();
            lastSummary.setText(saved);

            int limit=Math.min(20,out.size());
            for(int i=0;i<limit;i++){
                Result r=out.get(i);LinearLayout card=miniCard();
                TextView title=txt((i+1)+". "+tfLabel(r.tf)+"  "+r.name+"  ["+r.componentCount+"要素]",15,TEXT,true);card.addView(title);
                String state=r.candidate?"候補":"観察";
                TextView metrics=txt("["+state+"] 評価 "+String.format(Locale.JAPAN,"%.1f",r.score)+"  取引 "+r.trades+"回  勝率 "+pct(r.winRate)+"  未使用 "+pct(r.testWinRate)+"  PF "+String.format(Locale.JAPAN,"%.2f",r.pf)+"  平均 "+signed(r.avgPips)+"pips",13,r.candidate?UP:MUTED,false);
                metrics.setLineSpacing(dp(2),1.05f);card.addView(metrics,lp(-1,-2,0,5,0,0));
                card.addView(txt(r.components,12,MUTED,false),lp(-1,-2,0,5,0,0));
                results.addView(card,lp(-1,-2,0,0,0,7));
            }
        }

        private LinkedHashSet<String> componentTags(String text){
            LinkedHashSet<String> out=new LinkedHashSet<>();String s=text==null?"":text;
            String[][] map={
                {"RCI","RCI"},{"MACD","MACD"},{"RSI","RSI"},{"Stochastic","ストキャス"},{"ストキャス","ストキャス"},
                {"EMA","EMA"},{"ボリンジャー","ボリンジャー"},{"ATR","ATR"},{"ドンチャン","ドンチャン"},
                {"包み足","包み足"},{"ピンバー","ピンバー"},{"ハンマー","ハンマー"},{"シューティングスター","シューティングスター"},
                {"3本","連続足"},{"連続","連続足"},{"インサイドバー","インサイドバー"},{"ドージー","ドージー"},{"大実体","大実体"},
                {"高値","高安ブレイク"},{"安値","高安ブレイク"},{"ADX","ADX/DMI"},{"DMI","ADX/DMI"},{"CCI","CCI"},{"Williams","Williams %R"},{"ROC","ROC"},{"ダブルトップ","ダブルトップ/ボトム"},{"ダブルボトム","ダブルトップ/ボトム"},{"SMA","SMAクロス"},{"ピークボトム","ピークボトム"}
            };
            for(String[] x:map)if(s.contains(x[0]))out.add(x[1]);
            if(out.isEmpty())out.add("価格条件");
            return out;
        }
        private String joinTags(LinkedHashSet<String> tags){StringBuilder b=new StringBuilder();for(String x:tags){if(b.length()>0)b.append(" + ");b.append(x);}return b.toString();}

        private String labRegime(List<MarketEngine.Candle> r,int i){
            double a=atr(r,14,i),e5=ema(r,5,i),e25=ema(r,25,i),e75=ema(r,75,i);double[] dm=dmi(r,14,i);
            if(a>0&&dm[0]>=20&&e5>e25&&e25>e75&&e5-e25>a*.12&&dm[1]>dm[2])return "上昇トレンド";
            if(a>0&&dm[0]>=20&&e5<e25&&e25<e75&&e25-e5>a*.12&&dm[2]>dm[1])return "下降トレンド";
            return "レンジ";
        }
        private boolean manualRegimeCompatible(Strategy s,String regime,int dir){
            if("上昇トレンド".equals(regime))return dir>0;
            if("下降トレンド".equals(regime))return dir<0;
            String x=s.components==null?"":s.components;
            boolean timing=x.contains("RCI")||x.contains("RSI")||x.contains("ストキャス")||x.contains("Williams")||x.contains("CCI")||x.contains("ボリンジャー")||x.contains("包み足")||x.contains("ピンバー")||x.contains("ハンマー")||x.contains("流れ星")||x.contains("ダブル");
            boolean breakout=x.contains("ドンチャン")||x.contains("突破")||x.contains("ADX")||x.contains("DMI")||x.contains("ATR")||x.contains("高安ブレイク");
            return timing||breakout;
        }

        private ArrayList<Strategy> strategies(){
            ArrayList<Strategy> s=new ArrayList<>();
            s.add(new Strategy("RCI + MACD","RCI5/10/20の方向一致＋MACD5/20/9ヒストグラム加速",(r,i)->{
                double a=rci(r,5,i),b=rci(r,10,i),c=rci(r,20,i),ap=rci(r,5,i-1),h=macdHist(r,i),hp=macdHist(r,i-1);
                if(a>b&&b>=c&&a>ap&&h>0&&h>hp)return 1;if(a<b&&b<=c&&a<ap&&h<0&&h<hp)return -1;return 0;}));
            s.add(new Strategy("RCI反転 + MACD","RCI5/10/20の高値・安値密集崩れ＋MACD5/20/9悪化/改善",(r,i)->{
                double rs=rci(r,5,i),rm=rci(r,10,i),rl=rci(r,20,i),ps=rci(r,5,i-1),pm=rci(r,10,i-1),pl=rci(r,20,i-1),h=macdHist(r,i),hp=macdHist(r,i-1);
                double pmin=Math.min(ps,Math.min(pm,pl)),pmax=Math.max(ps,Math.max(pm,pl));
                boolean high=pmin>=65&&pmax-pmin<=30,low=pmax<=-65&&pmax-pmin<=30;
                if(high&&rs<=ps-7&&rm<=pm-2&&rl<=pl+1&&h<hp)return -1;if(low&&rs>=ps+7&&rm>=pm+2&&rl>=pl-1&&h>hp)return 1;return 0;}));
            s.add(new Strategy("RSI + MACD","RSI14の過熱反転＋MACDゼロ方向転換",(r,i)->{
                double x=rsi(r,14,i),h=macdHist(r,i),hp=macdHist(r,i-1);
                if(x<=38&&h>0&&hp<=0)return 1;if(x>=62&&h<0&&hp>=0)return -1;return 0;}));
            s.add(new Strategy("ストキャス + 包み足","Stochastic %K14/%D3のクロス＋包み足",(r,i)->{
                double k=stoch(r,14,i),d=stochD(r,14,3,i),kp=stoch(r,14,i-1),dp=stochD(r,14,3,i-1);if(kp<=dp&&k>d&&k<35&&bullEngulf(r,i))return 1;if(kp>=dp&&k<d&&k>65&&bearEngulf(r,i))return -1;return 0;}));
            s.add(new Strategy("EMA5/25/75 + ピンバー","標準EMA（5/25/75）の並び＋ピンバー反転",(r,i)->{
                double e5=ema(r,5,i),e25=ema(r,25,i),e75=ema(r,75,i);if(e5>e25&&e25>e75&&bullPin(r,i))return 1;if(e5<e25&&e25<e75&&bearPin(r,i))return -1;return 0;}));
            s.add(new Strategy("ボリンジャー + RSI反転","25期間±2σの外側からバンド内へ戻る動き＋RSI14反転",(r,i)->{
                double m=sma(r,25,i),sd=std(r,25,i),pm=sma(r,25,i-1),ps=std(r,25,i-1),x=rsi(r,14,i),xp=rsi(r,14,i-1),cl=r.get(i).c,pc=r.get(i-1).c;
                if(pc<pm-2*ps&&cl>m-2*sd&&xp<35&&x>xp)return 1;if(pc>pm+2*ps&&cl<m+2*sd&&xp>65&&x<xp)return -1;return 0;}));
            s.add(new Strategy("ボリンジャーブレイク + MACD","25期間±2σブレイク＋MACD5/20/9方向一致",(r,i)->{
                double m=sma(r,25,i),sd=std(r,25,i),h=macdHist(r,i),cl=r.get(i).c;
                if(cl>m+2*sd&&h>0)return 1;if(cl<m-2*sd&&h<0)return -1;return 0;}));
            s.add(new Strategy("EMA押し目 + RSI","EMA20/50トレンド＋EMA20付近の押し戻り＋RSI",(r,i)->{
                double e20=ema(r,20,i),e50=ema(r,50,i),x=rsi(r,14,i),a=atr(r,14,i),cl=r.get(i).c;
                if(Math.abs(cl-e20)>a*.55)return 0;if(e20>e50&&x>=45&&x<=62&&cl>r.get(i).o)return 1;if(e20<e50&&x>=38&&x<=55&&cl<r.get(i).o)return -1;return 0;}));
            s.add(new Strategy("ドンチャン20 + EMA50","直近20本高値/安値ブレイク＋EMA50方向",(r,i)->{
                if(i<21)return 0;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int j=i-20;j<i;j++){hi=Math.max(hi,r.get(j).h);lo=Math.min(lo,r.get(j).l);}double e=ema(r,50,i),cl=r.get(i).c;
                if(cl>hi&&cl>e)return 1;if(cl<lo&&cl<e)return -1;return 0;}));
            s.add(new Strategy("ハンマー/流れ星 + RCI","ハンマー・シューティングスター＋RCI5の過熱",(r,i)->{
                double x=rci(r,5,i);if(hammer(r,i)&&x<-45)return 1;if(shootingStar(r,i)&&x>45)return -1;return 0;}));
            s.add(new Strategy("3本連続足 + EMA20","3本連続陽線/陰線＋EMA20方向",(r,i)->{
                double e=ema(r,20,i),cl=r.get(i).c;if(threeBull(r,i)&&cl>e)return 1;if(threeBear(r,i)&&cl<e)return -1;return 0;}));
            s.add(new Strategy("インサイドバー突破 + MACD","前足インサイドバーからのレンジ突破＋MACD方向",(r,i)->{
                if(i<2)return 0;MarketEngine.Candle mother=r.get(i-2),inside=r.get(i-1),cur=r.get(i);boolean in=inside.h<mother.h&&inside.l>mother.l;double h=macdHist(r,i);
                if(in&&cur.c>mother.h&&h>0)return 1;if(in&&cur.c<mother.l&&h<0)return -1;return 0;}));
            s.add(new Strategy("ATR拡大 + 大実体","ATR14の1.5倍以上の値幅＋大実体＋EMA20方向",(r,i)->{
                double a=atr(r,14,i-1),range=r.get(i).h-r.get(i).l,body=Math.abs(r.get(i).c-r.get(i).o),e=ema(r,20,i);if(a<=0||range<a*1.5||body<range*.65)return 0;
                if(r.get(i).c>r.get(i).o&&r.get(i).c>e)return 1;if(r.get(i).c<r.get(i).o&&r.get(i).c<e)return -1;return 0;}));
            s.add(new Strategy("ドージー突破 + RSI","直前ドージーの高安突破＋RSI方向",(r,i)->{
                if(i<1||!doji(r,i-1))return 0;MarketEngine.Candle p=r.get(i-1),c=r.get(i);double x=rsi(r,14,i);
                if(c.c>p.h&&x>52)return 1;if(c.c<p.l&&x<48)return -1;return 0;}));
            s.add(new Strategy("ADX/DMI + EMA","ADX14でトレンド強度を確認し、DI方向とEMA20/50を一致",(r,i)->{
                double[] d=dmi(r,14,i);double e20=ema(r,20,i),e50=ema(r,50,i);if(d[0]<20)return 0;
                if(d[1]>d[2]&&e20>e50)return 1;if(d[2]>d[1]&&e20<e50)return -1;return 0;}));
            s.add(new Strategy("CCI + EMA","トレンドではCCI20の±100突破、レンジでは±100からの戻りをEMAと併用",(r,i)->{
                double x=cci(r,20,i),p=cci(r,20,i-1),e=ema(r,20,i),cl=r.get(i).c;String rg=labRegime(r,i);
                if("レンジ".equals(rg)){if(p<-100&&x>-100&&cl>=e)return 1;if(p>100&&x<100&&cl<=e)return -1;return 0;}
                if(p<=100&&x>100&&cl>e)return 1;if(p>=-100&&x<-100&&cl<e)return -1;return 0;}));
            s.add(new Strategy("Williams %R + 包み足","Williams %R14の過熱域からの反転＋包み足",(r,i)->{
                double w=williamsR(r,14,i),wp=williamsR(r,14,i-1);if(w<-80&&w>wp&&bullEngulf(r,i))return 1;if(w>-20&&w<wp&&bearEngulf(r,i))return -1;return 0;}));
            s.add(new Strategy("ROC + EMA","ROC12のゼロライン方向＋EMA20/50トレンド",(r,i)->{
                double x=roc(r,12,i),xp=roc(r,12,i-1),e20=ema(r,20,i),e50=ema(r,50,i);
                if(x>0&&x>xp&&e20>e50)return 1;if(x<0&&x<xp&&e20<e50)return -1;return 0;}));
            s.add(new Strategy("SMA5/25/75クロス + RSI","SMA5/25クロス＋SMA75とRSI14で方向確認",(r,i)->{
                double a=sma(r,5,i),b=sma(r,25,i),l=sma(r,75,i),ap=sma(r,5,i-1),bp=sma(r,25,i-1),x=rsi(r,14,i);
                if(ap<=bp&&a>b&&b>l&&x>50)return 1;if(ap>=bp&&a<b&&b<l&&x<50)return -1;return 0;}));
            s.add(new Strategy("ダブルトップ/ボトム + MACD","局所ダブルトップ/ボトム＋MACDヒストグラム方向",(r,i)->{
                int p=doublePattern(r,i);double h=macdHist(r,i);if(p>0&&h>0)return 1;if(p<0&&h<0)return -1;return 0;}));
            s.add(new Strategy("ピークボトム継続","確定ピーク/ボトムから次の波が未完了の方向を追跡",(r,i)->PeakBottomEngine.waveDirectionAt(r,i)));
            s.add(new Strategy("ピークボトム + RCI","ピークボトム10の波方向＋RCI5/10/20の方向一致",(r,i)->{
                int pb=PeakBottomEngine.waveDirectionAt(r,i);double a=rci(r,5,i),b=rci(r,10,i),c=rci(r,20,i);
                if(pb>0&&a>b&&b>=c)return 1;if(pb<0&&a<b&&b<=c)return -1;return 0;}));
            return s;
        }

        private static double[] dmi(List<MarketEngine.Candle> r,int p,int end){
            if(end<p+1)return new double[]{0,0,0};double tr=0,pdm=0,mdm=0;
            for(int i=end-p+1;i<=end;i++){MarketEngine.Candle c=r.get(i),q=r.get(i-1);double up=c.h-q.h,down=q.l-c.l;
                pdm+=up>down&&up>0?up:0;mdm+=down>up&&down>0?down:0;tr+=Math.max(c.h-c.l,Math.max(Math.abs(c.h-q.c),Math.abs(c.l-q.c)));}
            if(tr<=1e-9)return new double[]{0,0,0};double plus=100*pdm/tr,minus=100*mdm/tr,den=plus+minus,adx=den<=1e-9?0:100*Math.abs(plus-minus)/den;return new double[]{adx,plus,minus};
        }
        private static double cci(List<MarketEngine.Candle> r,int p,int end){
            if(end+1<p)return 0;double mean=0;for(int i=end-p+1;i<=end;i++)mean+=(r.get(i).h+r.get(i).l+r.get(i).c)/3.0;mean/=p;
            double dev=0;for(int i=end-p+1;i<=end;i++)dev+=Math.abs((r.get(i).h+r.get(i).l+r.get(i).c)/3.0-mean);dev/=p;
            double tp=(r.get(end).h+r.get(end).l+r.get(end).c)/3.0;return dev<=1e-9?0:(tp-mean)/(.015*dev);
        }
        private static double williamsR(List<MarketEngine.Candle> r,int p,int end){
            if(end+1<p)return -50;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int i=end-p+1;i<=end;i++){hi=Math.max(hi,r.get(i).h);lo=Math.min(lo,r.get(i).l);}return hi<=lo?-50:-100*(hi-r.get(end).c)/(hi-lo);
        }
        private static double roc(List<MarketEngine.Candle> r,int p,int end){if(end<p)return 0;double base=r.get(end-p).c;return Math.abs(base)<1e-9?0:100*(r.get(end).c-base)/base;}
        private static int doublePattern(List<MarketEngine.Candle> r,int end){
            if(end<14)return 0;double a=atr(r,14,end);if(a<=0)return 0;
            int first=end-10,mid=end-5;double hi1=-Double.MAX_VALUE,hi2=-Double.MAX_VALUE,lo1=Double.MAX_VALUE,lo2=Double.MAX_VALUE;
            for(int i=first;i<mid;i++){hi1=Math.max(hi1,r.get(i).h);lo1=Math.min(lo1,r.get(i).l);}
            for(int i=mid;i<end;i++){hi2=Math.max(hi2,r.get(i).h);lo2=Math.min(lo2,r.get(i).l);}
            double cl=r.get(end).c;if(Math.abs(lo1-lo2)<=a*.45&&cl>Math.max(r.get(end-1).h,r.get(end-2).h))return 1;
            if(Math.abs(hi1-hi2)<=a*.45&&cl<Math.min(r.get(end-1).l,r.get(end-2).l))return -1;return 0;
        }

        private static double ema(List<MarketEngine.Candle> r,int p,int end){
            int e=Math.min(end,r.size()-1),start=Math.max(0,e-p*5);double a=2.0/(p+1.0),v=r.get(start).c;for(int i=start+1;i<=e;i++)v=r.get(i).c*a+v*(1-a);return v;
        }
        private static double sma(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return Double.NaN;double s=0;for(int i=end-p+1;i<=end;i++)s+=r.get(i).c;return s/p;}
        private static double std(List<MarketEngine.Candle> r,int p,int end){double m=sma(r,p,end);if(Double.isNaN(m))return Double.NaN;double s=0;for(int i=end-p+1;i<=end;i++){double d=r.get(i).c-m;s+=d*d;}return Math.sqrt(s/p);}
        private static double rsi(List<MarketEngine.Candle> r,int p,int end){if(end<p)return 50;double g=0,l=0;for(int i=end-p+1;i<=end;i++){double d=r.get(i).c-r.get(i-1).c;if(d>0)g+=d;else l-=d;}if(l==0)return 100;double rs=g/l;return 100-100/(1+rs);}
        private static double stoch(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return 50;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int i=end-p+1;i<=end;i++){hi=Math.max(hi,r.get(i).h);lo=Math.min(lo,r.get(i).l);}return hi<=lo?50:100*(r.get(end).c-lo)/(hi-lo);}
        private static double stochD(List<MarketEngine.Candle> r,int kp,int dp,int end){if(end<dp-1)return 50;double z=0;for(int i=end-dp+1;i<=end;i++)z+=stoch(r,kp,i);return z/dp;}
        private static double atr(List<MarketEngine.Candle> r,int p,int end){if(end<p)return 0;double s=0;for(int i=end-p+1;i<=end;i++){MarketEngine.Candle a=r.get(i),b=r.get(i-1);s+=Math.max(a.h-a.l,Math.max(Math.abs(a.h-b.c),Math.abs(a.l-b.c)));}return s/p;}
        private static double macdHist(List<MarketEngine.Candle> r,int end){if(end<2)return 0;double af=2.0/6.0,as=2.0/21.0,ag=2.0/10.0,fast=r.get(0).c,slow=r.get(0).c,sig=0,m=0;for(int i=0;i<=end;i++){double c=r.get(i).c;if(i>0){fast=c*af+fast*(1-af);slow=c*as+slow*(1-as);}m=fast-slow;sig=i==0?m:m*ag+sig*(1-ag);}return m-sig;}
        private static double rci(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return 0;int start=end-p+1;double sum=0;for(int i=0;i<p;i++){double price=r.get(start+i).c;int less=0,equal=0;for(int j=0;j<p;j++){double q=r.get(start+j).c;if(q<price)less++;else if(Double.compare(q,price)==0)equal++;}double pr=less+(equal+1)/2.0,tr=i+1,d=tr-pr;sum+=d*d;}return 100*(1-6*sum/(p*(p*p-1.0)));}
        private static boolean bullEngulf(List<MarketEngine.Candle> r,int i){if(i<1)return false;MarketEngine.Candle p=r.get(i-1),c=r.get(i);return p.c<p.o&&c.c>c.o&&c.o<=p.c&&c.c>=p.o;}
        private static boolean bearEngulf(List<MarketEngine.Candle> r,int i){if(i<1)return false;MarketEngine.Candle p=r.get(i-1),c=r.get(i);return p.c>p.o&&c.c<c.o&&c.o>=p.c&&c.c<=p.o;}
        private static boolean bullPin(List<MarketEngine.Candle> r,int i){MarketEngine.Candle c=r.get(i);double body=Math.max(.00001,Math.abs(c.c-c.o)),lower=Math.min(c.o,c.c)-c.l,upper=c.h-Math.max(c.o,c.c);return lower>=body*2.0&&upper<=body*1.2;}
        private static boolean bearPin(List<MarketEngine.Candle> r,int i){MarketEngine.Candle c=r.get(i);double body=Math.max(.00001,Math.abs(c.c-c.o)),lower=Math.min(c.o,c.c)-c.l,upper=c.h-Math.max(c.o,c.c);return upper>=body*2.0&&lower<=body*1.2;}
        private static boolean hammer(List<MarketEngine.Candle> r,int i){MarketEngine.Candle c=r.get(i);double range=Math.max(.00001,c.h-c.l),body=Math.abs(c.c-c.o),lower=Math.min(c.o,c.c)-c.l;return body<=range*.40&&lower>=range*.50;}
        private static boolean shootingStar(List<MarketEngine.Candle> r,int i){MarketEngine.Candle c=r.get(i);double range=Math.max(.00001,c.h-c.l),body=Math.abs(c.c-c.o),upper=c.h-Math.max(c.o,c.c);return body<=range*.40&&upper>=range*.50;}
        private static boolean threeBull(List<MarketEngine.Candle> r,int i){if(i<2)return false;for(int j=i-2;j<=i;j++)if(r.get(j).c<=r.get(j).o)return false;return r.get(i).c>r.get(i-1).c&&r.get(i-1).c>r.get(i-2).c;}
        private static boolean threeBear(List<MarketEngine.Candle> r,int i){if(i<2)return false;for(int j=i-2;j<=i;j++)if(r.get(j).c>=r.get(j).o)return false;return r.get(i).c<r.get(i-1).c&&r.get(i-1).c<r.get(i-2).c;}
        private static boolean doji(List<MarketEngine.Candle> r,int i){MarketEngine.Candle c=r.get(i);double range=Math.max(.00001,c.h-c.l);return Math.abs(c.c-c.o)<=range*.12;}

        private TextView pageTitle(String s){
            TextView t=txt(s,20,TEXT,true);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;
        }

        private LinearLayout buildPageNav(int active){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            String[] labels={"相場","デモ","成績","検証","情報"};
            Class<?>[] pages={MainActivity.class,DemoTradeActivity.class,TradeHistoryActivity.class,ResearchLabActivity.class,ImportantInfoActivity.class};
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
                @Override public boolean onFling(MotionEvent e1,MotionEvent e2,float vx,float vy){
                    if(e1==null||e2==null||switching)return false;float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();
                    if(Math.abs(dx)<dp(80)||Math.abs(dx)<Math.abs(dy)*1.25f)return false;
                    if(dx<0&&vx<-350){openPage(ImportantInfoActivity.class);return true;}
                    if(dx>0&&vx>350){openPage(TradeHistoryActivity.class);return true;}
                    return false;
                }
            });
        }
        @Override public boolean dispatchTouchEvent(MotionEvent ev){if(swipeDetector!=null)swipeDetector.onTouchEvent(ev);return super.dispatchTouchEvent(ev);}
        private void openPage(Class<?> cls){if(switching)return;switching=true;Intent i=new Intent(this,cls);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(i);overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);handler.postDelayed(()->switching=false,450);}

        private String tfLabel(String tf){for(int i=0;i<TF_KEYS.length;i++)if(TF_KEYS[i].equals(tf))return TF_NAMES[i];return tf;}
        private String pct(double v){return String.format(Locale.JAPAN,"%.1f%%",v);}
        private String signed(double v){return (v>=0?"+":"")+String.format(Locale.JAPAN,"%.2f",v);}
        private String shortError(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():(s.length()>120?s.substring(0,120):s);}
        private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
        private LinearLayout miniCard(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(10),dp(10),dp(10),dp(10));c.setBackground(makeBg(PANEL2,dp(12)));return c;}
        private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(13);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
        private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
        private View space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(dp(d),1));return s;}
        private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
        private android.graphics.drawable.Drawable makeScoreBg(){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(Color.rgb(24,34,45));g.setCornerRadius(dp(10));g.setStroke(dp(2),WARN);return g;}
        private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
        private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
        private void applySystemBarInsets(View src,View target,int l,int t,int r,int b){final int L=dp(l),T=dp(t),R=dp(r),B=dp(b);target.setPadding(L,T,R,B);src.setOnApplyWindowInsetsListener((v,i)->{int x1=0,y1=0,x2=0,y2=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets z=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());x1=z.left;y1=z.top;x2=z.right;y2=z.bottom;}else{x1=i.getSystemWindowInsetLeft();y1=i.getSystemWindowInsetTop();x2=i.getSystemWindowInsetRight();y2=i.getSystemWindowInsetBottom();}target.setPadding(L+x1,T+y1,R+x2,B+y2);return i;});src.requestApplyInsets();}
    }

    public static class TradeHistoryActivity extends Activity {
        private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(248,251,253), MUTED=Color.rgb(205,215,224), UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), WARN=Color.rgb(241,199,95), ACCENT=Color.rgb(104,170,255);
        private final TimeZone JST=TimeZone.getTimeZone("Asia/Tokyo");
        private DemoStore store;
        private LinearLayout validationContainer;
        private Spinner yearSpinner,otherMonthSpinner;
        private LinearLayout calendarContainer,otherMonthContainer,tradeContainer;
        private final ArrayList<Integer> years=new ArrayList<>();
        private GestureDetector swipeDetector; private boolean switchingPage=false;

        private static final class DayStat {
            double pnl=0; int count=0;
            void add(double v){pnl+=v;count++;}
        }

        @Override protected void onCreate(Bundle b){
            super.onCreate(b);store=new DemoStore(this);buildUi();setupSwipe();refreshAll();
        }
        @Override protected void onResume(){super.onResume();refreshAll();}

        private void buildUi(){
            ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setBackgroundColor(BG);
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(14),dp(12),dp(28));
            sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applySystemBarInsets(sv,root,12,14,12,28);

            root.addView(buildPageNav(2),lp(-1,-2,0,0,0,6));
            root.addView(pageTitle("デモトレード 成績・履歴"),lp(-1,dp(38),0,0,0,8));

            LinearLayout review=card();review.addView(txt("チャッピーのトレード検証",16,TEXT,true));
            validationContainer=new LinearLayout(this);validationContainer.setOrientation(LinearLayout.VERTICAL);
            review.addView(validationContainer,lp(-1,-2,0,8,0,0));
            root.addView(review,lp(-1,-2,0,0,0,10));

            LinearLayout selector=card();selector.addView(txt("年別集計",16,TEXT,true));
            LinearLayout yr=new LinearLayout(this);yr.setOrientation(LinearLayout.HORIZONTAL);yr.setGravity(Gravity.CENTER_VERTICAL);
            TextView yl=txt("西暦",12,MUTED,true);yr.addView(yl,new LinearLayout.LayoutParams(dp(64),dp(48)));
            yearSpinner=new Spinner(this);yearSpinner.setBackground(makeBg(PANEL2,dp(10)));yr.addView(yearSpinner,new LinearLayout.LayoutParams(0,dp(48),1));
            selector.addView(yr,lp(-1,-2,0,8,0,0));selector.addView(txt("各月の右側に月間損益、各日に日次損益、日曜日には月〜日の週間損益を表示します。",11,MUTED,false),lp(-1,-2,0,7,0,0));
            root.addView(selector,lp(-1,-2,0,0,0,10));

            LinearLayout history=card();history.addView(txt("本日の取引履歴",16,TEXT,true));
            tradeContainer=new LinearLayout(this);tradeContainer.setOrientation(LinearLayout.VERTICAL);history.addView(tradeContainer,lp(-1,-2,0,7,0,0));
            root.addView(history,lp(-1,-2,0,0,0,10));

            calendarContainer=new LinearLayout(this);calendarContainer.setOrientation(LinearLayout.VERTICAL);root.addView(calendarContainer,lp(-1,-2,0,0,0,10));

            TextView note=txt("集計は決済時刻を日本時間で分類しています。日曜日の週合計は月曜日〜日曜日の確定損益です。",10,MUTED,false);root.addView(note);
        }

        private TextView pageTitle(String s){
            TextView t=txt(s,20,TEXT,true);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;
        }

        private LinearLayout buildPageNav(int active){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            String[] labels={"相場","デモ","成績","検証","情報"};
            Class<?>[] pages={MainActivity.class,DemoTradeActivity.class,TradeHistoryActivity.class,ResearchLabActivity.class,ImportantInfoActivity.class};
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
                @Override public boolean onFling(MotionEvent e1,MotionEvent e2,float vx,float vy){
                    if(e1==null||e2==null||switchingPage)return false;float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();
                    if(Math.abs(dx)<dp(80)||Math.abs(dx)<Math.abs(dy)*1.25f)return false;
                    if(dx<0&&vx<-350){openPage(ResearchLabActivity.class);return true;}
                    if(dx>0&&vx>350){openPage(DemoTradeActivity.class);return true;}
                    return false;
                }
            });
        }
        @Override public boolean dispatchTouchEvent(MotionEvent ev){if(swipeDetector!=null)swipeDetector.onTouchEvent(ev);return super.dispatchTouchEvent(ev);}
        private void openPage(Class<?> cls){
            if(switchingPage)return;switchingPage=true;Intent i=new Intent(this,cls);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(i);
            overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);
            new Handler(Looper.getMainLooper()).postDelayed(()->switchingPage=false,450);
        }

        private void refreshAll(){
            renderValidationSummary();
            rebuildYears();
        }

        private void renderValidationSummary(){
            if(validationContainer==null)return;
            validationContainer.removeAllViews();
            JSONArray trades=store.trades();

            int normalN=0,normalW=0,attackN=0,attackW=0;
            for(int i=0;i<trades.length();i++){
                JSONObject t=trades.optJSONObject(i);if(t==null)continue;
                boolean attack="先行攻め".equals(t.optString("entryMode",""));
                boolean win=t.optDouble("pnl",0)>0;
                if(attack){attackN++;if(win)attackW++;}
                else{normalN++;if(win)normalW++;}
            }

            LinearLayout stats=new LinearLayout(this);stats.setOrientation(LinearLayout.HORIZONTAL);
            stats.addView(validationStatCard("通常エントリー",normalN,normalW),new LinearLayout.LayoutParams(0,-2,1));
            stats.addView(space(7));
            stats.addView(validationStatCard("先行エントリー",attackN,attackW),new LinearLayout.LayoutParams(0,-2,1));
            validationContainer.addView(stats,lp(-1,-2,0,0,0,9));

            LinearLayout learning=validationBlock("学習状況",ACCENT);
            TextView learningState=txt(store.explorationActive()?"小リスク探索中":"蓄積データで検証中",15,store.explorationActive()?WARN:UP,true);
            learning.addView(learningState,lp(-1,-2,0,3,0,0));
            learning.addView(txt(store.learningProfile(),13,TEXT,true),lp(-1,-2,0,6,0,0));
            learning.addView(txt("安全条件は固定。十分な件数が集まった指標だけを少しずつ加点・減点します。",12,MUTED,false),lp(-1,-2,0,6,0,0));
            validationContainer.addView(learning,lp(-1,-2,0,0,0,8));

            JSONObject t=trades.optJSONObject(0);
            if(t==null){
                LinearLayout empty=miniCard();
                empty.addView(txt("まだ決済済み取引がありません。",14,MUTED,false));
                empty.addView(txt("決済後に、結果・原因・指標・次回対応をここへ整理して表示します。",14,TEXT,false),lp(-1,-2,0,6,0,0));
                validationContainer.addView(empty);
                return;
            }

            double pnl=t.optDouble("pnl",0);
            boolean attack="先行攻め".equals(t.optString("entryMode",""));
            String dir="long".equals(t.optString("dir"))?"ロング":"ショート";
            String reason=t.optString("reason","--");
            long min=t.optLong("durationMin",0);
            int ready=t.optInt("entryReadiness",0),agree=t.optInt("entryAgreement",0);
            int same=0,opp=0;String wanted="long".equals(t.optString("dir"))?"up":"down",opposite="up".equals(wanted)?"down":"up";
            for(String tf:DemoStore.DISPLAY_TF){
                String d=t.optString("entryTf_"+tf,"neutral");
                if(wanted.equals(d))same++;else if(opposite.equals(d))opp++;
            }

            LinearLayout result=validationBlock("結果・判定",pnl>0?UP:DOWN);
            TextView resultMain=txt((pnl>0?"利益 ":"損失 ")+signedYen(pnl)+"　"+reason,17,pnl>0?UP:DOWN,true);
            result.addView(resultMain);
            result.addView(txt((attack?"先行エントリー":"通常エントリー")+" / "+dir+" / 保有 "+min+"分",14,TEXT,true),lp(-1,-2,0,5,0,0));
            validationContainer.addView(result,lp(-1,-2,0,0,0,8));

            LinearLayout cause=validationBlock("今回の原因",WARN);
            ArrayList<String> causes=new ArrayList<>();
            if(pnl<=0){
                if(reason.contains("損切り"))causes.add("想定方向へ伸びず、損切りラインまで逆行");
                else causes.add("時間経過に対して十分な伸びが出ず、優位性が低下");
                if(min<=60)causes.add("短時間での逆行が大きい取引");
                if(agree<55)causes.add("エントリー時の一致度が低め（"+agree+"%）");
                if(opp>=2)causes.add("反対方向の時間足が"+opp+"/10あり、時間足の衝突あり");
            }else{
                causes.add(reason.contains("利確")?"想定方向への値動きが継続し、利確ラインへ到達":"利益を残した状態で決済");
                causes.add("エントリー時同方向 "+same+"/10");
            }
            for(int i=0;i<Math.min(3,causes.size());i++)cause.addView(txt("・"+causes.get(i),14,TEXT,false),lp(-1,-2,0,i==0?3:5,0,0));
            validationContainer.addView(cause,lp(-1,-2,0,0,0,8));

            LinearLayout metrics=validationBlock("検証指標",ACCENT);
            LinearLayout mr1=new LinearLayout(this);mr1.setOrientation(LinearLayout.HORIZONTAL);
            addValidationMetric(mr1,"準備度",ready+"%");
            addValidationMetric(mr1,"一致度",agree+"%");
            addValidationMetric(mr1,"同方向",same+"/10");
            metrics.addView(mr1,lp(-1,-2,0,4,0,0));
            if(attack){
                LinearLayout mr2=new LinearLayout(this);mr2.setOrientation(LinearLayout.HORIZONTAL);
                addValidationMetric(mr2,"先行点",t.optInt("entryAttackScore",0)+"");
                addValidationMetric(mr2,"全10足方向",t.optInt("entryAllTimeframeScore",0)+"%");
                addValidationMetric(mr2,"ボラ予兆",t.optInt("entryVolatilityExpansionScore",0)+"点");
                metrics.addView(mr2,lp(-1,-2,0,6,0,0));
            }
            validationContainer.addView(metrics,lp(-1,-2,0,0,0,8));

            LinearLayout indicators=validationBlock("各指標の総合分析",ACCENT);
            int tech=t.optInt("entryTechnicalScore",0);
            int pbAll=t.optInt("entryPbAllScore",0),pbLower=t.optInt("entryPbLowerScore",0),pbRisk=t.optInt("entryPbUpperRisk",0);
            String techRegime=t.optString("entryTechnicalRegime","");
            indicators.addView(txt("総合指標　技術点 "+tech+"%"+(techRegime.isEmpty()?"":" / "+techRegime),14,TEXT,true),lp(-1,-2,0,3,0,0));
            indicators.addView(txt("PB　全体 "+pbAll+"% / 下位足 "+pbLower+"% / 上位足逆風 "+pbRisk+"%",14,TEXT,true),lp(-1,-2,0,5,0,0));
            indicators.addView(txt("時間足　エントリー方向 "+same+"/10 / 反対方向 "+opp+"/10",14,TEXT,true),lp(-1,-2,0,5,0,0));
            indicators.addView(txt("PBも他のテクニカル指標と同じ一要素の一つとして総合判断します。",13,MUTED,false),lp(-1,-2,0,6,0,0));
            validationContainer.addView(indicators,lp(-1,-2,0,0,0,8));

            LinearLayout next=validationBlock("次回の対応",UP);
            String normal="通常エントリー：準備度 "+store.adaptiveReadiness()+"%以上 / 一致度 "+store.adaptiveAgreement()+"%以上"+(store.adaptiveRequireH1()?" / H1同方向必須":"");
            String early="先行エントリー：先行点 "+store.adaptiveAttackScore()+"以上 / リスク "+store.adaptiveAttackRiskPct()+"%";
            next.addView(txt(normal,14,TEXT,true),lp(-1,-2,0,3,0,0));
            next.addView(txt(early,14,TEXT,true),lp(-1,-2,0,6,0,0));
            validationContainer.addView(next);
        }

        private LinearLayout validationStatCard(String label,int n,int wins){
            LinearLayout c=miniCard();
            c.addView(txt(label,14,TEXT,true));
            String wr=n==0?"--":String.format(Locale.JAPAN,"%.1f%%",wins*100.0/n);
            TextView v=txt(n+"件　勝率 "+wr,15,n==0?MUTED:wins*2>=n?UP:WARN,true);
            c.addView(v,lp(-1,-2,0,5,0,0));
            return c;
        }

        private LinearLayout validationBlock(String title,int accent){
            LinearLayout c=miniCard();
            TextView h=txt(title,15,accent,true);c.addView(h);
            return c;
        }

        private void addValidationMetric(LinearLayout row,String label,String value){
            LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);c.setPadding(dp(4),dp(5),dp(4),dp(5));
            c.addView(txt(label,12,MUTED,false));
            TextView v=txt(value,16,TEXT,true);v.setGravity(Gravity.CENTER);c.addView(v,lp(-1,-2,0,3,0,0));
            row.addView(c,new LinearLayout.LayoutParams(0,-2,1));
        }

        private void rebuildYears(){
            int previous=selectedYear();years.clear();TreeSet<Integer> set=new TreeSet<>(Collections.reverseOrder());
            Calendar now=Calendar.getInstance(JST);set.add(now.get(Calendar.YEAR));
            JSONArray a=store.trades();for(int i=0;i<a.length();i++){JSONObject t=a.optJSONObject(i);if(t==null)continue;long tm=t.optLong("closedAt",0);if(tm<=0)continue;Calendar c=Calendar.getInstance(JST);c.setTimeInMillis(tm);set.add(c.get(Calendar.YEAR));}
            years.addAll(set);ArrayList<String> labels=new ArrayList<>();for(int y:years)labels.add(y+"年");
            ArrayAdapter<String> ad=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
                @Override public View getView(int p,View v,ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);t.setTextSize(14);t.setPadding(dp(12),0,dp(12),0);return t;}
                @Override public View getDropDownView(int p,View v,ViewGroup parent){TextView t=(TextView)super.getDropDownView(p,v,parent);t.setTextColor(TEXT);t.setBackgroundColor(PANEL2);t.setTextSize(14);t.setPadding(dp(14),dp(12),dp(14),dp(12));return t;}
            };
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);yearSpinner.setAdapter(ad);
            int target=previous>0?previous:now.get(Calendar.YEAR),idx=years.indexOf(target);if(idx<0)idx=0;
            yearSpinner.setOnItemSelectedListener(null);yearSpinner.setSelection(idx,false);
            yearSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){renderYear(years.get(pos));}
                @Override public void onNothingSelected(AdapterView<?> p){}
            });
            if(!years.isEmpty())renderYear(years.get(idx));
        }

        private int selectedYear(){
            if(yearSpinner==null||yearSpinner.getSelectedItemPosition()<0||yearSpinner.getSelectedItemPosition()>=years.size())return -1;
            return years.get(yearSpinner.getSelectedItemPosition());
        }

        private void renderYear(int year){
            calendarContainer.removeAllViews();tradeContainer.removeAllViews();
            HashMap<Integer,DayStat> days=buildDayStats();
            Calendar now=Calendar.getInstance(JST);int currentYear=now.get(Calendar.YEAR),currentMonth=now.get(Calendar.MONTH)+1;

            if(year==currentYear){
                TextView currentLabel=txt("当月",14,ACCENT,true);
                calendarContainer.addView(currentLabel,lp(-1,-2,0,0,0,6));
                renderMonth(year,currentMonth,days,calendarContainer);
            }

            LinearLayout other=card();
            other.addView(txt(year==currentYear?"他の月":"月を選択",14,TEXT,true));
            ArrayList<Integer> months=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
            for(int month=12;month>=1;month--){
                if(year==currentYear&&month==currentMonth)continue;
                months.add(month);labels.add(month+"月");
            }
            otherMonthSpinner=new Spinner(this);otherMonthSpinner.setBackground(makeBg(PANEL2,dp(10)));
            ArrayAdapter<String> monthAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
                @Override public View getView(int p,View v,ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);t.setTextSize(14);t.setPadding(dp(12),0,dp(12),0);return t;}
                @Override public View getDropDownView(int p,View v,ViewGroup parent){TextView t=(TextView)super.getDropDownView(p,v,parent);t.setTextColor(TEXT);t.setBackgroundColor(PANEL2);t.setTextSize(14);t.setPadding(dp(14),dp(12),dp(14),dp(12));return t;}
            };
            monthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);otherMonthSpinner.setAdapter(monthAdapter);
            other.addView(otherMonthSpinner,lp(-1,dp(48),0,8,0,0));
            otherMonthContainer=new LinearLayout(this);otherMonthContainer.setOrientation(LinearLayout.VERTICAL);other.addView(otherMonthContainer,lp(-1,-2,0,8,0,0));
            calendarContainer.addView(other,lp(-1,-2,0,0,0,10));

            if(!months.isEmpty()){
                otherMonthSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                    @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){
                        if(pos<0||pos>=months.size())return;
                        otherMonthContainer.removeAllViews();
                        renderMonth(year,months.get(pos),days,otherMonthContainer);
                    }
                    @Override public void onNothingSelected(AdapterView<?> p){}
                });
                otherMonthSpinner.setSelection(0,false);
                renderMonth(year,months.get(0),days,otherMonthContainer);
            }
            renderTradeHistoryToday();
        }

        private HashMap<Integer,DayStat> buildDayStats(){
            HashMap<Integer,DayStat> map=new HashMap<>();JSONArray a=store.trades();
            for(int i=0;i<a.length();i++){
                JSONObject t=a.optJSONObject(i);if(t==null)continue;long tm=t.optLong("closedAt",0);if(tm<=0)continue;
                Calendar c=Calendar.getInstance(JST);c.setTimeInMillis(tm);int key=dayKey(c);
                DayStat s=map.get(key);if(s==null){s=new DayStat();map.put(key,s);}s.add(t.optDouble("pnl",0));
            }
            return map;
        }

        private void renderMonth(int year,int month,HashMap<Integer,DayStat> days,LinearLayout target){
            Calendar c=Calendar.getInstance(JST);c.clear();c.set(year,month-1,1,12,0,0);int max=c.getActualMaximum(Calendar.DAY_OF_MONTH);
            double monthPnl=0;int monthTrades=0;
            for(int day=1;day<=max;day++){c.set(Calendar.DAY_OF_MONTH,day);DayStat st=days.get(dayKey(c));if(st!=null){monthPnl+=st.pnl;monthTrades+=st.count;}}
            LinearLayout box=card();
            LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);
            TextView ml=txt(month+"月",16,TEXT,true);head.addView(ml,new LinearLayout.LayoutParams(0,-2,1));
            TextView mp=txt("月損益 "+signedYen(monthPnl)+"  / "+monthTrades+"回",13,monthPnl>0?UP:monthPnl<0?DOWN:MUTED,true);mp.setGravity(Gravity.RIGHT);head.addView(mp,new LinearLayout.LayoutParams(0,-2,2));box.addView(head);
            for(int day=1;day<=max;day++){
                c.set(Calendar.DAY_OF_MONTH,day);DayStat st=days.get(dayKey(c));double dpnl=st==null?0:st.pnl;int count=st==null?0:st.count;boolean sunday=c.get(Calendar.DAY_OF_WEEK)==Calendar.SUNDAY;
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(5),dp(7),dp(5),dp(7));
                if(sunday)row.setBackground(makeBg(Color.rgb(30,43,57),dp(8)));
                TextView dl=txt(day+"日（"+dow(c.get(Calendar.DAY_OF_WEEK))+"）",11,sunday?ACCENT:TEXT,sunday);row.addView(dl,new LinearLayout.LayoutParams(0,-2,1));
                TextView dv=txt((count>0?signedYen(dpnl):"¥0")+(count>0?"  "+count+"回":""),11,dpnl>0?UP:dpnl<0?DOWN:MUTED,true);dv.setGravity(Gravity.RIGHT);row.addView(dv,new LinearLayout.LayoutParams(0,-2,1));
                if(sunday){double weekly=weekTotal(c,days);TextView w=txt("週計 "+signedYen(weekly),11,weekly>0?UP:weekly<0?DOWN:WARN,true);w.setGravity(Gravity.RIGHT);row.addView(w,new LinearLayout.LayoutParams(0,-2,1));}
                box.addView(row,lp(-1,-2,0,4,0,0));
            }
            target.addView(box,lp(-1,-2,0,0,0,10));
        }

        private double weekTotal(Calendar sunday,HashMap<Integer,DayStat> days){
            Calendar c=(Calendar)sunday.clone();c.add(Calendar.DAY_OF_MONTH,-6);double sum=0;
            for(int i=0;i<7;i++){DayStat s=days.get(dayKey(c));if(s!=null)sum+=s.pnl;c.add(Calendar.DAY_OF_MONTH,1);}
            return sum;
        }

        private void renderTradeHistoryToday(){
            JSONArray a=store.trades();int shown=0;
            Calendar now=Calendar.getInstance(JST);int todayKey=dayKey(now);
            for(int i=0;i<a.length();i++){
                JSONObject t=a.optJSONObject(i);if(t==null)continue;long tm=t.optLong("closedAt",0);if(tm<=0)continue;
                Calendar cal=Calendar.getInstance(JST);cal.setTimeInMillis(tm);if(dayKey(cal)!=todayKey)continue;
                double v=t.optDouble("pnl",0),en=t.optDouble("entry",Double.NaN),ex=t.optDouble("exit",Double.NaN);
                boolean isLong="long".equals(t.optString("dir"));String dir=isLong?"ロング":"ショート";
                double pips=(Double.isNaN(en)||Double.isNaN(ex))?Double.NaN:(isLong?ex-en:en-ex)*100.0;
                LinearLayout box=miniCard();box.setPadding(dp(10),dp(10),dp(10),dp(10));
                LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.HORIZONTAL);h.setGravity(Gravity.CENTER_VERTICAL);
                TextView left=txt(formatTime(tm)+"  "+dir,14,TEXT,true);h.addView(left,new LinearLayout.LayoutParams(0,-2,2));
                TextView pv=txt(signedYen(v),14,v>0?UP:v<0?DOWN:TEXT,true);pv.setGravity(Gravity.RIGHT);h.addView(pv,new LinearLayout.LayoutParams(0,-2,1));box.addView(h);
                String priceLine="建値 "+price(en)+" → 決済 "+price(ex);
                box.addView(txt(priceLine,14,TEXT,false),lp(-1,-2,0,7,0,0));
                String pipText=Double.isNaN(pips)?"-- pips":String.format(Locale.JAPAN,"%+.1f pips",pips);
                int pipColor=Double.isNaN(pips)?MUTED:pips>0?UP:pips<0?DOWN:TEXT;
                LinearLayout resultRow=new LinearLayout(this);resultRow.setOrientation(LinearLayout.HORIZONTAL);resultRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView reasonView=txt(t.optString("reason",""),14,MUTED,true);resultRow.addView(reasonView,new LinearLayout.LayoutParams(0,-2,1));
                TextView pipsView=txt(pipText,14,pipColor,true);pipsView.setGravity(Gravity.RIGHT);resultRow.addView(pipsView,new LinearLayout.LayoutParams(0,-2,1));
                box.addView(resultRow,lp(-1,-2,0,6,0,0));
                if(t.has("durationMin"))box.addView(txt("保有時間 "+t.optLong("durationMin")+"分",14,MUTED,false),lp(-1,-2,0,5,0,0));
                tradeContainer.addView(box,lp(-1,-2,0,5,0,0));shown++;
            }
            if(shown==0)tradeContainer.addView(txt("本日の決済済み取引はありません。",14,MUTED,false),lp(-1,-2,0,7,0,0));
        }

        private int dayKey(Calendar c){return c.get(Calendar.YEAR)*10000+(c.get(Calendar.MONTH)+1)*100+c.get(Calendar.DAY_OF_MONTH);}
        private String dow(int d){String[] x={"","日","月","火","水","木","金","土"};return d>=1&&d<=7?x[d]:"";}
        private String formatTime(long t){SimpleDateFormat f=new SimpleDateFormat("M/d HH:mm",Locale.JAPAN);f.setTimeZone(JST);return f.format(new Date(t));}
        private String signedYen(double v){if(Math.abs(v)<.5)return "¥0";return (v>0?"+¥":"-¥")+String.format(Locale.JAPAN,"%,.0f",Math.abs(v));}
        private String yen(double v){return (v<0?"-¥":"¥")+String.format(Locale.JAPAN,"%,.0f",Math.abs(v));}
        private String price(double v){return Double.isNaN(v)?"--":String.format(Locale.JAPAN,"%.3f",v);}

        private void applySystemBarInsets(View insetSource,View target,int leftDp,int topDp,int rightDp,int bottomDp){
            final int baseLeft=dp(leftDp),baseTop=dp(topDp),baseRight=dp(rightDp),baseBottom=dp(bottomDp);target.setPadding(baseLeft,baseTop,baseRight,baseBottom);
            insetSource.setOnApplyWindowInsetsListener((v,insets)->{int left=0,top=0,right=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;}else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}target.setPadding(baseLeft+left,baseTop+top,baseRight+right,baseBottom+bottom);return insets;});insetSource.requestApplyInsets();
        }
        private TextView metricSmall(LinearLayout parent,String label,String value){LinearLayout c=miniCard();c.addView(txt(label,9,MUTED,false));TextView v=txt(value,11,TEXT,true);c.addView(v,lp(-1,-2,0,3,0,0));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));parent.addView(space(4));return v;}
        private LinearLayout miniCard(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(7),dp(7),dp(7),dp(7));c.setBackground(makeBg(PANEL2,dp(10)));return c;}
        private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
        private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(11);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
        private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
        private View space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(dp(d),1));return s;}
        private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
        private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
        private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    }





}
