package com.konchan.chappyfx;

import android.content.*;
import org.json.*;
import java.text.*;
import java.util.*;

/**
 * ResearchLabEngine
 * - Historical screening selects useful components without future leakage.
 * - Selected strategies run as independent paper traders on newly confirmed candles.
 * - No real order is ever sent.
 */
final class ResearchLabEngine {
    private static final int MIN_UNITS=1000;
    private static final String PREF="research_live_v2";
    private static final long RESCREEN_MS=6L*60L*60L*1000L;
    private static final int MAX_EVENTS=20;
    private static final int PARAM_VERSION=6;
    private static final String[] TFS={"M5","M15","M30","H1","H2","H4","H8","D","W","MN"};
    private static final String[] TF_NAMES={"5分","15分","30分","1時間","2時間","4時間","8時間","日足","週足","月足"};
    private static final String[] COMP={
        "RCI","MACD","RSI","ストキャス","EMA","ボリンジャー","ATR大実体","ドンチャン",
        "ローソク足反転","ADX/DMI","CCI","Williams %R","ROC","SMAクロス","ピークボトム","上位足PB/更新予測"
    };

    static final class UiSnapshot {
        final String status,top3,champion,regimes,log;
        UiSnapshot(String status,String top3,String champion,String regimes,String log){
            this.status=status;this.top3=top3;this.champion=champion;this.regimes=regimes;this.log=log;
        }
    }

    private static final class Candidate {
        final String tf,key,name,components,exitMode; final int[] ids;
        Candidate(String tf,int[] ids){this(tf,ids,"標準ATR","");}
        Candidate(String tf,int[] ids,String exitMode,String suffix){
            this.tf=tf;this.ids=ids;this.exitMode=exitMode;
            StringBuilder k=new StringBuilder(tf),n=new StringBuilder();
            for(int id:ids){k.append('_').append(id);if(n.length()>0)n.append(" + ");n.append(COMP[id]);}
            if(suffix!=null&&!suffix.isEmpty())k.append(suffix);
            this.key=k.toString();this.components=n.toString()+(suffix==null||suffix.isEmpty()?"":" / 出口:"+exitMode);this.name=ids.length+"要素手法";
        }
    }
    private static final class PairPerf {
        int a,b,trades,wins;double gp,gl,pips,score;
    }
    private static final class Rank {
        String key,tf,name,components,regime;int trades,wins,openDir;double pips,pf,wr,recent,maxDd,score;
    }

    static synchronized void step(Context ctx,Map<String,List<MarketEngine.Candle>> raw){
        if(ctx==null||raw==null)return;
        SharedPreferences p=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONObject states=obj(p.getString("states","{}"));
        JSONArray events=arr(p.getString("events","[]"));
        long now=System.currentTimeMillis(),lastScreen=p.getLong("last_screen",0);
        boolean screenDue=lastScreen==0||now-lastScreen>=RESCREEN_MS;
        SharedPreferences.Editor ed=p.edit();
        if(p.getInt("param_version",0)!=PARAM_VERSION){ed.putInt("param_version",PARAM_VERSION).remove("champion");addEvent(events,"標準設定値へ統一。新パラメータの成績を再集計します。");}
        ResearchHistoryDb history=new ResearchHistoryDb(ctx.getApplicationContext());
        int processed=0,openCount=0;
        PeakBottomEngine.MultiTimeframe pbAll=PeakBottomEngine.analyzeAll(raw);

        for(int t=0;t<TFS.length;t++){
            String tf=TFS[t];List<MarketEngine.Candle> rows=raw.get(tf);
            if(rows==null||rows.size()<70)continue;
            int idx=rows.size()-2; // latest bar can still be forming; use the previous bar as confirmed.
            if(idx<60)continue;
            long barTime=rows.get(idx).timeMs;
            int[] top=screenDue||!p.contains("top_"+tf)?screenTopComponents(rows):parseTop(p.getString("top_"+tf,""));
            if(top.length<6)top=defaultTop();
            if(screenDue)ed.putString("top_"+tf,joinInts(top));

            if(p.getLong("bar_"+tf,0)==barTime)continue;
            String regime=regime(rows,idx),volatility=volatilityRegime(rows,idx);
            int higherPbBias=pbAll.higherBiasFor(tf);int pbUpdateBias=PeakBottomEngine.updateBiasFor(tf,raw,pbAll);int[] sig=signals(rows,idx,higherPbBias,pbUpdateBias);
            List<Candidate> pool=candidates(tf,top);
            for(Candidate c:pool){
                JSONObject s=states.optJSONObject(c.key);
                try{
                    if(s==null||s.optInt("paramVersion",0)!=PARAM_VERSION){s=new JSONObject();s.put("paramVersion",PARAM_VERSION);}
                    if(!s.has("tf")){s.put("tf",tf);s.put("name",c.name);s.put("components",c.components);}
                    if(!s.has("exitMode"))s.put("exitMode",c.exitMode);
                    boolean closed=updateOpen(s,c,rows,idx,sig,regime,volatility,barTime,events,history);
                    if(!closed&&s.optInt("open",0)==0){
                        int dir=candidateSignal(sig,c.ids,regime);
                        if(dir!=0&&volatilityCompatible(c,volatility,regime))open(s,dir,rows,idx,regime,volatility,barTime);
                    }
                    if(s.optInt("open",0)!=0)openCount++;
                    states.put(c.key,s);
                }catch(Exception ignored){}
            }
            ed.putLong("bar_"+tf,barTime);processed++;
        }

        if(screenDue){
            ed.putLong("last_screen",now);
            addEvent(events,"候補再選定：全2要素を比較し、上位要素から3〜6要素候補を更新しました。");
        }
        List<Rank> ranking=ranking(states);
        String prevChampion=p.getString("champion","");
        String newChampion="";
        for(Rank r:ranking){if(r.trades>=20){newChampion=r.key;break;}}
        if(!newChampion.isEmpty()&&!newChampion.equals(prevChampion)){
            Rank r=findRank(ranking,newChampion);
            if(r!=null)addEvent(events,"現在トップ交代："+tfName(r.tf)+" / "+r.components+"（"+r.trades+"取引、勝率"+pct(r.wr)+"）");
            ed.putString("champion",newChampion);
        }
        try{
            ed.putString("states",states.toString()).putString("events",trimEvents(events).toString()).putLong("updated",now)
              .putInt("open_count",openCount).putInt("last_processed_tf",processed).apply();
        }catch(Exception ignored){}
        try{history.close();}catch(Exception ignored){}
    }

    private static boolean updateOpen(JSONObject s,Candidate c,List<MarketEngine.Candle> rows,int idx,int[] sig,String currentRegime,String currentVolatility,long barTime,JSONArray events,ResearchHistoryDb history)throws Exception{
        int dir=s.optInt("open",0);if(dir==0)return false;
        MarketEngine.Candle bar=rows.get(idx);int bars=s.optInt("bars",0)+1;s.put("bars",bars);
        double entry=s.optDouble("entry",bar.c),target=s.optDouble("target",entry),stop=s.optDouble("stop",entry),exit=Double.NaN;
        String mode=s.optString("exitMode",c.exitMode),reason="";double a=atr(rows,14,idx);if(a<=0)a=Math.max(.03,bar.h-bar.l);
        boolean targetEnabled="標準ATR".equals(mode)||"短期固定".equals(mode);
        boolean stopHit=dir>0?bar.l<=stop:bar.h>=stop;
        boolean targetHit=targetEnabled&&(dir>0?bar.h>=target:bar.l<=target);
        boolean ambiguous=stopHit&&targetHit;
        if(ambiguous){exit=stop;reason="同一足で利確/損切り両到達→損切り優先";}
        else if(stopHit){exit=stop;reason="損切り";}
        else if(targetHit){exit=target;reason="利確";}
        else{
            int nowSignal=candidateSignal(sig,c.ids,currentRegime);
            int maxBars="シグナル追従".equals(mode)?8:"トレーリング".equals(mode)?6:3;
            if(nowSignal==-dir){exit=bar.c;reason="反対シグナル";}
            else if(bars>=maxBars){exit=bar.c;reason=maxBars+"本経過";}
            else if("トレーリング".equals(mode)){
                double trail=s.optDouble("trail",stop),next=dir>0?Math.max(trail,bar.c-a*.80):Math.min(trail,bar.c+a*.80);
                s.put("trail",next).put("stop",next);
            }
        }
        if(Double.isNaN(exit))return false;
        String entryRegime=s.optString("entryRegime","不明"),entryVolatility=s.optString("entryVolatility","通常ボラ");
        boolean surge="急拡大".equals(entryVolatility)||"急拡大".equals(currentVolatility);
        boolean highVol=surge||"高ボラ".equals(entryVolatility)||"高ボラ".equals(currentVolatility);
        double spreadMult=surge?2.5:highVol?1.6:1.0;
        double execSpread=DemoStore.SPREAD*spreadMult;
        double slippage=(surge?.0015:highVol?.0008:"低ボラ".equals(entryVolatility)?.0002:.0003)+Math.min(.0010,Math.max(0,a)*.005);
        double pnl=dir*(exit-entry)-execSpread-slippage,pips=pnl/0.01;int units=Math.max(MIN_UNITS,s.optInt("units",MIN_UNITS));double pnlYen=pnl*units;
        int trades=s.optInt("trades",0)+1,wins=s.optInt("wins",0)+(pnl>0?1:0);
        double gp=s.optDouble("gp",0)+(pnl>0?pnl:0),gl=s.optDouble("gl",0)+(pnl<0?-pnl:0),sum=s.optDouble("pips",0)+pips;
        double equity=s.optDouble("equity",0)+pips,peak=Math.max(s.optDouble("peak",0),equity),dd=Math.max(0,peak-equity),maxDd=Math.max(s.optDouble("maxdd",0),dd);
        String recent=s.optString("recent","")+(pnl>0?"1":"0");if(recent.length()>30)recent=recent.substring(recent.length()-30);
        s.put("trades",trades).put("wins",wins).put("gp",gp).put("gl",gl).put("pips",sum).put("equity",equity).put("peak",peak).put("maxdd",maxDd).put("recent",recent).put("lastUnits",units).put("lastPnlYen",pnlYen).put("pnlYen",s.optDouble("pnlYen",0)+pnlYen);
        if(ambiguous)s.put("ambiguous",s.optInt("ambiguous",0)+1);
        recordRegime(s,entryRegime,pnl,pips);recordVolatility(s,entryVolatility,pnl,pips);recordDay(s,pnl,pips);
        long entryTime=s.optLong("entryTime",barTime);
        try{history.insertTrade(c.key,c.tf,c.components,mode,dir,entryTime,barTime,entry,exit,pips,pnl>0,entryRegime+" / "+entryVolatility,reason,execSpread,slippage,ambiguous);}catch(Exception ignored){}
        s.put("open",0).put("bars",0).put("lastExit",barTime).put("lastReason",reason);
        if(trades==20)addEvent(events,"信頼度判定開始："+tfName(c.tf)+" / "+c.components+" が20取引に到達しました。");
        if(trades==60)addEvent(events,"信頼手法入り："+tfName(c.tf)+" / "+c.components+" が60取引に到達しました。");
        return true;
    }

    private static void open(JSONObject s,int dir,List<MarketEngine.Candle> rows,int idx,String regime,String volatility,long barTime)throws Exception{
        double entry=rows.get(idx).c,a=atr(rows,14,idx);if(a<=0)a=Math.max(.03,rows.get(idx).h-rows.get(idx).l);
        String mode=s.optString("exitMode","標準ATR");double target=entry+dir*a*1.30,stop=entry-dir*a*1.00;
        if("短期固定".equals(mode)){target=entry+dir*a*.80;stop=entry-dir*a*.80;}
        else if("トレーリング".equals(mode)){stop=entry-dir*a*1.00;target=entry;}
        else if("シグナル追従".equals(mode)){stop=entry-dir*a*1.20;target=entry;}
        s.put("open",dir).put("entry",entry).put("target",target).put("stop",stop).put("trail",stop).put("units",MIN_UNITS)
         .put("bars",0).put("entryTime",barTime).put("entryRegime",regime).put("entryVolatility",volatility);
    }

    private static void recordRegime(JSONObject s,String regime,double pnl,double pips)throws Exception{
        JSONObject rg=s.optJSONObject("regimes");if(rg==null)rg=new JSONObject();
        JSONObject x=rg.optJSONObject(regime);if(x==null)x=new JSONObject();
        x.put("trades",x.optInt("trades",0)+1).put("wins",x.optInt("wins",0)+(pnl>0?1:0)).put("pips",x.optDouble("pips",0)+pips);
        rg.put(regime,x);s.put("regimes",rg);
    }
    private static void recordVolatility(JSONObject s,String volatility,double pnl,double pips)throws Exception{
        JSONObject all=s.optJSONObject("volatilityRegimes");if(all==null)all=new JSONObject();
        JSONObject x=all.optJSONObject(volatility);if(x==null)x=new JSONObject();
        x.put("trades",x.optInt("trades",0)+1).put("wins",x.optInt("wins",0)+(pnl>0?1:0)).put("pips",x.optDouble("pips",0)+pips);
        all.put(volatility,x);s.put("volatilityRegimes",all);
    }
    private static void recordDay(JSONObject s,double pnl,double pips)throws Exception{
        String day=new SimpleDateFormat("yyyyMMdd",Locale.JAPAN).format(new Date());
        if(!day.equals(s.optString("day",""))){s.put("day",day).put("dayTrades",0).put("dayWins",0).put("dayPips",0);}
        s.put("dayTrades",s.optInt("dayTrades",0)+1).put("dayWins",s.optInt("dayWins",0)+(pnl>0?1:0)).put("dayPips",s.optDouble("dayPips",0)+pips);
    }

    private static int[] screenTopComponents(List<MarketEngine.Candle> rows){
        ArrayList<PairPerf> perf=new ArrayList<>();PairPerf[][] map=new PairPerf[COMP.length][COMP.length];
        for(int a=0;a<COMP.length;a++)for(int b=a+1;b<COMP.length;b++){PairPerf x=new PairPerf();x.a=a;x.b=b;map[a][b]=x;perf.add(x);}
        for(int i=60;i+3<rows.size()-1;i++){
            String rg=regime(rows,i);int[] s=signals(rows,i,0,0);double next=rows.get(i+3).c,entry=rows.get(i).c;
            for(PairPerf x:perf){
                int dir=candidateSignal(s,new int[]{x.a,x.b},rg);if(dir==0)continue;
                double pnl=dir*(next-entry)-DemoStore.SPREAD;x.trades++;if(pnl>0){x.wins++;x.gp+=pnl;}else x.gl-=pnl;x.pips+=pnl/0.01;
            }
        }
        for(PairPerf x:perf){
            if(x.trades==0){x.score=-999;continue;}double wr=100.0*x.wins/x.trades,pf=x.gl<=1e-9?(x.gp>0?5:0):Math.min(5,x.gp/x.gl);
            double sample=.55+.45*Math.min(1.0,x.trades/30.0);x.score=(wr*.55+Math.min(100,pf*40)*.30+Math.max(0,50+(x.pips/x.trades)*5)*.15)*sample;
        }
        perf.sort((a,b)->Double.compare(b.score,a.score));LinkedHashSet<Integer> ids=new LinkedHashSet<>();
        for(PairPerf x:perf){if(x.trades<6)continue;ids.add(x.a);ids.add(x.b);if(ids.size()>=6)break;}
        for(int i=0;ids.size()<6&&i<COMP.length;i++)ids.add(i);
        int[] out=new int[6];int n=0;for(int id:ids){if(n==6)break;out[n++]=id;}return out;
    }

    private static List<Candidate> candidates(String tf,int[] top){
        ArrayList<Candidate> out=new ArrayList<>();
        for(int a=0;a<COMP.length;a++)for(int b=a+1;b<COMP.length;b++)out.add(new Candidate(tf,new int[]{a,b}));
        for(int a=0;a<top.length;a++)for(int b=a+1;b<top.length;b++)addExitVariants(out,tf,new int[]{top[a],top[b]},false);
        for(int size=3;size<=6;size++)choose(tf,top,size,0,new int[size],0,out);
        return out;
    }
    private static void addExitVariants(List<Candidate> out,String tf,int[] ids,boolean includeStandard){
        if(includeStandard)out.add(new Candidate(tf,ids));
        out.add(new Candidate(tf,ids,"短期固定","_e1"));
        out.add(new Candidate(tf,ids,"トレーリング","_e2"));
        out.add(new Candidate(tf,ids,"シグナル追従","_e3"));
    }
    private static void choose(String tf,int[] src,int size,int start,int[] pick,int depth,List<Candidate> out){
        if(depth==size){addExitVariants(out,tf,Arrays.copyOf(pick,pick.length),true);return;}
        for(int i=start;i<=src.length-(size-depth);i++){pick[depth]=src[i];choose(tf,src,size,i+1,pick,depth+1,out);}
    }
    private static boolean trendComponent(int id){return id==1||id==4||id==6||id==7||id==9||id==12||id==13||id==14||id==15;}
    private static boolean timingComponent(int id){return id==0||id==2||id==3||id==5||id==8||id==10||id==11;}
    private static boolean breakoutComponent(int id){return id==1||id==6||id==7||id==9||id==12||id==14||id==15;}

    private static int candidateSignal(int[] sig,int[] ids,String regime){
        int up=0,down=0,trendUp=0,trendDown=0,timingUp=0,timingDown=0,breakUp=0,breakDown=0;
        for(int id:ids){
            if(id<0||id>=sig.length)continue;int v=sig[id];
            if(v>0)up++;else if(v<0)down++;
            if(trendComponent(id)){if(v>0)trendUp++;else if(v<0)trendDown++;}
            if(timingComponent(id)){if(v>0)timingUp++;else if(v<0)timingDown++;}
            if(breakoutComponent(id)){if(v>0)breakUp++;else if(v<0)breakDown++;}
        }
        int need=ids.length/2+1,dir=up>=need&&down==0?1:down>=need&&up==0?-1:0;
        if(dir==0)return 0;
        boolean upTrend="上昇トレンド".equals(regime),downTrend="下降トレンド".equals(regime),range="レンジ".equals(regime);
        if(upTrend){
            if(dir<0)return 0; // 強い上昇中のオシレーター逆張りは採用しない。
            return trendUp>0&&trendDown==0?1:0;
        }
        if(downTrend){
            if(dir>0)return 0;
            return trendDown>0&&trendUp==0?-1:0;
        }
        if(range){
            // レンジではオシレーター/バンド/反転足を中心にする。
            if(dir>0&&timingUp>0)return 1;if(dir<0&&timingDown>0)return -1;
            // ただし複数のブレイク系が一致したらレンジ離脱候補として残す。
            if(dir>0&&breakUp>=2)return 1;if(dir<0&&breakDown>=2)return -1;
            return 0;
        }
        return dir;
    }

    private static int[] signals(List<MarketEngine.Candle> r,int i,int higherPbBias,int pbUpdateBias){
        int[] s=new int[COMP.length];String rg=regime(r,i);
        boolean upTrend="上昇トレンド".equals(rg),downTrend="下降トレンド".equals(rg),range="レンジ".equals(rg);

        double r5=rci(r,5,i),r10=rci(r,10,i),r20=rci(r,20,i),r5p=rci(r,5,i-1),r10p=rci(r,10,i-1);
        if(upTrend)s[0]=r5>r5p&&(r5>r10||r10>=r20)&&r5>-80?1:0;
        else if(downTrend)s[0]=r5<r5p&&(r5<r10||r10<=r20)&&r5<80?-1:0;
        else s[0]=(r5p<=-80&&r5>r5p&&r5>r10)||(r5p<=r10p&&r5>r10&&r5<20)?1:
                  (r5p>=80&&r5<r5p&&r5<r10)||(r5p>=r10p&&r5<r10&&r5>-20)?-1:0;

        double mh=macdHist(r,i),mhp=macdHist(r,i-1);
        if(upTrend)s[1]=mh>=0&&mh>=mhp?1:0;
        else if(downTrend)s[1]=mh<=0&&mh<=mhp?-1:0;
        else s[1]=mhp<=0&&mh>0?1:mhp>=0&&mh<0?-1:0;

        double rs=rsi(r,14,i),rsp=rsi(r,14,i-1);
        if(upTrend)s[2]=rs>=45&&rs<=70&&rs>=rsp?1:0;
        else if(downTrend)s[2]=rs<=55&&rs>=30&&rs<=rsp?-1:0;
        else s[2]=rsp<35&&rs>rsp?1:rsp>65&&rs<rsp?-1:0;

        double k=stoch(r,14,i),d=stochD(r,14,3,i),kp=stoch(r,14,i-1),dp=stochD(r,14,3,i-1);
        if(upTrend)s[3]=kp<=dp&&k>d&&k<55?1:0;
        else if(downTrend)s[3]=kp>=dp&&k<d&&k>45?-1:0;
        else s[3]=kp<=dp&&k>d&&k<35?1:kp>=dp&&k<d&&k>65?-1:0;

        double e5=ema(r,5,i),e25=ema(r,25,i),e75=ema(r,75,i);s[4]=e5>e25&&e25>e75?1:e5<e25&&e25<e75?-1:0;

        double mid=sma(r,25,i),sd=std(r,25,i),cl=r.get(i).c,prevCl=r.get(i-1).c;
        double pmid=sma(r,25,i-1),psd=std(r,25,i-1);
        if(range){
            boolean reInLow=prevCl<pmid-2*psd&&cl>mid-2*sd;
            boolean reInHigh=prevCl>pmid+2*psd&&cl<mid+2*sd;
            s[5]=reInLow?1:reInHigh?-1:0;
        }else s[5]=upTrend&&cl>mid+2*sd?1:downTrend&&cl<mid-2*sd?-1:0;

        double a=atr(r,14,i-1),barRange=r.get(i).h-r.get(i).l,body=Math.abs(r.get(i).c-r.get(i).o);
        s[6]=!range&&a>0&&barRange>a*1.4&&body>barRange*.6?(r.get(i).c>r.get(i).o?1:-1):0;

        if(i>=21){double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int j=i-20;j<i;j++){hi=Math.max(hi,r.get(j).h);lo=Math.min(lo,r.get(j).l);}s[7]=cl>hi?1:cl<lo?-1:0;}

        int candle=bullReversal(r,i)?1:bearReversal(r,i)?-1:0;
        s[8]=range?candle:(upTrend&&candle>0?1:downTrend&&candle<0?-1:0);

        double[] dm=dmi143(r,i);s[9]=dm[0]>=20?(dm[1]>dm[2]?1:dm[2]>dm[1]?-1:0):0;

        double cc=cci(r,20,i),ccp=cci(r,20,i-1);
        if(range)s[10]=ccp<-100&&cc>-100?1:ccp>100&&cc<100?-1:0;
        else s[10]=cc>100?1:cc<-100?-1:0;

        double w=williamsR(r,14,i),wp=williamsR(r,14,i-1);
        if(upTrend)s[11]=wp<-80&&w>wp?1:0;
        else if(downTrend)s[11]=wp>-20&&w<wp?-1:0;
        else s[11]=w<-80&&w>wp?1:w>-20&&w<wp?-1:0;

        double ro=roc(r,12,i),rop=roc(r,12,i-1);s[12]=ro>0&&ro>rop?1:ro<0&&ro<rop?-1:0;

        double s5=sma(r,5,i),s25=sma(r,25,i),s75=sma(r,75,i);s[13]=s5>s25&&s25>s75?1:s5<s25&&s25<s75?-1:0;

        s[14]=PeakBottomEngine.waveDirectionAt(r,i);
        s[15]=pbUpdateBias!=PeakBottomEngine.NEUTRAL?pbUpdateBias:higherPbBias;
        return s;
    }

    private static String regime(List<MarketEngine.Candle> r,int i){
        double a=atr(r,14,i),e5=ema(r,5,i),e25=ema(r,25,i),e75=ema(r,75,i);double[] dm=dmi143(r,i);
        if(a>0&&dm[0]>=20&&e5>e25&&e25>e75&&e5-e25>a*.12&&dm[1]>dm[2])return "上昇トレンド";
        if(a>0&&dm[0]>=20&&e5<e25&&e25<e75&&e25-e5>a*.12&&dm[2]>dm[1])return "下降トレンド";
        return "レンジ";
    }
    private static String volatilityRegime(List<MarketEngine.Candle> r,int i){
        double a=atr(r,14,i);if(a<=0)return "通常ボラ";
        double sum=0;int n=0;for(int j=Math.max(20,i-30);j<i;j++){double x=atr(r,14,j);if(x>0){sum+=x;n++;}}
        double avg=n==0?a:sum/n,ratio=avg<=1e-9?1.0:a/avg;
        MarketEngine.Candle cur=r.get(i),prev=r.get(i-1);double tr=Math.max(cur.h-cur.l,Math.max(Math.abs(cur.h-prev.c),Math.abs(cur.l-prev.c)));
        if(ratio>=1.60||tr>=a*2.20)return "急拡大";
        if(ratio>=1.25)return "高ボラ";
        if(ratio<.75)return "低ボラ";
        return "通常ボラ";
    }
    private static boolean volatilityCompatible(Candidate c,String volatility,String regime){
        if("急拡大".equals(volatility))return false;
        boolean trend=false,timing=false,breakout=false;
        for(int id:c.ids){trend|=trendComponent(id);timing|=timingComponent(id);breakout|=breakoutComponent(id);}
        if("上昇トレンド".equals(regime)||"下降トレンド".equals(regime))return trend;
        if("レンジ".equals(regime))return timing||breakout;
        if("低ボラ".equals(volatility))return timing;
        if("高ボラ".equals(volatility))return trend||breakout;
        return true;
    }

    private static List<Rank> ranking(JSONObject states){
        ArrayList<Rank> out=new ArrayList<>();Iterator<String> it=states.keys();
        while(it.hasNext()){String key=it.next();JSONObject s=states.optJSONObject(key);if(s==null||s.optInt("paramVersion",0)!=PARAM_VERSION)continue;int trades=s.optInt("trades",0);if(trades<=0)continue;
            Rank r=new Rank();r.key=key;r.tf=s.optString("tf","");r.name=s.optString("name","");r.components=s.optString("components","");r.trades=trades;r.wins=s.optInt("wins",0);r.openDir=s.optInt("open",0);
            r.pips=s.optDouble("pips",0);r.maxDd=s.optDouble("maxdd",0);r.wr=100.0*r.wins/trades;double gl=s.optDouble("gl",0),gp=s.optDouble("gp",0);r.pf=gl<=1e-9?(gp>0?5:0):Math.min(5,gp/gl);
            String rec=s.optString("recent","");int rw=0;for(int i=0;i<rec.length();i++)if(rec.charAt(i)=='1')rw++;r.recent=rec.isEmpty()?50:100.0*rw/rec.length();
            double avg=r.pips/trades;
            // 少数取引の100%勝率を過大評価しないよう、勝率はWilson下限で評価する。
            double z=1.96,phat=r.wins/(double)trades,z2=z*z;
            double winFloor=100.0*((phat+z2/(2*trades)-z*Math.sqrt((phat*(1-phat)+z2/(4*trades))/trades))/(1+z2/trades));
            double pfScore=Math.max(0,Math.min(100,50+(r.pf-1.0)*35));
            double expectancyScore=Math.max(0,Math.min(100,50+avg*6));
            double recentWeight=Math.min(1.0,rec.length()/30.0),recentScore=50+(r.recent-50)*recentWeight;
            double ddScore=Math.max(0,100-r.maxDd*2.5);
            double maturity=Math.min(1.0,trades/30.0),sample=.30+.70*maturity;
            r.score=(winFloor*.35+pfScore*.20+expectancyScore*.20+recentScore*.15+ddScore*.10)*sample;out.add(r);
        }
        out.sort((a,b)->Double.compare(b.score,a.score));return out;
    }

    private static List<Rank> realtimeTop(List<Rank> ranks){
        ArrayList<Rank> trusted=new ArrayList<>(),checking=new ArrayList<>(),mature5=new ArrayList<>();
        for(Rank r:ranks){
            if(r.trades>=60)trusted.add(r);
            if(r.trades>=20)checking.add(r);
            if(r.trades>=5)mature5.add(r);
        }
        if(trusted.size()>=3)return trusted;
        if(checking.size()>=3)return checking;
        if(mature5.size()>=3)return mature5;
        return ranks;
    }

    static UiSnapshot ui(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);JSONObject states=obj(p.getString("states","{}"));List<Rank> ranks=ranking(states);
        long updated=p.getLong("updated",0);String when=updated==0?"未開始":new SimpleDateFormat("M/d HH:mm:ss",Locale.JAPAN).format(new Date(updated));
        int trusted=0,checking=0,provisional=0;for(Rank r:ranks){if(r.trades>=60)trusted++;else if(r.trades>=20)checking++;else provisional++;}
        ResearchHistoryDb historyUi=new ResearchHistoryDb(ctx.getApplicationContext());ResearchHistoryDb.Summary hs=historyUi.summary();historyUi.close();
        String todayWr=hs.today==0?"--":pct(100.0*hs.winsToday/hs.today);
        String status="常時監視の確定足で並列デモ検証 / 最終更新 "+when+
                "\n検証中 "+p.getInt("open_count",0)+"手法・成績蓄積 "+ranks.size()+"手法"+
                "\n信頼 "+trusted+" / 検証中 "+checking+" / 暫定 "+provisional+
                "\n標準設定：現在の既定値を維持 / 仮想取引は最低1,000通貨 / PB10トップダウン / RCI5・10・20 / MACD5・20・9 / RSI14 / Stoch14・3 / DMI14・14\n使い分け：ADX/DMI＋MAでトレンド/レンジ判定 → トレンド系とオシレーター系の役割を切替"+
                "\n今日 "+hs.today+"取引・勝率 "+todayWr+"・"+signed(hs.pipsToday)+"pips / 詳細DB累計 "+hs.total+"取引"+
                (hs.ambiguous>0?"・同一足判定困難 "+hs.ambiguous+"件":"");
        List<Rank> topRanks=realtimeTop(ranks);
        String topRule=topRanks.isEmpty()?"":topRanks.get(0).trades>=60?"信頼TOP3（60取引以上）":topRanks.get(0).trades>=20?"検証中TOP3（20取引以上）":topRanks.get(0).trades>=5?"暫定TOP3（5取引以上）":"蓄積初期の暫定順位";
        StringBuilder top=new StringBuilder();
        if(!topRule.isEmpty())top.append("評価基準：").append(topRule).append(" / 実績を随時再計算\n\n");
        for(int i=0;i<Math.min(3,topRanks.size());i++){Rank r=topRanks.get(i);if(i>0)top.append("\n\n");top.append("【").append(i+1).append("位】").append(tfName(r.tf)).append(" / ").append(r.components)
            .append("\n評価 ").append(String.format(Locale.JAPAN,"%.1f",r.score)).append("・").append(r.trades).append("取引・勝率").append(pct(r.wr)).append("・PF ").append(String.format(Locale.JAPAN,"%.2f",r.pf))
            .append("・累計 ").append(signed(r.pips)).append("pips・平均 ").append(signed(r.pips/r.trades)).append("pips")
            .append("・最大DD ").append(String.format(Locale.JAPAN,"%.1f",r.maxDd)).append("pips")
            .append("\n信頼度 ").append(confidence(r.trades)).append("・直近勝率 ").append(pct(r.recent));}
        if(topRanks.isEmpty())top.append("まだリアルタイム決済実績がありません。監視を続けると自動で蓄積します。");

        String champion="現在トップ：まだ認定前（20取引以上で判定開始）";
        String champKey=p.getString("champion","");Rank ch=findRank(ranks,champKey);
        if(ch!=null)champion="現在トップ："+tfName(ch.tf)+" / "+ch.components+"  勝率"+pct(ch.wr)+"  PF "+String.format(Locale.JAPAN,"%.2f",ch.pf);
        List<Rank> contenderPool=realtimeTop(ranks);
        if(contenderPool.size()>1){Rank x=contenderPool.get(ch==contenderPool.get(0)?1:0);champion+="\n次点候補："+tfName(x.tf)+" / "+x.components+"  評価"+String.format(Locale.JAPAN,"%.1f",x.score);}

        String regimes=regimeLeaders(states);
        JSONArray ev=arr(p.getString("events","[]"));StringBuilder log=new StringBuilder();
        for(int i=ev.length()-1;i>=Math.max(0,ev.length()-5);i--){if(log.length()>0)log.append("\n");log.append("・").append(sanitizeUiText(ev.optString(i)));}
        if(log.length()==0)log.append("まだ発見ログはありません。");
        return new UiSnapshot(status,top.toString(),champion,regimes,log.toString());
    }

    private static String regimeLeaders(JSONObject states){
        StringBuilder b=new StringBuilder("【トレンド環境】");
        for(String rg:new String[]{"上昇トレンド","下降トレンド","レンジ"})b.append("\n").append(rg).append("：").append(bestRegime(states,"regimes",rg));
        b.append("\n\n【ボラ環境】");
        for(String rg:new String[]{"低ボラ","通常ボラ","高ボラ","急拡大"})b.append("\n").append(rg).append("：").append(bestRegime(states,"volatilityRegimes",rg));
        return b.toString();
    }
    private static String bestRegime(JSONObject states,String bucket,String rg){
        String best="データ不足";double bestScore=-999;Iterator<String> it=states.keys();
        while(it.hasNext()){JSONObject s=states.optJSONObject(it.next());if(s==null)continue;JSONObject all=s.optJSONObject(bucket);JSONObject x=all==null?null:all.optJSONObject(rg);if(x==null||x.optInt("trades",0)<10)continue;
            int n=x.optInt("trades"),w=x.optInt("wins");double pp=x.optDouble("pips",0),sc=100.0*w/n+Math.max(-20,Math.min(20,pp/n*3));
            if(sc>bestScore){bestScore=sc;String level=n>=60?"信頼":n>=30?"有力":"参考";best=tfName(s.optString("tf",""))+" / "+s.optString("components","")+" ("+n+"回・"+pct(100.0*w/n)+"・"+level+")";}}
        return best;
    }

    private static String confidence(int n){return n>=60?"高（信頼）":n>=20?"中（検証中）":"低（暫定）";}
    private static Rank findRank(List<Rank> r,String key){if(key==null)return null;for(Rank x:r)if(key.equals(x.key))return x;return null;}
    private static void addEvent(JSONArray a,String text){a.put(new SimpleDateFormat("M/d HH:mm",Locale.JAPAN).format(new Date())+" "+sanitizeUiText(text));}
    private static JSONArray trimEvents(JSONArray a){JSONArray b=new JSONArray();for(int i=Math.max(0,a.length()-MAX_EVENTS);i<a.length();i++)b.put(sanitizeUiText(a.optString(i)));return b;}
    private static String sanitizeUiText(String s){if(s==null)return "";return s.replace("i"+"SPEED FX","標準設定").replace("楽"+"天FX","標準設定");}
    private static String tfName(String tf){for(int i=0;i<TFS.length;i++)if(TFS[i].equals(tf))return TF_NAMES[i];return tf;}
    private static String pct(double x){return String.format(Locale.JAPAN,"%.1f%%",x);}
    private static String signed(double x){return (x>=0?"+":"")+String.format(Locale.JAPAN,"%.1f",x);}
    private static JSONObject obj(String s){try{return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    private static JSONArray arr(String s){try{return new JSONArray(s);}catch(Exception e){return new JSONArray();}}
    private static int[] defaultTop(){return new int[]{0,1,2,4,9,6};}
    private static int[] parseTop(String s){try{String[] a=s.split(",");int[] out=new int[a.length];for(int i=0;i<a.length;i++)out[i]=Integer.parseInt(a[i]);return out;}catch(Exception e){return new int[0];}}
    private static String joinInts(int[] x){StringBuilder b=new StringBuilder();for(int v:x){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}

    private static double ema(List<MarketEngine.Candle> r,int p,int end){int e=Math.min(end,r.size()-1),st=Math.max(0,e-p*5);double a=2.0/(p+1),v=r.get(st).c;for(int i=st+1;i<=e;i++)v=r.get(i).c*a+v*(1-a);return v;}
    private static double sma(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return Double.NaN;double s=0;for(int i=end-p+1;i<=end;i++)s+=r.get(i).c;return s/p;}
    private static double std(List<MarketEngine.Candle> r,int p,int end){double m=sma(r,p,end);if(Double.isNaN(m))return 0;double s=0;for(int i=end-p+1;i<=end;i++){double d=r.get(i).c-m;s+=d*d;}return Math.sqrt(s/p);}
    private static double rsi(List<MarketEngine.Candle> r,int p,int end){if(end<p)return 50;double g=0,l=0;for(int i=end-p+1;i<=end;i++){double d=r.get(i).c-r.get(i-1).c;if(d>0)g+=d;else l-=d;}return l==0?100:100-100/(1+g/l);}
    private static double stoch(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return 50;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int i=end-p+1;i<=end;i++){hi=Math.max(hi,r.get(i).h);lo=Math.min(lo,r.get(i).l);}return hi<=lo?50:100*(r.get(end).c-lo)/(hi-lo);}
    private static double stochD(List<MarketEngine.Candle> r,int kp,int dp,int end){if(end<dp-1)return 50;double z=0;for(int i=end-dp+1;i<=end;i++)z+=stoch(r,kp,i);return z/dp;}
    private static double atr(List<MarketEngine.Candle> r,int p,int end){if(end<p)return 0;double s=0;for(int i=end-p+1;i<=end;i++){MarketEngine.Candle a=r.get(i),b=r.get(i-1);s+=Math.max(a.h-a.l,Math.max(Math.abs(a.h-b.c),Math.abs(a.l-b.c)));}return s/p;}
    private static double macdHist(List<MarketEngine.Candle> r,int end){if(end<2)return 0;double af=2.0/6.0,as=2.0/21.0,ag=2.0/10.0,fast=r.get(0).c,slow=fast,sig=0,m=0;for(int i=0;i<=end;i++){double c=r.get(i).c;if(i>0){fast=c*af+fast*(1-af);slow=c*as+slow*(1-as);}m=fast-slow;sig=i==0?m:m*ag+sig*(1-ag);}return m-sig;}
    private static double rci(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return 0;int st=end-p+1;double sum=0;for(int i=0;i<p;i++){double price=r.get(st+i).c;int less=0,eq=0;for(int j=0;j<p;j++){double q=r.get(st+j).c;if(q<price)less++;else if(Double.compare(q,price)==0)eq++;}double pr=less+(eq+1)/2.0,d=(i+1)-pr;sum+=d*d;}return 100*(1-6*sum/(p*(p*p-1.0)));}
    private static double[] dmi143(List<MarketEngine.Candle> r,int end){if(end<28)return new double[]{0,0,0};double[] cur=di14(r,end);double sum=0;for(int e=end-13;e<=end;e++)sum+=di14(r,e)[2];return new double[]{sum/14.0,cur[0],cur[1]};}
    private static double[] di14(List<MarketEngine.Candle> r,int end){if(end<14)return new double[]{0,0,0};double tr=0,pd=0,md=0;for(int i=end-13;i<=end;i++){MarketEngine.Candle c=r.get(i),q=r.get(i-1);double up=c.h-q.h,dn=q.l-c.l;pd+=up>dn&&up>0?up:0;md+=dn>up&&dn>0?dn:0;tr+=Math.max(c.h-c.l,Math.max(Math.abs(c.h-q.c),Math.abs(c.l-q.c)));}if(tr<=1e-9)return new double[]{0,0,0};double plus=100*pd/tr,minus=100*md/tr,den=plus+minus,dx=den<=1e-9?0:100*Math.abs(plus-minus)/den;return new double[]{plus,minus,dx};}
    private static double cci(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return 0;double m=0;for(int i=end-p+1;i<=end;i++)m+=(r.get(i).h+r.get(i).l+r.get(i).c)/3;m/=p;double d=0;for(int i=end-p+1;i<=end;i++)d+=Math.abs((r.get(i).h+r.get(i).l+r.get(i).c)/3-m);d/=p;double tp=(r.get(end).h+r.get(end).l+r.get(end).c)/3;return d<=1e-9?0:(tp-m)/(.015*d);}
    private static double williamsR(List<MarketEngine.Candle> r,int p,int end){if(end+1<p)return -50;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int i=end-p+1;i<=end;i++){hi=Math.max(hi,r.get(i).h);lo=Math.min(lo,r.get(i).l);}return hi<=lo?-50:-100*(hi-r.get(end).c)/(hi-lo);}
    private static double roc(List<MarketEngine.Candle> r,int p,int end){if(end<p)return 0;double b=r.get(end-p).c;return Math.abs(b)<1e-9?0:100*(r.get(end).c-b)/b;}
    private static boolean bullReversal(List<MarketEngine.Candle> r,int i){if(i<1)return false;MarketEngine.Candle p=r.get(i-1),c=r.get(i);double range=Math.max(.00001,c.h-c.l),body=Math.abs(c.c-c.o),lower=Math.min(c.o,c.c)-c.l;return (p.c<p.o&&c.c>c.o&&c.o<=p.c&&c.c>=p.o)||(body<=range*.4&&lower>=range*.5);}
    private static boolean bearReversal(List<MarketEngine.Candle> r,int i){if(i<1)return false;MarketEngine.Candle p=r.get(i-1),c=r.get(i);double range=Math.max(.00001,c.h-c.l),body=Math.abs(c.c-c.o),upper=c.h-Math.max(c.o,c.c);return (p.c>p.o&&c.c<c.o&&c.o>=p.c&&c.c<=p.o)||(body<=range*.4&&upper>=range*.5);}
}
