package com.konchan.chappyfx;

import android.content.*;
import org.json.*;
import java.util.*;

public final class DemoStore {
    public static final double START=100000.0;
    public static final double SPREAD_SEN=0.2, SPREAD=0.002;
    private static final double LEGACY_SPREAD=0.012;
    private static final int SPREAD_VERSION=2, POSITION_VERSION=1, STRATEGY_VERSION=21, CANDLE_ALIGNMENT_VERSION=2;
    private static final int EXPLORATION_TARGET_TRADES=40, EXPLORATION_RISK_PCT=15, MAX_ATTACK_POSITIONS=5;
    public static final int MIN_UNITS=1000, UNIT_STEP=1000, MAX_POSITIONS=10;
    public static final String[] EXTRA_TF={"W","MN"};
    public static final String[] DISPLAY_TF={"M5","M15","M30","H1","H2","H4","H8","D","W","MN"};
    public static final double MARGIN_RATE=0.04, LEVERAGE_COURSE=25.0, MARGIN_CALL_LEVEL_PCT=100.0, LIQUIDATION_LEVEL_PCT=50.0;
    private static final double MAX_PORTFOLIO_RISK_PCT=0.05;
    public static double bidFromMid(double mid){return Double.isNaN(mid)?Double.NaN:mid-SPREAD/2.0;}
    public static double askFromMid(double mid){return Double.isNaN(mid)?Double.NaN:mid+SPREAD/2.0;}
    private final SharedPreferences p,market;
    public DemoStore(Context c){p=c.getSharedPreferences("chappy_fx",Context.MODE_PRIVATE);market=c.getSharedPreferences("chappy_fx_market",Context.MODE_PRIVATE);if(!p.contains("balance"))reset();migrateStrategy();migrateSpread();migratePositions();migrateCandleAlignment();}
    private void migrateStrategy(){
        int v=p.getInt("strategyVersion",1);SharedPreferences.Editor e=p.edit();
        if(v<2){e.putInt("adaptiveReadiness",65).putInt("adaptiveAgreement",75).putBoolean("adaptiveRequireH1",false);}
        if(v<11){e.putInt("adaptiveAttackScore",60).putInt("adaptiveAttackRiskPct",40);}
        if(v<12){e.putInt("adaptiveAttackScore",60).putInt("adaptiveAttackRiskPct",50);}
        if(v<13){e.putString("strategyStatus","全10時間足を方向分析しつつ、M5/M15/M30で独立した短期方向が成立すれば上位足と逆でも小さくエントリー可能。上位足逆風は見送り理由ではなくリスク量・利確幅へ反映");}
        if(v<14){e.putString("strategyStatus","全10時間足を方向・RCI・MACDまで分析。短期足の独立方向成立時は上位足の反対方向だけを理由に見送りせず、上位逆風はサイズ縮小と短めの利確で対応");}
        if(v<15){e.putString("strategyStatus","ピークボトムもM5〜月足の全10時間足で評価。M15/M30だけを固定条件にせず、下位足PBが成立すれば先行攻め候補にし、上位足PBの逆風はリスク調整に使う");}
        if(v<16){e.putString("strategyStatus","PBの過去B→P / P→B周期から次の転換目安を時間足別に推定し、保有中の利確意欲・継続判断へ利用。推定だけでは自動決済せず、RCI/MACD・価格到達と併用する");}
        if(v<17){e.putString("strategyStatus","PB予測を全10時間足で相互比較。下位足に暫定ピーク/ボトムが出ても、上位足が同方向の転換待ちなら更新継続を強めに評価し、逆なら転換警戒を強める");}
        boolean learningUpgrade=v<18;
        if(v<18)e.putInt("strategyVersion",STRATEGY_VERSION).putString("strategyStatus","通常エントリー＋小リスク探索の先行エントリーで検証\n安全条件は固定し、十分な件数が集まった指標だけを段階的に学習");
        else if(v<19)e.putInt("strategyVersion",STRATEGY_VERSION).putString("strategyStatus","現在の標準設定値を維持し、相場環境→方向→タイミング→リスクの順で指標を使い分ける");
        else if(v<STRATEGY_VERSION)e.putInt("strategyVersion",STRATEGY_VERSION).putInt("adaptiveAttackScore",52).putInt("adaptiveAttackRiskPct",20).putString("strategyStatus","通常エントリーは慎重条件を維持。先行エントリーは常時探索モードで、低リスクのままM5未一致・上位足逆風・PB先行など複数パターンを積極的に検証し、失敗も学習材料として蓄積する");
        e.apply();
        if(learningUpgrade){
            JSONArray arr=trades();
            Tuning t=tuneStrategy(arr);
            updateLearningProfile(arr);
            p.edit().putInt("adaptiveReadiness",t.readiness).putInt("adaptiveAgreement",t.agreement).putBoolean("adaptiveRequireH1",t.requireH1)
             .putInt("adaptiveAttackScore",t.attackScore).putInt("adaptiveAttackRiskPct",t.attackRiskPct).putString("strategyStatus",buildStrategyStatus(t,arr)).apply();
        }
    }
    private void migrateCandleAlignment(){
        if(market.getInt("candleAlignmentVersion",1)>=CANDLE_ALIGNMENT_VERSION)return;
        market.edit()
                .remove("candles_H2").remove("candles_H4").remove("candles_H8")
                .putInt("candleAlignmentVersion",CANDLE_ALIGNMENT_VERSION).apply();
        p.edit()
                .remove("tf_H2_dir").remove("tf_H2_strength").remove("tf_H2_hist")
                .remove("tf_H4_dir").remove("tf_H4_strength").remove("tf_H4_hist")
                .remove("tf_H8_dir").remove("tf_H8_strength").remove("tf_H8_hist")
                .remove("pb_H2").remove("pb_H4").remove("pb_H8").apply();
    }

    private void migrateSpread(){
        if(p.getInt("spreadVersion",1)>=SPREAD_VERSION)return;
        SharedPreferences.Editor e=p.edit().putInt("spreadVersion",SPREAD_VERSION);
        if(p.contains("posDir")){
            double shift=(LEGACY_SPREAD-SPREAD)/2.0;
            double signedShift="long".equals(p.getString("posDir",""))?-shift:shift;
            double en=p.contains("entry")?Double.longBitsToDouble(p.getLong("entry",0)):Double.NaN;
            double st=p.contains("stop")?Double.longBitsToDouble(p.getLong("stop",0)):Double.NaN;
            double tg=p.contains("target")?Double.longBitsToDouble(p.getLong("target",0)):Double.NaN;
            if(!Double.isNaN(en))e.putLong("entry",Double.doubleToRawLongBits(en+signedShift));
            if(!Double.isNaN(st))e.putLong("stop",Double.doubleToRawLongBits(st+signedShift));
            if(!Double.isNaN(tg))e.putLong("target",Double.doubleToRawLongBits(tg+signedShift));
        }
        e.apply();
    }
    private void migratePositions(){
        if(p.contains("positions")){if(p.getInt("positionVersion",0)<POSITION_VERSION)p.edit().putInt("positionVersion",POSITION_VERSION).apply();return;}
        JSONArray a=new JSONArray();
        if(p.contains("posDir")){
            try{
                JSONObject x=new JSONObject();
                x.put("id","legacy-"+System.currentTimeMillis()).put("dir",p.getString("posDir",""))
                 .put("entry",p.contains("entry")?Double.longBitsToDouble(p.getLong("entry",0)):Double.NaN)
                 .put("stop",p.contains("stop")?Double.longBitsToDouble(p.getLong("stop",0)):Double.NaN)
                 .put("target",p.contains("target")?Double.longBitsToDouble(p.getLong("target",0)):Double.NaN)
                 .put("units",p.getInt("units",0)).put("openedAt",p.getLong("openedAt",0)).put("openBar",p.getLong("openBar",0))
                 .put("entryReadiness",p.getInt("entryReadiness",0)).put("entryAgreement",p.getInt("entryAgreement",0))
                 .put("entryBackgroundRisk",p.getInt("entryBackgroundRisk",0))
                 .put("entryAtr",p.contains("entryAtr")?Double.longBitsToDouble(p.getLong("entryAtr",0)):Double.NaN)
                 .put("entryStopDist",p.contains("entryStopDist")?Double.longBitsToDouble(p.getLong("entryStopDist",0)):Double.NaN);
                for(String k:MarketEngine.TF){x.put("entryTf_"+k,p.getString("entryTf_"+k+"_dir","neutral"));x.put("entryTf_"+k+"_strength",p.getInt("entryTf_"+k+"_strength",0));}
                a.put(x);
            }catch(Exception ignored){}
        }
        SharedPreferences.Editor e=p.edit().putString("positions",a.toString()).putInt("positionVersion",POSITION_VERSION);
        e.remove("posDir").remove("entry").remove("stop").remove("target").remove("units").remove("openedAt").remove("openBar")
         .remove("entryReadiness").remove("entryAgreement").remove("entryBackgroundRisk").remove("entryAtr").remove("entryStopDist");
        for(String k:MarketEngine.TF)e.remove("entryTf_"+k+"_dir").remove("entryTf_"+k+"_strength");
        e.apply();
    }

    public void reset(){p.edit().clear().putLong("balance",Double.doubleToRawLongBits(START)).putInt("wins",0).putInt("losses",0).putInt("closed",0).putLong("totalPnl",Double.doubleToRawLongBits(0)).putBoolean("running",false).putString("trades","[]").putString("positions","[]").putInt("positionVersion",POSITION_VERSION).putInt("strategyVersion",STRATEGY_VERSION).putInt("spreadVersion",SPREAD_VERSION).putInt("adaptiveReadiness",65).putInt("adaptiveAgreement",75).putBoolean("adaptiveRequireH1",false).putInt("adaptiveAttackScore",52).putInt("adaptiveAttackRiskPct",20).putString("lastReview","決済後に、結果・原因・指標・次回対応を表示します。\n通常エントリーは慎重に維持し、先行エントリーは低リスクで幅広く試して成功・失敗の両方を学習します。").putString("strategyStatus","通常エントリーは慎重条件を維持。先行エントリーは常時探索モードで、低リスクのまま幅広い条件を積極検証し、失敗も学習材料として蓄積する").apply();}
    public boolean isRunning(){return p.getBoolean("running",false);} public void setRunning(boolean v){p.edit().putBoolean("running",v).apply();}
    public void saveShockState(boolean rapid,double moveYen,int windowSec,long detectedAt,long rapidUntil){
        p.edit().putBoolean("shockRapid",rapid).putLong("shockMoveYen",Double.doubleToRawLongBits(moveYen)).putInt("shockWindowSec",windowSec).putLong("shockDetectedAt",detectedAt).putLong("shockRapidUntil",rapidUntil).apply();
    }
    public void endShockMode(){p.edit().putBoolean("shockRapid",false).putLong("shockRapidUntil",0).apply();}
    public boolean shockRapid(){return p.getBoolean("shockRapid",false);}
    public double shockMoveYen(){return p.contains("shockMoveYen")?Double.longBitsToDouble(p.getLong("shockMoveYen",0)):Double.NaN;}
    public int shockWindowSec(){return p.getInt("shockWindowSec",0);}
    public long shockDetectedAt(){return p.getLong("shockDetectedAt",0);}
    public long shockRapidUntil(){return p.getLong("shockRapidUntil",0);}
    public void saveShockInvestigation(OfficialInfoClient.ShockResult r){
        if(r==null)return;p.edit().putString("shockCauseSummary",r.summary==null?"":r.summary).putString("shockCauseDetail",r.detail==null?"":r.detail).putString("shockCauseSources",r.sources==null?"":r.sources).putString("shockCauseConfidence",r.confidence==null?"未判定":r.confidence).putLong("shockCauseCheckedAt",r.checkedAt).apply();
    }
    public String shockCauseSummary(){return p.getString("shockCauseSummary","急変はまだ検出されていません。");}
    public String shockCauseDetail(){return p.getString("shockCauseDetail","通常時は5分ごとに総合解析し、軽量な価格監視で急変を検出すると自動で高速監視へ切り替えます。");}
    public String shockCauseSources(){return p.getString("shockCauseSources","");}
    public String shockCauseConfidence(){return p.getString("shockCauseConfidence","--");}
    public long shockCauseCheckedAt(){return p.getLong("shockCauseCheckedAt",0);}
    public double balance(){return p.contains("balance")?Double.longBitsToDouble(p.getLong("balance",0)):START;} public int wins(){return p.getInt("wins",0);} public int losses(){return p.getInt("losses",0);} public int closed(){return p.getInt("closed",0);} public double totalPnl(){return p.contains("totalPnl")?Double.longBitsToDouble(p.getLong("totalPnl",0)):0;}
    public JSONArray positions(){try{return new JSONArray(p.getString("positions","[]"));}catch(Exception e){return new JSONArray();}}
    private void savePositions(JSONArray a){p.edit().putString("positions",a==null?"[]":a.toString()).apply();}
    public int positionCount(){return positions().length();}
    public int longPositionCount(){JSONArray a=positions();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&"long".equals(x.optString("dir")))n++;}return n;}
    public int shortPositionCount(){JSONArray a=positions();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&"short".equals(x.optString("dir")))n++;}return n;}
    public int totalUnits(){JSONArray a=positions();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)n+=Math.max(0,x.optInt("units",0));}return n;}
    private JSONObject firstPosition(){return positions().optJSONObject(0);}
    public boolean hasPosition(){return positionCount()>0;}
    public String posDir(){JSONObject x=firstPosition();return x==null?"":x.optString("dir","");}
    public double entry(){JSONObject x=firstPosition();return x==null?Double.NaN:x.optDouble("entry",Double.NaN);}
    public double stop(){JSONObject x=firstPosition();return x==null?Double.NaN:x.optDouble("stop",Double.NaN);}
    public double target(){JSONObject x=firstPosition();return x==null?Double.NaN:x.optDouble("target",Double.NaN);}
    public int units(){JSONObject x=firstPosition();return x==null?0:x.optInt("units",0);}
    public long openedAt(){JSONObject x=firstPosition();return x==null?0:x.optLong("openedAt",0);}
    public long openBar(){JSONObject x=firstPosition();return x==null?0:x.optLong("openBar",0);}
    public double positionUnrealized(JSONObject x,double mid){if(x==null||Double.isNaN(mid))return 0;String dir=x.optString("dir","");double en=x.optDouble("entry",Double.NaN);int u=x.optInt("units",0);if(Double.isNaN(en)||u<=0)return 0;double mark="long".equals(dir)?bidFromMid(mid):askFromMid(mid);return ("long".equals(dir)?mark-en:en-mark)*u;}
    public double openRisk(){JSONArray a=positions();double r=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)r+=Math.abs(x.optDouble("entry",0)-x.optDouble("stop",0))*Math.max(0,x.optInt("units",0));}return r;}
    public double maxPortfolioRisk(){return Math.max(500,balance()*MAX_PORTFOLIO_RISK_PCT);}
    public int longUnits(){JSONArray a=positions();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&"long".equals(x.optString("dir")))n+=Math.max(0,x.optInt("units",0));}return n;}
    public int shortUnits(){JSONArray a=positions();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&"short".equals(x.optString("dir")))n+=Math.max(0,x.optInt("units",0));}return n;}
    static double requiredMarginForRules(double mid,int longUnits,int shortUnits){
        if(Double.isNaN(mid)||mid<=0)return 0;
        int maxSide=Math.max(Math.max(0,longUnits),Math.max(0,shortUnits));
        return maxSide<=0?0:Math.ceil(mid*maxSide*MARGIN_RATE);
    }
    static double marginMaintenanceRatioForRules(double netAssets,double requiredMargin){
        return requiredMargin<=0?Double.NaN:(netAssets/requiredMargin)*100.0;
    }
    static double effectiveLeverageForRules(double mid,int longUnits,int shortUnits,double netAssets){
        int maxSide=Math.max(Math.max(0,longUnits),Math.max(0,shortUnits));
        return maxSide<=0||Double.isNaN(mid)||mid<=0||netAssets<=0?0.0:(mid*maxSide/netAssets);
    }
    static String marginStatusForRules(double ratio){
        if(Double.isNaN(ratio))return "スタンダード25倍 / ロスカット50%";
        if(ratio<LIQUIDATION_LEVEL_PCT)return "ロスカット水準（50%未満）";
        if(ratio<MARGIN_CALL_LEVEL_PCT)return "追証判定水準（100%未満）";
        if(ratio<=120.0)return "証拠金維持率に注意";
        return "証拠金余力あり";
    }
    private double requiredMarginFor(double mid,int longUnits,int shortUnits){return requiredMarginForRules(mid,longUnits,shortUnits);}
    public double requiredMargin(double mid){return requiredMarginFor(mid,longUnits(),shortUnits());}
    public double netAssets(double mid){return balance()+unrealized(mid);}
    public double availableMargin(double mid){return netAssets(mid)-requiredMargin(mid);}
    public double marginMaintenanceRatio(double mid){return marginMaintenanceRatioForRules(netAssets(mid),requiredMargin(mid));}
    public String marginStatus(double mid){return marginStatusForRules(marginMaintenanceRatio(mid));}

    public String lastAction(){return p.getString("lastAction","条件待ち");} public double lastMid(){return p.contains("lastMid")?Double.longBitsToDouble(p.getLong("lastMid",0)):Double.NaN;} public void setLastMid(double v){p.edit().putLong("lastMid",Double.doubleToRawLongBits(v)).apply();} public String lastError(){return p.getString("lastError","");} public long lastUpdate(){return p.getLong("lastUpdate",0);} public long lastAnalysisSuccess(){return p.getLong("lastAnalysisSuccess",0);}
    public String mainDir(){return p.getString("mainDir","neutral");} public int readiness(){return p.getInt("readiness",0);} public int agreement(){return p.getInt("agreement",0);} public String state(){return p.getString("state","--");}
    public String tfDir(String k){return p.getString("tf_"+k+"_dir","neutral");} public int tfStrength(String k){return p.getInt("tf_"+k+"_strength",0);} public int tfHistory(String k){return p.getInt("tf_"+k+"_hist",0);}
    public String tradeSide(){return candidateSideStored();}
    public String trendRegime(){return p.getString("trendRegime","neutral");}
    public String entrySetupState(){return p.getString("entrySetupState","上位足の方向を確認中");}
    public String technicalSummary(){return p.getString("technicalSummary","RCI・MACDの判定待ち");}
    public String peakBottomSummary(){return p.getString("peakBottomSummary","上位足PB 判定待ち");}
    public String peakBottomTfState(String tf){return p.getString("pb_"+tf,"判定待ち");}
    public int peakBottomHigherBias(){return p.getInt("peakBottomHigherBias",0);}
    public int technicalScore(){return p.getInt("technicalScore",0);}
    public int indicatorAgreement(){return p.getInt("indicatorAgreement",0);}
    public int indicatorAgreement(String tf){return tf==null?0:p.getInt("indicatorAgreement_"+tf,0);}
    public int rapidMoveRisk(){return p.getInt("rapidMoveRisk",0);}
    public int rapidMoveRisk(String tf){return tf==null?0:p.getInt("rapidMoveRisk_"+tf,0);}
    public String rapidMoveDirection(){return p.getString("rapidMoveDirection","neutral");}
    public String rapidMoveDirection(String tf){return tf==null?"neutral":p.getString("rapidMoveDirection_"+tf,"neutral");}
    public String volatilityRegime(){return p.getString("volatilityRegime","通常ボラ");}
    public double volatilityRatio(){return p.contains("volatilityRatio")?Double.longBitsToDouble(p.getLong("volatilityRatio",0)):1.0;}
    public int volatilityExpansionScore(){return p.getInt("volatilityExpansionScore",0);}
    public int entryAttackScore(){return p.getInt("entryAttackScore",0);}
    public String entryAttackReason(){return p.getString("entryAttackReason","");}
    public String entryStyle(){return p.getString("entryStyle","通常");}
    public int tradeReadiness(){String side=candidateSideStored();return side==null?0:tradeReadinessStored(side);}
    public int tradeAgreement(){String side=candidateSideStored();return side==null?0:tradeAgreementStored(side);}
    public int backgroundRisk(){String side=candidateSideStored();return side==null?0:backgroundRiskStored(side);}
    public String lastReview(){return p.getString("lastReview","決済後に分析を表示します。");} public String strategyStatus(){return p.getString("strategyStatus","学習準備中");} public int adaptiveReadiness(){return p.getInt("adaptiveReadiness",65);} public int adaptiveAgreement(){return p.getInt("adaptiveAgreement",45);} public boolean adaptiveRequireH1(){return p.getBoolean("adaptiveRequireH1",false);} public int adaptiveAttackScore(){return p.getInt("adaptiveAttackScore",60);} public int adaptiveAttackRiskPct(){return p.getInt("adaptiveAttackRiskPct",50);}
    public void saveImportantInfo(int risk,String summary,long updatedAt){p.edit().putInt("importantRisk",risk).putString("importantSummary",summary==null?"":summary).putLong("importantUpdatedAt",updatedAt).apply();}
    public int importantRisk(){return p.getInt("importantRisk",0);} public String importantSummary(){return p.getString("importantSummary","");} public long importantUpdatedAt(){return p.getLong("importantUpdatedAt",0);}
    public long importantInfoCheckedAt(){return market.getLong("officialInfoCheckedAt",0);} public long importantInfoChangedAt(){return market.getLong("officialInfoChangedAt",0);}
    private static final long IMPORTANT_PRE_BLOCK_MS=15L*60_000L,IMPORTANT_POST_BLOCK_MS=30L*60_000L;
    public long nextKnownImportantEventMs(){
        long now=System.currentTimeMillis(),best=Long.MAX_VALUE;
        OfficialInfoClient.Analysis a=loadImportantAnalysis();
        if(a!=null)for(OfficialInfoClient.Item x:a.items)if(x!=null&&x.risk>=85&&x.dateMs>now&&x.dateMs<best)best=x.dateMs;
        EmploymentForecastClient.Result e=loadEmploymentForecast();
        if(e!=null&&e.nextReleaseMs>now&&e.nextReleaseMs<best)best=e.nextReleaseMs;
        return best==Long.MAX_VALUE?0:best;
    }
    public String nextKnownImportantEventLabel(){
        long now=System.currentTimeMillis(),best=Long.MAX_VALUE;String label="";
        OfficialInfoClient.Analysis a=loadImportantAnalysis();
        if(a!=null)for(OfficialInfoClient.Item x:a.items)if(x!=null&&x.risk>=85&&x.dateMs>now&&x.dateMs<best){best=x.dateMs;label=x.source+"「"+OfficialInfoClient.displayTitle(x)+"」";}
        EmploymentForecastClient.Result e=loadEmploymentForecast();
        if(e!=null&&e.nextReleaseMs>now&&e.nextReleaseMs<best){best=e.nextReleaseMs;label="米雇用統計";}
        return label;
    }
    public String importantEntryBlockReason(){
        long now=System.currentTimeMillis(),future=Long.MAX_VALUE,recent=0;String futureLabel="",recentLabel="";
        OfficialInfoClient.Analysis a=loadImportantAnalysis();
        if(a!=null)for(OfficialInfoClient.Item x:a.items){
            if(x==null||x.risk<85||x.dateMs<=0)continue;
            if(x.dateMs>now&&x.dateMs<future){future=x.dateMs;futureLabel=x.source+"「"+OfficialInfoClient.displayTitle(x)+"」";}
            else if(x.dateMs<=now&&now-x.dateMs<=IMPORTANT_POST_BLOCK_MS&&x.dateMs>recent){recent=x.dateMs;recentLabel=x.source+"「"+OfficialInfoClient.displayTitle(x)+"」";}
        }
        EmploymentForecastClient.Result e=loadEmploymentForecast();
        if(e!=null&&e.nextReleaseMs>0){
            if(e.nextReleaseMs>now&&e.nextReleaseMs<future){future=e.nextReleaseMs;futureLabel="米雇用統計";}
            else if(e.nextReleaseMs<=now&&now-e.nextReleaseMs<=IMPORTANT_POST_BLOCK_MS&&e.nextReleaseMs>recent){recent=e.nextReleaseMs;recentLabel="米雇用統計";}
        }
        if(future<Long.MAX_VALUE&&future-now<=IMPORTANT_PRE_BLOCK_MS){
            long min=Math.max(0,(future-now+59_999L)/60_000L);
            return "重要情報直前（"+futureLabel+"まで約"+min+"分）";
        }
        if(recent>0){
            long min=Math.max(0,(now-recent)/60_000L);
            return "重要情報発表後（"+recentLabel+"から"+min+"分）";
        }
        return "";
    }
    public boolean freshHighInfoRisk(){return !importantEntryBlockReason().isEmpty();}

    public void saveEmploymentForecast(EmploymentForecastClient.Result r){
        if(r==null||!r.fetchSucceeded)return;
        try{
            JSONArray hist=employmentHistory();
            for(int i=0;i<hist.length();i++){
                JSONObject x=hist.optJSONObject(i);if(x==null||x.has("actualK"))continue;
                if(r.latestPayrollMonth!=null&&r.latestPayrollMonth.equals(x.optString("referenceMonth"))&&!Double.isNaN(r.latestPayrollChangeK)){
                    double center=x.optDouble("centerK",Double.NaN);
                    x.put("actualK",r.latestPayrollChangeK);x.put("errorK",r.latestPayrollChangeK-center);x.put("resolvedAt",System.currentTimeMillis());
                }
            }
            JSONObject rec=resultToJson(r);
            int replace=-1;
            for(int i=0;i<hist.length();i++){JSONObject x=hist.optJSONObject(i);if(x!=null&&r.referenceMonth.equals(x.optString("referenceMonth"))&&!x.has("actualK")){replace=i;break;}}
            JSONArray out=new JSONArray();
            if(replace<0)out.put(rec);
            else out.put(rec);
            for(int i=0;i<hist.length()&&out.length()<24;i++){if(i==replace)continue;out.put(hist.get(i));}
            market.edit().putString("employmentForecastCache",rec.toString()).putString("employmentForecastHistory",out.toString()).putLong("employmentForecastCheckedAt",r.checkedAt).apply();
        }catch(Exception ignored){}
    }
    private JSONObject resultToJson(EmploymentForecastClient.Result r)throws Exception{
        JSONObject o=new JSONObject();o.put("checkedAt",r.checkedAt).put("nextReleaseMs",r.nextReleaseMs).put("referenceMonth",r.referenceMonth).put("latestPayrollMonth",r.latestPayrollMonth).put("bias",r.bias).put("summary",r.summary).put("payrollCenterK",r.payrollCenterK).put("payrollLowK",r.payrollLowK).put("payrollHighK",r.payrollHighK).put("unemploymentRate",r.unemploymentRate).put("wageMonthlyPct",r.wageMonthlyPct).put("confidence",r.confidence).put("signalScore",r.signalScore).put("latestPayrollChangeK",r.latestPayrollChangeK);
        JSONArray a=new JSONArray();for(String s:r.factors)a.put(s);o.put("factors",a);return o;
    }
    public EmploymentForecastClient.Result loadEmploymentForecast(){
        String raw=market.getString("employmentForecastCache","");if(raw.isEmpty())return null;
        try{
            JSONObject o=new JSONObject(raw);EmploymentForecastClient.Result r=new EmploymentForecastClient.Result();
            r.checkedAt=o.optLong("checkedAt");r.nextReleaseMs=o.optLong("nextReleaseMs");r.referenceMonth=o.optString("referenceMonth","");r.latestPayrollMonth=o.optString("latestPayrollMonth","");r.bias=o.optString("bias","中立");r.summary=o.optString("summary","");r.payrollCenterK=o.optDouble("payrollCenterK",Double.NaN);r.payrollLowK=o.optDouble("payrollLowK",Double.NaN);r.payrollHighK=o.optDouble("payrollHighK",Double.NaN);r.unemploymentRate=o.optDouble("unemploymentRate",Double.NaN);r.wageMonthlyPct=o.optDouble("wageMonthlyPct",Double.NaN);r.confidence=o.optInt("confidence");r.signalScore=o.optInt("signalScore");r.latestPayrollChangeK=o.optDouble("latestPayrollChangeK",Double.NaN);r.fetchSucceeded=true;
            JSONArray a=o.optJSONArray("factors");if(a!=null)for(int i=0;i<a.length();i++)r.factors.add(a.optString(i));return r;
        }catch(Exception e){return null;}
    }
    public long employmentForecastCheckedAt(){return market.getLong("employmentForecastCheckedAt",0);}
    public JSONArray employmentHistory(){try{return new JSONArray(market.getString("employmentForecastHistory","[]"));}catch(Exception e){return new JSONArray();}}
    public int employmentResolvedCount(){JSONArray a=employmentHistory();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&x.has("actualK"))n++;}return n;}
    public double employmentMaeK(){JSONArray a=employmentHistory();double sum=0;int n=0;for(int i=0;i<a.length()&&n<6;i++){JSONObject x=a.optJSONObject(i);if(x!=null&&x.has("actualK")){sum+=Math.abs(x.optDouble("errorK",0));n++;}}return n==0?Double.NaN:sum/n;}
    public double employmentCalibrationK(){JSONArray a=employmentHistory();double sum=0;int n=0;for(int i=0;i<a.length()&&n<6;i++){JSONObject x=a.optJSONObject(i);if(x!=null&&x.has("actualK")){sum+=x.optDouble("errorK",0);n++;}}if(n<3)return 0;return Math.max(-25,Math.min(25,sum/n));}

    public String freeOfficialCache(){return freeOfficialCache("employment");}
    public long freeOfficialCheckedAt(){return freeOfficialCheckedAt("employment");}
    public void saveFreeOfficialCache(String json,long checkedAt){saveFreeOfficialCache("employment",json,checkedAt);}
    public String freeOfficialCache(String type){String k=(type==null||type.isEmpty())?"employment":type;return market.getString("freeOfficialCache_"+k,market.getString("freeOfficialCache",""));}
    public long freeOfficialCheckedAt(String type){String k=(type==null||type.isEmpty())?"employment":type;return market.getLong("freeOfficialCheckedAt_"+k,market.getLong("freeOfficialCheckedAt",0));}
    public void saveFreeOfficialCache(String type,String json,long checkedAt){
        if(json==null)return;String k=(type==null||type.isEmpty())?"employment":type;
        SharedPreferences.Editor e=market.edit().putString("freeOfficialCache_"+k,json).putLong("freeOfficialCheckedAt_"+k,checkedAt);
        if("employment".equals(k))e.putString("freeOfficialCache",json).putLong("freeOfficialCheckedAt",checkedAt);
        e.apply();
    }

    public boolean saveImportantAnalysis(OfficialInfoClient.Analysis a){
        if(a==null)return false;
        long checked=a.checkedAt>0?a.checkedAt:System.currentTimeMillis();
        JSONArray items=new JSONArray();
        try{
            for(OfficialInfoClient.Item x:a.items){
                JSONObject o=new JSONObject();
                o.put("source",x.source);o.put("country",x.country);o.put("title",x.title);o.put("titleJa",x.titleJa);o.put("url",x.url);o.put("date",x.date);o.put("dateMs",x.dateMs);o.put("risk",x.risk);o.put("direction",x.direction);o.put("category",x.category);
                items.put(o);
            }
            JSONObject content=new JSONObject();content.put("risk",a.risk);content.put("summary",a.summary);content.put("items",items);
            String signature=content.toString();
            String oldSignature=market.getString("officialInfoSignature","");
            boolean changed=!signature.equals(oldSignature);
            SharedPreferences.Editor me=market.edit().putLong("officialInfoCheckedAt",checked);
            if(changed||!market.contains("officialInfoCache")){
                JSONObject cache=new JSONObject(content.toString());cache.put("changedAt",checked);
                me.putString("officialInfoCache",cache.toString()).putString("officialInfoSignature",signature).putLong("officialInfoChangedAt",checked);
            }
            me.apply();
            saveImportantInfo(a.risk,a.summary,checked);
            return changed;
        }catch(Exception e){
            saveImportantInfo(a.risk,a.summary,checked);
            market.edit().putLong("officialInfoCheckedAt",checked).apply();
            return false;
        }
    }

    public OfficialInfoClient.Analysis loadImportantAnalysis(){
        String raw=market.getString("officialInfoCache","");
        if(raw.isEmpty())return null;
        try{
            JSONObject root=new JSONObject(raw);OfficialInfoClient.Analysis a=new OfficialInfoClient.Analysis();
            a.risk=root.optInt("risk",0);a.summary=root.optString("summary","");a.checkedAt=importantInfoCheckedAt();
            JSONArray items=root.optJSONArray("items");
            if(items!=null)for(int i=0;i<items.length();i++){
                JSONObject o=items.optJSONObject(i);if(o==null)continue;OfficialInfoClient.Item x=new OfficialInfoClient.Item();
                x.source=o.optString("source","");x.country=o.optString("country","");x.title=o.optString("title","");x.titleJa=o.optString("titleJa","");x.url=o.optString("url","");x.date=o.optString("date","");x.dateMs=o.optLong("dateMs",0);x.risk=o.optInt("risk",0);x.direction=o.optString("direction","");x.category=o.optString("category","");
                a.items.add(x);
            }
            return a;
        }catch(Exception e){return null;}
    }

    public void saveCandles(String tf,List<MarketEngine.Candle> rows){
        JSONArray a=new JSONArray();if(rows!=null){int from=Math.max(0,rows.size()-500);for(int i=from;i<rows.size();i++){MarketEngine.Candle r=rows.get(i);JSONObject x=new JSONObject();try{x.put("t",r.timeMs);x.put("o",r.o);x.put("h",r.h);x.put("l",r.l);x.put("c",r.c);a.put(x);}catch(Exception ignored){}}}
        market.edit().putString("candles_"+tf,a.toString()).apply();
    }
    public JSONArray candles(String tf){try{return new JSONArray(market.getString("candles_"+tf,"[]"));}catch(Exception e){return new JSONArray();}}
    public List<MarketEngine.Candle> candleList(String tf){
        ArrayList<MarketEngine.Candle> out=new ArrayList<>();JSONArray a=candles(tf);
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;out.add(new MarketEngine.Candle(x.optLong("t"),x.optDouble("o"),x.optDouble("h"),x.optDouble("l"),x.optDouble("c"),0,false));}
        out.sort(Comparator.comparingLong(v->v.timeMs));return out;
    }
    public void saveM5Candles(List<MarketEngine.Candle> rows){saveCandles("M5",rows);}
    public JSONArray m5Candles(){return candles("M5");}

    public void saveAnalysis(MarketEngine.Analysis a){long now=System.currentTimeMillis();SharedPreferences.Editor e=p.edit().putString("mainDir",a.mainDirection).putInt("readiness",a.readiness).putInt("agreement",a.agreement).putString("state",a.state).putLong("lastUpdate",now).putLong("lastAnalysisSuccess",now).putString("lastError","");for(String k:MarketEngine.TF){MarketEngine.TfResult r=a.tf.get(k);e.putString("tf_"+k+"_dir",r.direction).putInt("tf_"+k+"_strength",r.strength).putInt("tf_"+k+"_hist",r.historyCount);}e.apply();}
    public void saveExtendedAnalysis(Map<String,List<MarketEngine.Candle>> raw){
        if(raw==null)return;SharedPreferences.Editor e=p.edit();
        for(String k:EXTRA_TF){
            List<MarketEngine.Candle> rows=raw.get(k);if(rows==null||rows.isEmpty())continue;
            ExtendedTfResult r=analyzeExtended(rows);e.putString("tf_"+k+"_dir",r.direction).putInt("tf_"+k+"_strength",r.strength).putInt("tf_"+k+"_hist",r.historyCount);
            TechnicalSignal t=technicalSignal(rows);if(!Double.isNaN(t.rci52)&&!Double.isNaN(t.macd)){e.putInt("indicatorAgreement_"+k,indicatorAgreementOf(t)).putInt("rapidMoveRisk_"+k,rapidRiskOf(t)).putString("rapidMoveDirection_"+k,rapidDirectionOf(t));}
        }
        e.apply();
    }
    private static final class ExtendedTfResult{String direction="neutral";int strength=0,historyCount=0;}
    private ExtendedTfResult analyzeExtended(List<MarketEngine.Candle> rows){
        ExtendedTfResult r=new ExtendedTfResult();r.historyCount=rows==null?0:rows.size();if(rows==null||rows.size()<18)return r;
        int n=rows.size();double ema5=emaClose(rows,5,n),ema25=emaClose(rows,25,n),prev5=emaClose(rows,5,n-1),last=rows.get(n-1).c,prev3=rows.get(Math.max(0,n-4)).c,rsi=rsiClose(rows,14);
        int score=0;score+=last>=ema5?20:-20;score+=ema5>=ema25?25:-25;score+=ema5>=prev5?15:-15;score+=last>=prev3?15:-15;if(!Double.isNaN(rsi)){if(rsi>=55)score+=25;else if(rsi<=45)score-=25;}
        int abs=Math.min(100,Math.abs(score));r.strength=abs;if(abs<20)r.direction="neutral";else r.direction=score>0?"up":"down";return r;
    }
    private double emaClose(List<MarketEngine.Candle> rows,int period,int endExclusive){
        int end=Math.min(rows.size(),Math.max(1,endExclusive)),start=Math.max(0,end-Math.max(period*4,period+2));double a=2.0/(period+1.0),v=rows.get(start).c;for(int i=start+1;i<end;i++)v=rows.get(i).c*a+v*(1-a);return v;
    }
    private double rsiClose(List<MarketEngine.Candle> rows,int period){
        if(rows==null||rows.size()<period+1)return Double.NaN;int from=rows.size()-period;double gain=0,loss=0;for(int i=from;i<rows.size();i++){double ch=rows.get(i).c-rows.get(i-1).c;if(ch>0)gain+=ch;else loss-=ch;}if(loss<1e-12)return gain>0?100:50;double rs=(gain/period)/(loss/period);return 100.0-(100.0/(1.0+rs));
    }
    public void saveError(String s){p.edit().putString("lastError",s==null?"不明なエラー":s).putLong("lastUpdate",System.currentTimeMillis()).apply();}

    public static final class Action { public String message=""; public boolean notable=false; }

    private static final class TechnicalSignal {
        double rci9=Double.NaN,rci26=Double.NaN,rci52=Double.NaN,prev9=Double.NaN,prev26=Double.NaN,prev52=Double.NaN;
        double macd=Double.NaN,signal=Double.NaN,hist=Double.NaN,prevMacd=Double.NaN,prevSignal=Double.NaN,prevHist=Double.NaN;
        double rsi=Double.NaN,stochK=Double.NaN,stochD=Double.NaN,cci=Double.NaN,williams=Double.NaN,roc=Double.NaN,adx=Double.NaN,plusDi=Double.NaN,minusDi=Double.NaN;
        String regime="レンジ";
        boolean golden=false,dead=false,topDrop=false,bottomJump=false,overboughtTurn=false,oversoldTurn=false;
        boolean rciHighCluster=false,rciLowCluster=false,rciHighBreak=false,rciLowBreak=false,bearConfluence=false,bullConfluence=false;
        boolean bullCandle=false,bearCandle=false,donchianUp=false,donchianDown=false;
        int trendLong=0,trendShort=0,timingLong=0,timingShort=0,longScore=0,shortScore=0;
    }
    private static final class VolatilitySnapshot {
        String regime="通常ボラ";double ratio=1.0,atr=Double.NaN,trueRange=Double.NaN,atrSlope=0,rangeRatio=1.0;int expansionScore=0;boolean expansionLikely=false;
    }
    private static final class EntryDecision {
        String side=null,regime="neutral",contextSide="neutral",volatility="通常ボラ",technicalRegime="レンジ",state="全時間足の方向を確認中",summary="指標判定待ち";
        double volatilityRatio=1.0;int expansionScore=0,attackScore=0,allTimeframeScore=0,pbAllScore=0,pbLowerScore=0,pbUpperRisk=0,pbTopDownScore=0;
        String attackReason="";
        int technicalScore=0,readiness=0,agreement=0,backgroundRisk=0;
        boolean entry=false,attackEntry=false,explorationEntry=false,lowerTimeframeLead=false,pbLowerLead=false,counterTrendEntry=false;
        int learningBonus=0;
    }

    public synchronized Action step(MarketEngine.Analysis a,Map<String,List<MarketEngine.Candle>> raw){
        updatePeakBottomHoldGuides(raw);
        Action action=new Action();ArrayList<String> events=checkMarginLiquidation(a,raw);boolean liquidated=!events.isEmpty();
        if(!liquidated)events.addAll(checkExits(a,raw));if(!events.isEmpty())action.notable=true;
        EntryDecision decision=evaluateEntry(a,raw);saveEntryDecision(decision);
        String entryMsg=null;
        if(liquidated){
            // ロスカット直後の同一解析ターンでは再エントリーしない。
        }else if(freshHighInfoRisk()){
            if(events.isEmpty())events.add(importantEntryBlockReason()+"：新規エントリー見送り");
        }else if(decision.entry){
            p.edit().remove("lastOpenBlock").apply();
            entryMsg=open(a,raw,decision);
            if(entryMsg!=null){events.add(entryMsg);action.notable=true;}
            else if(events.isEmpty()&&positionCount()>=MAX_POSITIONS)events.add("保有上限 "+MAX_POSITIONS+"ポジション：新規エントリー見送り");
            else if(events.isEmpty()&&"margin".equals(p.getString("lastOpenBlock","")))events.add("取引余力不足：新規エントリー見送り");
            else if(events.isEmpty()&&"attackCap".equals(p.getString("lastOpenBlock","")))events.add("先行エントリーは同時"+MAX_ATTACK_POSITIONS+"件まで：追加エントリー見送り");
            else if(events.isEmpty()&&openRisk()>=maxPortfolioRisk()-1)events.add("総リスク上限到達：新規エントリー見送り");
        }
        if(events.isEmpty())events.add((hasPosition()?"ポジション継続 / ":"")+decision.state);
        StringBuilder b=new StringBuilder();for(String x:events){if(b.length()>0)b.append(" / ");b.append(x);}
        action.message=b.toString();p.edit().putString("lastAction",action.message).apply();return action;
    }

    private EntryDecision evaluateEntry(MarketEngine.Analysis a,Map<String,List<MarketEngine.Candle>> raw){
        EntryDecision d=new EntryDecision();
        PeakBottomEngine.MultiTimeframe pb=PeakBottomEngine.analyzeAll(raw);
        int pbBias=pb.higherBiasFor("H4");if(pbBias==PeakBottomEngine.NEUTRAL)pbBias=pb.primaryBias();
        savePeakBottom(pb,pbBias);

        String oldRegime=trendRegime(a),allSide=allTimeframeSide(a),lowerSide=lowerTimeframeSide(a);
        String pbSide=pbBias==PeakBottomEngine.UP?"up":pbBias==PeakBottomEngine.DOWN?"down":null;
        String pbLowerSide=peakBottomLowerSide(pb),pbAllSide=peakBottomAllSide(pb);
        d.contextSide=allSide!=null?allSide:("up".equals(oldRegime)||"down".equals(oldRegime)?oldRegime:(pbAllSide!=null?pbAllSide:(pbSide!=null?pbSide:"neutral")));
        d.regime=d.contextSide;
        if(lowerSide!=null){
            d.side=lowerSide;d.lowerTimeframeLead=true;
        }else if(pbLowerSide!=null){
            d.side=pbLowerSide;d.lowerTimeframeLead=true;d.pbLowerLead=true;
        }else if(allSide!=null)d.side=allSide;
        else if(pbAllSide!=null)d.side=pbAllSide;
        else if(pbSide!=null)d.side=pbSide;
        else d.side="up".equals(oldRegime)?"up":"down".equals(oldRegime)?"down":null;
        d.allTimeframeScore=d.side==null?0:allTimeframeDirectionScore(a,d.side);
        if(d.side!=null){
            d.pbAllScore=peakBottomDirectionScore(pb,d.side,DISPLAY_TF,new double[]{.18,.17,.15,.13,.10,.09,.07,.05,.035,.025});
            d.pbLowerScore=peakBottomDirectionScore(pb,d.side,new String[]{"M5","M15","M30"},new double[]{.45,.35,.20});
            String pbOpp="up".equals(d.side)?"down":"up";
            d.pbUpperRisk=peakBottomDirectionScore(pb,pbOpp,new String[]{"H1","H2","H4","H8","D","W","MN"},new double[]{.24,.20,.18,.14,.10,.08,.06});
            d.pbTopDownScore=PeakBottomEngine.updateDirectionScore(raw,pb,"up".equals(d.side)?PeakBottomEngine.UP:PeakBottomEngine.DOWN);
            d.pbLowerLead=d.pbLowerLead||d.pbLowerScore>=50;
            d.lowerTimeframeLead=d.lowerTimeframeLead||d.pbLowerLead;
            d.counterTrendEntry=(("up".equals(d.contextSide)||"down".equals(d.contextSide))&&!d.side.equals(d.contextSide))||d.pbUpperRisk>=55;
        }

        String pbSummary=pb.higherSummaryFor("H4");
        d.summary="全10足方向点 "+d.allTimeframeScore+"% / PB全体 "+d.pbAllScore+"%・下位PB "+d.pbLowerScore+"%・上位足連動PB更新 "+d.pbTopDownScore+"%・上位PB逆風 "+d.pbUpperRisk+"% / "+pbSummary+" / "+technicalBundle(raw,d.side);
        d.technicalScore=p.getInt("_tmpTechnicalScore",0);d.technicalRegime=p.getString("_tmpTechnicalRegime","レンジ");
        VolatilitySnapshot vol=volatilitySnapshot(raw.get("M15"));d.volatility=vol.regime;d.volatilityRatio=vol.ratio;d.expansionScore=vol.expansionScore;
        boolean bearConfluence=p.getBoolean("_tmpBearConfluence",false),bullConfluence=p.getBoolean("_tmpBullConfluence",false);
        if(d.side==null){
            if(bearConfluence)d.state="RCI3本の高値圏密集崩れ＋MACD悪化：下降転換を警戒（上位足確定まで新規ショート待機）";
            else if(bullConfluence)d.state="RCI3本の安値圏密集反転＋MACD改善：上昇転換を警戒（上位足確定まで新規ロング待機）";
            else d.state="全時間足の方向・ピークボトムが競合中：下位足の方向成立待ち";
            return d;
        }

        String m5=a.tf.get("M5").direction,m15=a.tf.get("M15").direction,m30=a.tf.get("M30").direction,h1=a.tf.get("H1").direction;
        String opp="up".equals(d.side)?"down":"up";
        boolean pullback=opp.equals(m15)||opp.equals(m30);
        String setup="up".equals(d.side)?"押し目ロング":"戻り売りショート";
        String rapidBlock=opposingRapidWarning(d.side,d.lowerTimeframeLead?new String[]{"M5","M15","M30"}:DISPLAY_TF);
        if(rapidBlock!=null){d.state=setup+"："+rapidBlock+"で反対方向の急変警戒。新規エントリー見送り";return d;}

        if("up".equals(d.side)&&bearConfluence){
            d.state="短期RCI・MACDが急落警戒：下位足の再確認待ち";return d;
        }
        if("down".equals(d.side)&&bullConfluence){
            d.state="短期RCI・MACDが急反発警戒：下位足の再確認待ち";return d;
        }

        d.readiness=tradeReadiness(a,d.side);d.agreement=tradeAgreement(a,d.side);d.backgroundRisk=backgroundRisk(a,d.side);
        boolean danger=p.getBoolean("_tmpTechnicalDanger",false);
        String rapidDir=p.getString("rapidMoveDirection","neutral");int rapidRisk=p.getInt("rapidMoveRisk",0);
        if("急拡大".equals(d.volatility)){d.state=setup+"：ボラ急拡大（ATR比 "+fmtRatio(d.volatilityRatio)+"）のため飛び乗りは見送り";return d;}
        if(danger){d.state=setup+"：複数指標が反対方向の転換を確認";return d;}
        boolean m5Aligned=d.side.equals(m5);
        int attackNeedScore=Math.max(48,adaptiveAttackScore()-4),attackReady=Math.max(42,adaptiveReadiness()-25),attackAgree=Math.max(38,adaptiveAgreement()-35);
        boolean oneMidAligned=d.side.equals(m15)||d.side.equals(m30),bothMidAligned=d.side.equals(m15)&&d.side.equals(m30);
        boolean pbLowerEvidence=d.pbLowerScore>=42||d.pbTopDownScore>=52;
        boolean rapidSame=d.side.equals(rapidDir)&&rapidRisk>=35;
        d.learningBonus=learnedEntryBonus(d);
        d.attackScore=Math.min(100,Math.max(0,(int)Math.round(
                d.technicalScore*.25+d.readiness*.16+d.agreement*.12+d.allTimeframeScore*.07+d.pbAllScore*.09+
                (m5Aligned?8:-4)+(pbLowerEvidence?10:0)+(d.pbTopDownScore>=52?5:0)+(oneMidAligned?6:0)+(bothMidAligned?4:0)+d.expansionScore*.04+(rapidSame?4:0)+(d.lowerTimeframeLead?6:0)+d.learningBonus)));
        boolean attackEvidence=m5Aligned||oneMidAligned||pbLowerEvidence||d.lowerTimeframeLead||d.technicalScore>=58||d.allTimeframeScore>=48;
        boolean attackCandidate=attackEvidence&&d.attackScore>=attackNeedScore&&
                d.technicalScore>=48&&d.readiness>=attackReady&&d.agreement>=attackAgree;

        boolean exploreNeeded=explorationNeeded();
        int exploreNeed=42,exploreReady=35,exploreAgree=30;
        boolean broadEvidence=m5Aligned||oneMidAligned||pbLowerEvidence||d.lowerTimeframeLead||d.technicalScore>=52||d.pbAllScore>=40||d.allTimeframeScore>=40;
        boolean explorationCandidate=!attackCandidate&&exploreNeeded&&d.backgroundRisk<90&&broadEvidence&&
                d.attackScore>=exploreNeed&&d.technicalScore>=40&&d.readiness>=exploreReady&&d.agreement>=exploreAgree;

        if(attackCandidate||explorationCandidate){
            d.entry=true;d.attackEntry=true;d.explorationEntry=explorationCandidate;
            String coreReason=!m5Aligned?"M5未一致の先回り検証":d.pbLowerLead?(d.counterTrendEntry?"下位足PB先行・上位逆風":"下位足PB先行"):d.counterTrendEntry?"下位足独立・上位逆行":vol.expansionLikely?"ボラ予兆先行":d.lowerTimeframeLead?"下位足独立":bothMidAligned?"早期順張り":d.technicalScore>=58?"指標先行":"方向先行";
            d.attackReason=explorationCandidate?"学習探索・"+coreReason:coreReason;
            d.state=(explorationCandidate?"先行エントリー（探索） ":"先行エントリー ")+("up".equals(d.side)?"↑ロング":"↓ショート")+"："+d.attackReason+" / 先行点 "+d.attackScore+"点 / PB下位 "+d.pbLowerScore+"%・PB全体 "+d.pbAllScore+"%"+(d.counterTrendEntry?" / 上位PB逆風はリスク縮小で対応":"");
            return d;
        }

        // ここから下は通常エントリー。通常側の慎重条件は維持する。
        if(!m5Aligned){d.state=setup+"：M5の再加速待ち";return d;}
        // H1同方向必須は通常エントリーの学習ルール。小リスクの先行探索までは止めない。
        if(adaptiveRequireH1()&&!d.side.equals(h1)){d.state=setup+"：通常エントリーは学習ルールによりH1同方向待ち";return d;}

        if(pullback&&!d.lowerTimeframeLead){
            d.state=("up".equals(d.side)?"短期足の上方向成立待ち":"短期足の下方向成立待ち");return d;
        }
        if(!d.side.equals(m15)||!d.side.equals(m30)){d.state=setup+"：通常エントリーは短期トレンド再開待ち / PB下位 "+d.pbLowerScore+"%・ボラ予兆 "+d.expansionScore+"点";return d;}

        int volReady="低ボラ".equals(d.volatility)?5:"高ボラ".equals(d.volatility)?5:0;
        int needTech="レンジ".equals(d.technicalRegime)?60:"低ボラ".equals(d.volatility)?62:"高ボラ".equals(d.volatility)?60:55;
        int minStrength="低ボラ".equals(d.volatility)?55:"高ボラ".equals(d.volatility)?55:50;
        int needReady=Math.min(90,adaptiveReadiness()+(d.backgroundRisk>=45?5:0)+volReady);
        if(d.technicalScore<needTech){d.state=setup+"："+d.volatility+"のため技術点 "+needTech+"% 以上を待機";return d;}
        if(d.readiness<needReady||d.agreement<adaptiveAgreement()){d.state=setup+"：時間足の整合性待ち（"+d.volatility+"補正）";return d;}
        if(a.tf.get("M15").strength<minStrength||a.tf.get("M30").strength<minStrength){d.state=setup+"：M15/M30の勢い不足（"+d.volatility+"）";return d;}
        d.entry=true;d.state=setup+"条件成立（"+d.technicalRegime+" / "+d.volatility+" / 相場環境に応じた指標役割を確認済み）";return d;
    }

    private void savePeakBottom(PeakBottomEngine.MultiTimeframe pb,int bias){
        SharedPreferences.Editor e=p.edit().putInt("peakBottomHigherBias",bias).putString("peakBottomSummary",pb.higherSummaryFor("H4"));
        for(String tf:DISPLAY_TF){PeakBottomEngine.State s=pb.byTf.get(tf);if(s!=null)e.putString("pb_"+tf,s.compact());}
        e.apply();
    }

    private void saveEntryDecision(EntryDecision d){
        p.edit().putString("trendRegime",d.regime).putString("plannedSide",d.side==null?"":d.side).putString("entrySetupState",d.state)
         .putString("volatilityRegime",d.volatility).putLong("volatilityRatio",Double.doubleToRawLongBits(d.volatilityRatio)).putInt("volatilityExpansionScore",d.expansionScore)
         .putInt("entryAttackScore",d.attackScore).putString("entryAttackReason",d.attackReason).putString("entryStyle",d.attackEntry?"先行攻め":"通常").putBoolean("entryExploration",d.explorationEntry).putInt("entryLearningBonus",d.learningBonus)
         .putInt("allTimeframeScore",d.allTimeframeScore).putInt("pbAllScore",d.pbAllScore).putInt("pbLowerScore",d.pbLowerScore).putInt("pbUpperRisk",d.pbUpperRisk).putInt("pbTopDownScore",d.pbTopDownScore).putBoolean("pbLowerLead",d.pbLowerLead).putBoolean("lowerTimeframeLead",d.lowerTimeframeLead).putBoolean("counterTrendEntry",d.counterTrendEntry).putString("contextSide",d.contextSide)
         .putString("technicalSummary",d.summary).putInt("technicalScore",d.technicalScore).putString("technicalRegime",d.technicalRegime).remove("_tmpTechnicalScore").remove("_tmpTechnicalRegime").remove("_tmpTechnicalDanger").remove("_tmpBearConfluence").remove("_tmpBullConfluence").apply();
    }

    private String open(MarketEngine.Analysis a,Map<String,List<MarketEngine.Candle>> raw,EntryDecision decision){
        if(positionCount()>=MAX_POSITIONS||decision==null||decision.side==null)return null;
        if(decision.attackEntry&&attackPositionCount()>=MAX_ATTACK_POSITIONS){p.edit().putString("lastOpenBlock","attackCap").apply();return null;}
        String base=decision.side,side="up".equals(base)?"long":"short";
        List<MarketEngine.Candle> m5=raw.get("M5"),m15=raw.get("M15");if(m5==null||m5.isEmpty()||m15==null||m15.isEmpty())return null;
        MarketEngine.Candle last=m5.get(m5.size()-1);if(p.getLong("lastEntryBar",0)>=last.timeMs)return null;
        double atr=MarketEngine.atr(m15,14);if(Double.isNaN(atr))atr=.10;
        String vol=decision.volatility;double stopMul="低ボラ".equals(vol)?1.25:"高ボラ".equals(vol)?1.65:1.45;
        double rr="低ボラ".equals(vol)?1.50:"高ボラ".equals(vol)?2.00:1.80;
        double riskFactor="低ボラ".equals(vol)?.70:"高ボラ".equals(vol)?.75:1.00;
        if(decision.attackEntry){stopMul=Math.max(stopMul,1.35);rr=Math.max(rr,1.80);double attackRisk=adaptiveAttackRiskPct()/100.0;if(decision.explorationEntry)attackRisk=Math.min(attackRisk,EXPLORATION_RISK_PCT/100.0);riskFactor=Math.max(.15,Math.min(.60,attackRisk));}
        if(decision.counterTrendEntry){riskFactor*=decision.pbUpperRisk>=75?.55:.65;rr=Math.min(rr,1.50);}
        double stopDist=Math.max(.05,atr*stopMul),targetDist=stopDist*rr,risk=Math.max(500,balance()*.01)*riskFactor;
        double rawUnits=Math.floor((risk/stopDist)/UNIT_STEP)*UNIT_STEP;int u=(int)Math.max(MIN_UNITS,Math.min((double)(Integer.MAX_VALUE-UNIT_STEP),rawUnits));
        double actualRisk=stopDist*u;if(openRisk()+actualRisk>maxPortfolioRisk()+1e-6)return null;
        int projectedLong=longUnits()+("long".equals(side)?u:0),projectedShort=shortUnits()+("short".equals(side)?u:0);
        double projectedRequired=requiredMarginFor(last.c,projectedLong,projectedShort);
        double projectedNetAssets=netAssets(last.c)-SPREAD*u;
        if(projectedNetAssets+1e-6<projectedRequired){p.edit().putString("lastOpenBlock","margin").apply();return null;}
        double en="long".equals(side)?askFromMid(last.c):bidFromMid(last.c),st="long".equals(side)?en-stopDist:en+stopDist,tg="long".equals(side)?en+targetDist:en-targetDist;
        int entryReady=tradeReadiness(a,base),entryAgree=tradeAgreement(a,base),entryBg=backgroundRisk(a,base);
        try{
            JSONObject x=new JSONObject();x.put("id",System.currentTimeMillis()+"-"+positionCount()).put("dir",side).put("entry",en).put("stop",st).put("target",tg).put("units",u)
             .put("openedAt",System.currentTimeMillis()).put("openBar",last.timeMs).put("entryReadiness",entryReady).put("entryAgreement",entryAgree)
             .put("entryBackgroundRisk",entryBg).put("entryAtr",atr).put("entryStopDist",stopDist).put("entryTrendRegime",decision.regime)
             .put("entryVolatilityRegime",decision.volatility).put("entryVolatilityRatio",decision.volatilityRatio).put("entryVolatilityExpansionScore",decision.expansionScore)
             .put("entryAttackScore",decision.attackScore).put("entryAttackReason",decision.attackReason).put("entryMode",decision.attackEntry?"先行攻め":"通常").put("entryExploration",decision.explorationEntry).put("entryLearningBonus",decision.learningBonus).put("entryIndicatorAgreement",indicatorAgreement())
             .put("entryAllTimeframeScore",decision.allTimeframeScore).put("entryPbAllScore",decision.pbAllScore).put("entryPbLowerScore",decision.pbLowerScore).put("entryPbUpperRisk",decision.pbUpperRisk).put("entryPbTopDownScore",decision.pbTopDownScore).put("entryPbLowerLead",decision.pbLowerLead).put("entryLowerTimeframeLead",decision.lowerTimeframeLead).put("entryCounterTrend",decision.counterTrendEntry).put("entryContextSide",decision.contextSide)
             .put("entryTechnicalScore",decision.technicalScore).put("entryTechnicalRegime",decision.technicalRegime).put("entryTechnical",decision.summary).put("entryPeakBottom",peakBottomSummary());
            for(String k:MarketEngine.TF){MarketEngine.TfResult r=a.tf.get(k);x.put("entryTf_"+k,r.direction).put("entryTf_"+k+"_strength",r.strength);}
            for(String k:EXTRA_TF)x.put("entryTf_"+k,tfDir(k)).put("entryTf_"+k+"_strength",tfStrength(k));
            JSONArray ps=positions();ps.put(x);savePositions(ps);p.edit().putLong("lastEntryBar",last.timeMs).apply();
            return (decision.explorationEntry?"先行エントリー（探索） ":decision.attackEntry?"先行エントリー ":"")+("long".equals(side)?"ロング":"ショート")+" 新規エントリー "+String.format(Locale.JAPAN,"%,d通貨",u)+" / テクニカル "+decision.technicalScore+"%"+(decision.attackEntry?" / 先行点"+decision.attackScore+"点・"+decision.attackReason:"")+" / ボラ予兆"+decision.expansionScore+"点 / "+decision.volatility;
        }catch(Exception e){return null;}
    }

    private VolatilitySnapshot volatilitySnapshot(List<MarketEngine.Candle> rows){
        VolatilitySnapshot v=new VolatilitySnapshot();if(rows==null||rows.size()<46)return v;
        int end=Math.max(18,rows.size()-2);double a=atrAt(rows,14,end);if(Double.isNaN(a)||a<=0)return v;
        double sum=0;int n=0;for(int i=Math.max(15,end-30);i<end;i++){double x=atrAt(rows,14,i);if(!Double.isNaN(x)&&x>0){sum+=x;n++;}}
        double avg=n==0?a:sum/n,ratio=avg<=1e-9?1.0:a/avg,tr=trueRangeAt(rows,end);
        double prevAtr=atrAt(rows,14,Math.max(14,end-3));double atrSlope=Double.isNaN(prevAtr)||prevAtr<=1e-9?0:(a/prevAtr-1.0);
        double recent=0,prior=0;int rn=0,pn=0;
        for(int i=Math.max(1,end-2);i<=end;i++){double x=trueRangeAt(rows,i);if(!Double.isNaN(x)){recent+=x;rn++;}}
        for(int i=Math.max(1,end-8);i<=end-3;i++){double x=trueRangeAt(rows,i);if(!Double.isNaN(x)){prior+=x;pn++;}}
        double rangeRatio=(rn==0||pn==0||prior<=1e-9)?1.0:(recent/rn)/(prior/pn);
        v.atr=a;v.trueRange=tr;v.ratio=ratio;v.atrSlope=atrSlope;v.rangeRatio=rangeRatio;
        if(ratio>=1.60||(!Double.isNaN(tr)&&tr>=a*2.20))v.regime="急拡大";
        else if(ratio>=1.25)v.regime="高ボラ";
        else if(ratio<.75)v.regime="低ボラ";
        else v.regime="通常ボラ";
        int score=0;
        if(ratio>=.85)score+=15;if(ratio>=1.00)score+=10;if(ratio>=1.12)score+=10;
        if(atrSlope>=.04)score+=15;if(atrSlope>=.10)score+=15;if(atrSlope>=.18)score+=10;
        if(rangeRatio>=1.10)score+=10;if(rangeRatio>=1.30)score+=10;if(!Double.isNaN(tr)&&tr>=a*1.35)score+=10;
        v.expansionScore=Math.min(100,score);v.expansionLikely=!"急拡大".equals(v.regime)&&v.expansionScore>=55;
        return v;
    }
    private double atrAt(List<MarketEngine.Candle> rows,int period,int end){
        if(rows==null||end<period||end>=rows.size())return Double.NaN;double sum=0;for(int i=end-period+1;i<=end;i++)sum+=trueRangeAt(rows,i);return sum/period;
    }
    private double trueRangeAt(List<MarketEngine.Candle> rows,int i){
        if(rows==null||i<=0||i>=rows.size())return Double.NaN;MarketEngine.Candle x=rows.get(i),p0=rows.get(i-1);return Math.max(x.h-x.l,Math.max(Math.abs(x.h-p0.c),Math.abs(x.l-p0.c)));
    }
    private String fmtRatio(double x){return Double.isNaN(x)?"--":String.format(Locale.JAPAN,"%.2f倍",x);}

    private int attackPositionCount(){JSONArray a=positions();int n=0;for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&"先行攻め".equals(x.optString("entryMode","")))n++;}return n;}

    private String trendRegime(MarketEngine.Analysis a){
        String[] keys={"H1","H2","H4","H8","D","W","MN"};double[] w={.28,.24,.22,.12,.08,.04,.02};double score=0;int coreUp=0,coreDown=0;
        for(int i=0;i<keys.length;i++){
            String dir=(a.tf.containsKey(keys[i])?a.tf.get(keys[i]).direction:tfDir(keys[i]));
            int strength=(a.tf.containsKey(keys[i])?a.tf.get(keys[i]).strength:tfStrength(keys[i]));
            if("up".equals(dir))score+=w[i]*Math.max(20,strength);else if("down".equals(dir))score-=w[i]*Math.max(20,strength);
            if(i<3){if("up".equals(dir))coreUp++;else if("down".equals(dir))coreDown++;}
        }
        if(coreUp>=2&&score>=12)return "up";if(coreDown>=2&&score<=-12)return "down";return "neutral";
    }

    private int peakBottomDirectionScore(PeakBottomEngine.MultiTimeframe pb,String side,String[] keys,double[] weights){
        if(pb==null||(!"up".equals(side)&&!"down".equals(side)))return 0;
        int wanted="up".equals(side)?PeakBottomEngine.UP:PeakBottomEngine.DOWN;double score=0,total=0;
        for(int i=0;i<keys.length&&i<weights.length;i++){
            PeakBottomEngine.State s=pb.byTf.get(keys[i]);if(s==null)continue;double w=weights[i];total+=w;
            if(s.waveDir==wanted)score+=w*.80;else if(s.waveDir==PeakBottomEngine.NEUTRAL)score+=w*.15;
            if(s.structureDir==wanted)score+=w*.20;else if(s.structureDir==PeakBottomEngine.NEUTRAL)score+=w*.05;
        }
        return total<=0?0:Math.max(0,Math.min(100,(int)Math.round(score*100.0/total)));
    }
    private String peakBottomLowerSide(PeakBottomEngine.MultiTimeframe pb){
        String[] k={"M5","M15","M30"};double[] w={.45,.35,.20};
        int up=peakBottomDirectionScore(pb,"up",k,w),down=peakBottomDirectionScore(pb,"down",k,w);
        if(Math.max(up,down)<50||Math.abs(up-down)<10)return null;return up>down?"up":"down";
    }
    private String peakBottomAllSide(PeakBottomEngine.MultiTimeframe pb){
        int up=peakBottomDirectionScore(pb,"up",DISPLAY_TF,new double[]{.18,.17,.15,.13,.10,.09,.07,.05,.035,.025});
        int down=peakBottomDirectionScore(pb,"down",DISPLAY_TF,new double[]{.18,.17,.15,.13,.10,.09,.07,.05,.035,.025});
        if(Math.max(up,down)<45||Math.abs(up-down)<8)return null;return up>down?"up":"down";
    }

    private static final class PbHoldItem {
        String tf,text;int pressure,confidence;long remaining;
        PbHoldItem(String tf,String text,int pressure,int confidence,long remaining){this.tf=tf;this.text=text;this.pressure=pressure;this.confidence=confidence;this.remaining=remaining;}
    }
    private void updatePeakBottomHoldGuides(Map<String,List<MarketEngine.Candle>> raw){
        if(raw==null)return;
        ArrayList<PbHoldItem> peaks=new ArrayList<>(),bottoms=new ArrayList<>();
        for(String tf:DISPLAY_TF){
            PeakBottomEngine.TurnEstimate e=PeakBottomEngine.estimateNextTurn(tf,raw);
            if(e==null||!e.available)continue;
            int pressure;
            String label;
            if(e.candidateActive){
                int finality=Math.max(20,100-e.contextSupport);
                pressure=Math.max(0,Math.min(100,(int)Math.round(e.candidateMaturity*e.confidence*finality/10000.0)));
                String turn=e.candidatePeak?"ピーク":"ボトム";
                label=tfDisplayName(tf)+"："+turn+"形成中";
                if(e.contextSummary!=null&&!e.contextSummary.isEmpty())label+="\n"+e.contextSummary;
                if(e.nextOppAvailable){
                    String next=e.candidatePeak?"ボトム":"ピーク";
                    label+="\n次の"+next+"予想到達"+formatPbArrival(e.nextOppRemainingMinutes)+"（目安"+formatPbRange(e.nextOppLowMinutes,e.nextOppHighMinutes)+"）";
                }
            }else{
                pressure=Math.max(0,Math.min(100,(int)Math.round(e.progress*e.confidence/100.0)));
                String turn=e.targetPeak?"ピーク":"ボトム";
                label=tfDisplayName(tf)+"：次の"+turn+"予想到達"+formatPbArrival(e.remainingMinutes)+"（目安"+formatPbRange(e.lowMinutes,e.highMinutes)+"）";
            }
            PbHoldItem item=new PbHoldItem(tf,label,pressure,e.confidence,e.remainingMinutes);
            if(e.targetPeak)peaks.add(item);else bottoms.add(item);
        }
        savePbHoldSide("long",peaks);savePbHoldSide("short",bottoms);
    }
    private void savePbHoldSide(String dir,ArrayList<PbHoldItem> items){
        items.sort((a,b)->{int x=Long.compare(a.remaining,b.remaining);return x!=0?x:Integer.compare(b.confidence,a.confidence);});
        StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(3,items.size());i++){if(b.length()>0)b.append("\n");b.append(items.get(i).text);}
        int pressure=0,near=0;
        for(PbHoldItem x:items){
            int idx=Arrays.asList(DISPLAY_TF).indexOf(x.tf);
            if(idx>=0&&idx<=5){pressure=Math.max(pressure,x.pressure);if(x.pressure>=60)near++;}
        }
        if(near>=2)pressure=Math.min(100,pressure+8);
        String key="long".equals(dir)?"Long":"Short";
        p.edit().putString("pbHold"+key+"Guide",b.length()==0?"PB周期を学習中（同種転換3周期以上で時間目安を表示）":b.toString()).putInt("pbHold"+key+"Pressure",pressure).apply();
    }
    private String formatPbArrival(long min){
        if(min<=0)return "はまもなく";
        return "まで"+formatPbDuration(min);
    }
    private String formatPbRange(long low,long high){
        low=Math.max(0,low);high=Math.max(low,high);
        if(low==high)return formatPbDurationPlain(low)+"後";
        if(high<180)return (low==0?"今":String.valueOf(low))+"〜"+high+"分後";
        if(low>=60&&high<1440){
            double lo=low/60.0,hi=high/60.0;
            return formatRangeNumber(lo)+"〜"+formatRangeNumber(hi)+"時間後";
        }
        if(low>=1440){
            double lo=low/1440.0,hi=high/1440.0;
            return formatRangeNumber(lo)+"〜"+formatRangeNumber(hi)+"日後";
        }
        return (low==0?"今":formatPbDurationPlain(low))+"〜"+formatPbDurationPlain(high)+"後";
    }
    private String formatRangeNumber(double v){
        return Math.abs(v-Math.rint(v))<1e-9?String.valueOf((long)Math.rint(v)):String.format(Locale.JAPAN,"%.1f",v);
    }
    private String formatPbDuration(long min){return "約"+formatPbDurationPlain(min);}
    private String formatPbDurationPlain(long min){
        if(min<60)return min+"分";
        if(min<1440){double h=min/60.0;return h<10?String.format(Locale.JAPAN,"%.1f時間",h):Math.round(h)+"時間";}
        double d=min/1440.0;return d<10?String.format(Locale.JAPAN,"%.1f日",d):Math.round(d)+"日";
    }
    private String tfDisplayName(String tf){
        if("M5".equals(tf))return "5分足";if("M15".equals(tf))return "15分足";if("M30".equals(tf))return "30分足";
        if("H1".equals(tf))return "1時間足";if("H2".equals(tf))return "2時間足";if("H4".equals(tf))return "4時間足";if("H8".equals(tf))return "8時間足";
        if("D".equals(tf))return "日足";if("W".equals(tf))return "週足";if("MN".equals(tf))return "月足";return tf;
    }
    public String peakBottomHoldGuide(String dir){return p.getString("long".equals(dir)?"pbHoldLongGuide":"pbHoldShortGuide","PB周期を学習中");}
    public int peakBottomTurnPressure(String dir){return p.getInt("long".equals(dir)?"pbHoldLongPressure":"pbHoldShortPressure",0);}

    private String candidateSideStored(){
        String planned=p.getString("plannedSide","");if("up".equals(planned)||"down".equals(planned))return planned;
        String regime=trendRegime();if("up".equals(regime)||"down".equals(regime))return regime;
        return null;
    }
    private String analysisTfDir(MarketEngine.Analysis a,String k){return a!=null&&a.tf.containsKey(k)?a.tf.get(k).direction:tfDir(k);}
    private int analysisTfStrength(MarketEngine.Analysis a,String k){return a!=null&&a.tf.containsKey(k)?a.tf.get(k).strength:tfStrength(k);}
    private int allTimeframeDirectionScore(MarketEngine.Analysis a,String side){
        String[] keys=DISPLAY_TF;double[] w={.18,.17,.15,.13,.10,.09,.07,.05,.035,.025};double score=0;
        for(int i=0;i<keys.length;i++){String d=analysisTfDir(a,keys[i]);int s=analysisTfStrength(a,keys[i]);if(side.equals(d))score+=w[i]*Math.max(20,s);else if("neutral".equals(d))score+=w[i]*Math.max(20,s)*.20;}
        return Math.max(0,Math.min(100,(int)Math.round(score)));
    }
    private String allTimeframeSide(MarketEngine.Analysis a){
        int up=allTimeframeDirectionScore(a,"up"),down=allTimeframeDirectionScore(a,"down");
        if(Math.max(up,down)<42||Math.abs(up-down)<8)return null;
        return up>down?"up":"down";
    }
    private String lowerTimeframeSide(MarketEngine.Analysis a){
        String m5=analysisTfDir(a,"M5"),m15=analysisTfDir(a,"M15"),m30=analysisTfDir(a,"M30");
        int s5=analysisTfStrength(a,"M5"),s15=analysisTfStrength(a,"M15"),s30=analysisTfStrength(a,"M30");
        for(String side:new String[]{"up","down"}){
            boolean mid=side.equals(m15)||side.equals(m30);
            double strength=(side.equals(m5)?s5*.45:0)+(side.equals(m15)?s15*.35:0)+(side.equals(m30)?s30*.20:0);
            if(side.equals(m5)&&mid&&strength>=42)return side;
        }
        return null;
    }
    private int tradeReadinessStored(String side){return tradeReadinessValues(side,new String[]{"M5","M15","M30","H1"},new int[]{tfStrength("M5"),tfStrength("M15"),tfStrength("M30"),tfStrength("H1")},new String[]{tfDir("M5"),tfDir("M15"),tfDir("M30"),tfDir("H1")});}
    private int tradeAgreementStored(String side){return tradeAgreementValues(side,new String[]{tfDir("M5"),tfDir("M15"),tfDir("M30"),tfDir("H1")});}
    private int backgroundRiskStored(String side){return backgroundRiskValues(side,new String[]{tfDir("H2"),tfDir("H4"),tfDir("H8"),tfDir("D"),tfDir("W"),tfDir("MN")},new int[]{tfStrength("H2"),tfStrength("H4"),tfStrength("H8"),tfStrength("D"),tfStrength("W"),tfStrength("MN")});}
    private int tradeReadiness(MarketEngine.Analysis a,String side){return tradeReadinessValues(side,new String[]{"M5","M15","M30","H1"},new int[]{a.tf.get("M5").strength,a.tf.get("M15").strength,a.tf.get("M30").strength,a.tf.get("H1").strength},new String[]{a.tf.get("M5").direction,a.tf.get("M15").direction,a.tf.get("M30").direction,a.tf.get("H1").direction});}
    private int tradeAgreement(MarketEngine.Analysis a,String side){return tradeAgreementValues(side,new String[]{a.tf.get("M5").direction,a.tf.get("M15").direction,a.tf.get("M30").direction,a.tf.get("H1").direction});}
    private int backgroundRisk(MarketEngine.Analysis a,String side){return backgroundRiskValues(side,new String[]{a.tf.get("H2").direction,a.tf.get("H4").direction,a.tf.get("H8").direction,a.tf.get("D").direction,tfDir("W"),tfDir("MN")},new int[]{a.tf.get("H2").strength,a.tf.get("H4").strength,a.tf.get("H8").strength,a.tf.get("D").strength,tfStrength("W"),tfStrength("MN")});}
    private int tradeReadinessValues(String side,String[] keys,int[] strength,String[] dirs){double[] w={.20,.30,.30,.20};double score=0;for(int i=0;i<dirs.length;i++){if(side.equals(dirs[i]))score+=w[i]*strength[i];else if("neutral".equals(dirs[i]))score+=w[i]*strength[i]*.30;}return Math.max(0,Math.min(100,(int)Math.round(score)));}
    private int tradeAgreementValues(String side,String[] dirs){int[] w={20,30,30,20};int score=0;for(int i=0;i<dirs.length;i++){if(side.equals(dirs[i]))score+=w[i];else if("neutral".equals(dirs[i]))score+=w[i]/2;}return Math.max(0,Math.min(100,score));}
    private int backgroundRiskValues(String side,String[] dirs,int[] strength){String opp="up".equals(side)?"down":"up";double[] w={.25,.20,.18,.17,.12,.08};double score=0;for(int i=0;i<dirs.length&&i<w.length;i++)if(opp.equals(dirs[i]))score+=w[i]*strength[i];return Math.max(0,Math.min(100,(int)Math.round(score)));}

    private String technicalBundle(Map<String,List<MarketEngine.Candle>> raw,String side){
        String[] keys=DISPLAY_TF;
        double[] weight={.24,.20,.15,.11,.08,.07,.05,.04,.035,.025};
        SharedPreferences.Editor tfEdit=p.edit();
        double longScore=0,shortScore=0,usedWeight=0,agreeSum=0,agreeWeight=0,upMode=0,downMode=0,rangeMode=0;
        boolean bearDanger=false,bullDanger=false,shortBearConfluence=false,shortBullConfluence=false;
        int rapidRisk=0;String rapidDir="neutral";StringBuilder b=new StringBuilder();

        for(int i=0;i<keys.length;i++){
            String k=keys[i];List<MarketEngine.Candle> rows=raw.get(k);
            if(rows==null||rows.size()<24)continue;
            TechnicalSignal t=technicalSignal(rows);double w=weight[i];usedWeight+=w;
            longScore+=w*t.longScore;shortScore+=w*t.shortScore;
            if("上昇トレンド".equals(t.regime))upMode+=w;else if("下降トレンド".equals(t.regime))downMode+=w;else rangeMode+=w;
            int agree=indicatorAgreementOf(t);agreeSum+=w*agree;agreeWeight+=w;
            int rr=rapidRiskOf(t);tfEdit.putInt("indicatorAgreement_"+k,agree).putInt("rapidMoveRisk_"+k,rr).putString("rapidMoveDirection_"+k,rapidDirectionOf(t));
            if(rr>rapidRisk){rapidRisk=rr;rapidDir=rapidDirectionOf(t);}

            if(i<3){
                // トレンド中の「買われすぎ/売られすぎ」だけでは逆張りしない。
                bearDanger|=t.bearConfluence||("レンジ".equals(t.regime)&&t.overboughtTurn&&t.bearCandle);
                bullDanger|=t.bullConfluence||("レンジ".equals(t.regime)&&t.oversoldTurn&&t.bullCandle);
                shortBearConfluence|=t.bearConfluence;shortBullConfluence|=t.bullConfluence;
            }

            if(b.length()>0)b.append(" / ");
            if(i<3){
                b.append(k).append(" ").append(t.regime).append(" RCI ").append(fmt0(t.rci9)).append("/").append(fmt0(t.rci26)).append("/").append(fmt0(t.rci52));
                b.append(" MACD ").append(t.dead?"DC":t.golden?"GC":t.hist>=0?"上":"下");
                b.append(" 総合").append("up".equals(side)?t.longScore:"down".equals(side)?t.shortScore:Math.max(t.longScore,t.shortScore)).append("%");
            }else{
                b.append(k).append(" ").append(t.regime).append(" 指標一致").append(agree).append("%");
            }
        }
        tfEdit.apply();

        int score;
        if(usedWeight<=0)score=0;
        else if("up".equals(side))score=(int)Math.round(longScore/usedWeight);
        else if("down".equals(side))score=(int)Math.round(shortScore/usedWeight);
        else score=(int)Math.round(Math.max(longScore,shortScore)/usedWeight);
        int indicatorAgreement=agreeWeight<=0?0:(int)Math.round(agreeSum/agreeWeight);
        String mode=upMode>downMode&&upMode>rangeMode?"上昇トレンド":downMode>upMode&&downMode>rangeMode?"下降トレンド":"レンジ";
        boolean danger="up".equals(side)?bearDanger:"down".equals(side)?bullDanger:false;
        p.edit().putInt("_tmpTechnicalScore",Math.max(0,Math.min(100,score))).putString("_tmpTechnicalRegime",mode).putBoolean("_tmpTechnicalDanger",danger)
         .putBoolean("_tmpBearConfluence",shortBearConfluence).putBoolean("_tmpBullConfluence",shortBullConfluence)
         .putInt("indicatorAgreement",Math.max(0,Math.min(100,indicatorAgreement))).putInt("rapidMoveRisk",Math.max(0,Math.min(100,rapidRisk))).putString("rapidMoveDirection",rapidDir).apply();
        return "指標環境 "+mode+" / "+b.toString();
    }

    private String opposingRapidWarning(String side,String... keys){
        String opp="up".equals(side)?"down":"up";String bestTf=null;int best=0;
        for(String k:keys){int risk=rapidMoveRisk(k);if(opp.equals(rapidMoveDirection(k))&&risk>best){best=risk;bestTf=k;}}
        return bestTf!=null&&best>=65?bestTf+" 急変警戒 "+best+"%":null;
    }
    private int indicatorAgreementOf(TechnicalSignal t){
        int best=Math.max(t.longScore,t.shortScore),gap=Math.abs(t.longScore-t.shortScore);
        if(best<40)return 25;if(gap<10)return 40;if(gap<25)return 65;if(gap<40)return 85;return 100;
    }
    private int rapidRiskOf(TechnicalSignal t){
        int r=0;if(t.rciHighCluster||t.rciLowCluster)r+=18;if(t.rciHighBreak||t.rciLowBreak)r+=27;
        if(t.golden||t.dead)r+=18;if(t.topDrop||t.bottomJump)r+=22;if(t.overboughtTurn||t.oversoldTurn)r+=15;
        if(t.bearConfluence||t.bullConfluence)r+=35;return Math.max(0,Math.min(100,r));
    }
    private String rapidDirectionOf(TechnicalSignal t){
        if(t.bearConfluence||t.rciHighBreak||t.dead||t.topDrop)return "down";
        if(t.bullConfluence||t.rciLowBreak||t.golden||t.bottomJump)return "up";return "neutral";
    }

    private TechnicalSignal technicalSignal(List<MarketEngine.Candle> rows){
        TechnicalSignal t=new TechnicalSignal();if(rows==null||rows.size()<24)return t;int n=rows.size(),i=n-1;

        // 現在の標準設定値は変更しない。ここで変えるのは読み方だけ。
        t.rci9=rciClose(rows,5,n);t.rci26=rciClose(rows,10,n);t.rci52=rciClose(rows,20,n);
        t.prev9=rciClose(rows,5,n-1);t.prev26=rciClose(rows,10,n-1);t.prev52=rciClose(rows,20,n-1);
        double[] mc=macdAt(rows,n),mp=macdAt(rows,n-1);t.macd=mc[0];t.signal=mc[1];t.hist=mc[2];t.prevMacd=mp[0];t.prevSignal=mp[1];t.prevHist=mp[2];
        t.golden=t.prevMacd<=t.prevSignal&&t.macd>t.signal;t.dead=t.prevMacd>=t.prevSignal&&t.macd<t.signal;

        double last=rows.get(i).c,prev=rows.get(i-1).c;
        t.topDrop=t.macd>0&&t.signal>0&&t.prevHist>=0&&t.hist<0&&last<prev;
        t.bottomJump=t.macd<0&&t.signal<0&&t.prevHist<=0&&t.hist>0&&last>prev;
        t.overboughtTurn=t.prev9>=80&&t.rci9<t.prev9&&t.rci9<t.rci26;
        t.oversoldTurn=t.prev9<=-80&&t.rci9>t.prev9&&t.rci9>t.rci26;

        double curMin=Math.min(t.rci9,Math.min(t.rci26,t.rci52)),curMax=Math.max(t.rci9,Math.max(t.rci26,t.rci52));
        double prevMin=Math.min(t.prev9,Math.min(t.prev26,t.prev52)),prevMax=Math.max(t.prev9,Math.max(t.prev26,t.prev52));
        t.rciHighCluster=curMin>=65&&curMax-curMin<=30;t.rciLowCluster=curMax<=-65&&curMax-curMin<=30;
        boolean prevHighCluster=prevMin>=65&&prevMax-prevMin<=30,prevLowCluster=prevMax<=-65&&prevMax-prevMin<=30;
        boolean rollingDown=t.rci9<=t.prev9-7&&t.rci26<=t.prev26-2&&t.rci52<=t.prev52+1;
        boolean rollingUp=t.rci9>=t.prev9+7&&t.rci26>=t.prev26+2&&t.rci52>=t.prev52-1;
        t.rciHighBreak=(prevHighCluster||t.rciHighCluster)&&rollingDown;t.rciLowBreak=(prevLowCluster||t.rciLowCluster)&&rollingUp;
        boolean macdBear=t.dead||t.topDrop||(t.macd>0&&t.hist<t.prevHist&&last<prev),macdBull=t.golden||t.bottomJump||(t.macd<0&&t.hist>t.prevHist&&last>prev);
        t.bearConfluence=t.rciHighBreak&&macdBear;t.bullConfluence=t.rciLowBreak&&macdBull;

        t.rsi=rsiSignalAt(rows,14,i);double rsiPrev=rsiSignalAt(rows,14,i-1);
        t.stochK=stochSignalAt(rows,14,i);t.stochD=stochDSignalAt(rows,14,3,i);
        double stKPrev=stochSignalAt(rows,14,i-1),stDPrev=stochDSignalAt(rows,14,3,i-1);
        t.cci=cciSignalAt(rows,20,i);double cciPrev=cciSignalAt(rows,20,i-1);
        t.williams=williamsSignalAt(rows,14,i);double willPrev=williamsSignalAt(rows,14,i-1);
        t.roc=rocSignalAt(rows,12,i);double rocPrev=rocSignalAt(rows,12,i-1);
        double[] dm=dmiSignalAt(rows,14,14,i);t.adx=dm[0];t.plusDi=dm[1];t.minusDi=dm[2];

        double e5=emaSignalAt(rows,5,i),e25=emaSignalAt(rows,25,i),e75=emaSignalAt(rows,75,i);
        double s5=smaSignalAt(rows,5,i),s25=smaSignalAt(rows,25,i),s75=smaSignalAt(rows,75,i);
        double atr=atrAt(rows,14,i);
        boolean emaUp=e5>e25&&e25>e75,emaDown=e5<e25&&e25<e75;
        if(!Double.isNaN(atr)&&atr>0&&t.adx>=20&&emaUp&&e5-e25>atr*.12&&t.plusDi>t.minusDi)t.regime="上昇トレンド";
        else if(!Double.isNaN(atr)&&atr>0&&t.adx>=20&&emaDown&&e25-e5>atr*.12&&t.minusDi>t.plusDi)t.regime="下降トレンド";
        else t.regime="レンジ";

        if(i>=21){double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int j=i-20;j<i;j++){hi=Math.max(hi,rows.get(j).h);lo=Math.min(lo,rows.get(j).l);}t.donchianUp=last>hi;t.donchianDown=last<lo;}
        t.bullCandle=bullReversalSignal(rows,i);t.bearCandle=bearReversalSignal(rows,i);

        int trendL=0,trendS=0;
        if(t.golden||(t.macd>t.signal&&t.hist>=t.prevHist))trendL+=25;
        if(t.dead||(t.macd<t.signal&&t.hist<=t.prevHist))trendS+=25;
        if(emaUp)trendL+=20;if(emaDown)trendS+=20;
        if(t.adx>=20&&t.plusDi>t.minusDi)trendL+=20;if(t.adx>=20&&t.minusDi>t.plusDi)trendS+=20;
        if(t.roc>0&&t.roc>=rocPrev)trendL+=10;if(t.roc<0&&t.roc<=rocPrev)trendS+=10;
        if(s5>s25&&s25>s75)trendL+=10;if(s5<s25&&s25<s75)trendS+=10;
        if(t.donchianUp)trendL+=15;if(t.donchianDown)trendS+=15;
        if(t.cci>100)trendL+=10;if(t.cci<-100)trendS+=10;
        t.trendLong=Math.min(100,trendL);t.trendShort=Math.min(100,trendS);

        boolean upTrend="上昇トレンド".equals(t.regime),downTrend="下降トレンド".equals(t.regime);
        int rciL=0,rciS=0;
        if(upTrend){if(t.rci9>t.prev9&&(t.rci9>t.rci26||t.rci26>=t.rci52)&&t.rci9>-80)rciL=30;}
        else if(downTrend){if(t.rci9<t.prev9&&(t.rci9<t.rci26||t.rci26<=t.rci52)&&t.rci9<80)rciS=30;}
        else {if(t.oversoldTurn||t.rciLowBreak)rciL=30;if(t.overboughtTurn||t.rciHighBreak)rciS=30;}

        int oscL=0,oscS=0;
        if(upTrend){if(t.rsi>=45&&t.rsi<=70&&t.rsi>=rsiPrev)oscL++;if(stKPrev<=stDPrev&&t.stochK>t.stochD&&t.stochK<55)oscL++;if(willPrev<-80&&t.williams>willPrev)oscL++;if(cciPrev<-100&&t.cci>-100)oscL++;}
        else if(downTrend){if(t.rsi<=55&&t.rsi>=30&&t.rsi<=rsiPrev)oscS++;if(stKPrev>=stDPrev&&t.stochK<t.stochD&&t.stochK>45)oscS++;if(willPrev>-20&&t.williams<willPrev)oscS++;if(cciPrev>100&&t.cci<100)oscS++;}
        else {if(rsiPrev<35&&t.rsi>rsiPrev)oscL++;if(rsiPrev>65&&t.rsi<rsiPrev)oscS++;if(stKPrev<=stDPrev&&t.stochK>t.stochD&&t.stochK<35)oscL++;if(stKPrev>=stDPrev&&t.stochK<t.stochD&&t.stochK>65)oscS++;if(t.williams<-80&&t.williams>willPrev)oscL++;if(t.williams>-20&&t.williams<willPrev)oscS++;if(cciPrev<-100&&t.cci>-100)oscL++;if(cciPrev>100&&t.cci<100)oscS++;}
        int oscLong=Math.min(25,oscL*7),oscShort=Math.min(25,oscS*7);

        double mid=smaSignalAt(rows,25,i),sd=stdSignalAt(rows,25,i),pmid=smaSignalAt(rows,25,i-1),psd=stdSignalAt(rows,25,i-1);
        boolean bbLong=false,bbShort=false;
        if("レンジ".equals(t.regime)){bbLong=prev<pmid-2*psd&&last>mid-2*sd;bbShort=prev>pmid+2*psd&&last<mid+2*sd;}
        else {bbLong=upTrend&&last>mid+2*sd;bbShort=downTrend&&last<mid-2*sd;}
        t.timingLong=Math.min(100,rciL+oscLong+(bbLong?20:0)+(t.bullCandle?15:0));
        t.timingShort=Math.min(100,rciS+oscShort+(bbShort?20:0)+(t.bearCandle?15:0));

        if(upTrend){t.longScore=Math.max(0,Math.min(100,(int)Math.round(t.trendLong*.65+t.timingLong*.35)));t.shortScore=Math.min(35,(int)Math.round(t.trendShort*.35+t.timingShort*.25));}
        else if(downTrend){t.shortScore=Math.max(0,Math.min(100,(int)Math.round(t.trendShort*.65+t.timingShort*.35)));t.longScore=Math.min(35,(int)Math.round(t.trendLong*.35+t.timingLong*.25));}
        else {t.longScore=Math.max(0,Math.min(100,(int)Math.round(t.trendLong*.20+t.timingLong*.80)));t.shortScore=Math.max(0,Math.min(100,(int)Math.round(t.trendShort*.20+t.timingShort*.80)));}

        return t;
    }

    public static final class TechnicalSnapshot {
        public String regime="レンジ",volatility="通常ボラ",summary="指標待ち",rapidDirection="neutral";
        public int longScore=0,shortScore=0,agreement=0,rapidRisk=0,entryThreshold=60;
        public boolean longReady=false,shortReady=false;
        public double atr=Double.NaN,volatilityRatio=1.0;
    }
    public TechnicalSnapshot m5TechnicalSnapshot(List<MarketEngine.Candle> rows){
        TechnicalSnapshot x=new TechnicalSnapshot();TechnicalSignal t=technicalSignal(rows);VolatilitySnapshot vol=volatilitySnapshot(rows);
        x.regime=t.regime;x.volatility=vol.regime;x.atr=vol.atr;x.volatilityRatio=vol.ratio;
        x.longScore=t.longScore;x.shortScore=t.shortScore;x.agreement=indicatorAgreementOf(t);x.rapidRisk=rapidRiskOf(t);x.rapidDirection=rapidDirectionOf(t);
        x.entryThreshold="レンジ".equals(t.regime)?60:"低ボラ".equals(vol.regime)?62:"高ボラ".equals(vol.regime)?60:55;
        boolean bearDanger=t.bearConfluence||("レンジ".equals(t.regime)&&t.overboughtTurn&&t.bearCandle);
        boolean bullDanger=t.bullConfluence||("レンジ".equals(t.regime)&&t.oversoldTurn&&t.bullCandle);
        boolean usable=!"急拡大".equals(vol.regime);
        x.longReady=usable&&!bearDanger&&t.longScore>=x.entryThreshold&&t.longScore>=t.shortScore+15;
        x.shortReady=usable&&!bullDanger&&t.shortScore>=x.entryThreshold&&t.shortScore>=t.longScore+15;
        x.summary="M5 "+t.regime+" / RCI "+String.format(Locale.JAPAN,"%.0f/%.0f/%.0f",t.rci9,t.rci26,t.rci52)+
                " / MACD "+(t.dead?"DC":t.golden?"GC":t.hist>=0?"上":"下")+" / RSI "+String.format(Locale.JAPAN,"%.0f",t.rsi)+
                " / ADX "+String.format(Locale.JAPAN,"%.0f",t.adx)+" / 指標一致 "+x.agreement+"%";
        return x;
    }

    private double rciClose(List<MarketEngine.Candle> rows,int period,int endExclusive){
        int end=Math.min(rows.size(),endExclusive);if(end<period)return Double.NaN;int start=end-period;double sum=0;
        for(int i=0;i<period;i++){
            double price=rows.get(start+i).c;int less=0,equal=0;
            for(int j=0;j<period;j++){double q=rows.get(start+j).c;if(q<price)less++;else if(Double.compare(q,price)==0)equal++;}
            double priceRank=less+(equal+1)/2.0,timeRank=i+1,d=timeRank-priceRank;sum+=d*d;
        }
        double den=period*(period*period-1.0);return den<=0?Double.NaN:100.0*(1.0-6.0*sum/den);
    }

    private double[] macdAt(List<MarketEngine.Candle> rows,int endExclusive){
        int end=Math.min(rows.size(),endExclusive);if(end<23)return new double[]{Double.NaN,Double.NaN,Double.NaN};
        double af=2.0/6.0,as=2.0/21.0,ag=2.0/10.0,fast=rows.get(0).c,slow=rows.get(0).c,signal=0,macd=0;boolean init=false;
        for(int i=0;i<end;i++){double c=rows.get(i).c;if(i>0){fast=c*af+fast*(1-af);slow=c*as+slow*(1-as);}macd=fast-slow;if(!init){signal=macd;init=true;}else signal=macd*ag+signal*(1-ag);}
        return new double[]{macd,signal,macd-signal};
    }
    private double emaSignalAt(List<MarketEngine.Candle> r,int p,int end){int e=Math.min(end,r.size()-1);if(e<0)return Double.NaN;int st=Math.max(0,e-p*5);double a=2.0/(p+1),v=r.get(st).c;for(int j=st+1;j<=e;j++)v=r.get(j).c*a+v*(1-a);return v;}
    private double smaSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end+1<p||end>=r.size())return Double.NaN;double s=0;for(int j=end-p+1;j<=end;j++)s+=r.get(j).c;return s/p;}
    private double stdSignalAt(List<MarketEngine.Candle> r,int p,int end){double m=smaSignalAt(r,p,end);if(Double.isNaN(m))return 0;double s=0;for(int j=end-p+1;j<=end;j++){double d=r.get(j).c-m;s+=d*d;}return Math.sqrt(s/p);}
    private double rsiSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end<p||end>=r.size())return 50;double g=0,l=0;for(int j=end-p+1;j<=end;j++){double d=r.get(j).c-r.get(j-1).c;if(d>0)g+=d;else l-=d;}return l==0?100:100-100/(1+g/l);}
    private double stochSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end+1<p||end>=r.size())return 50;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int j=end-p+1;j<=end;j++){hi=Math.max(hi,r.get(j).h);lo=Math.min(lo,r.get(j).l);}return hi<=lo?50:100*(r.get(end).c-lo)/(hi-lo);}
    private double stochDSignalAt(List<MarketEngine.Candle> r,int kp,int dp,int end){if(end<dp-1)return 50;double z=0;for(int j=end-dp+1;j<=end;j++)z+=stochSignalAt(r,kp,j);return z/dp;}
    private double cciSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end+1<p||end>=r.size())return 0;double m=0;for(int j=end-p+1;j<=end;j++)m+=(r.get(j).h+r.get(j).l+r.get(j).c)/3;m/=p;double dev=0;for(int j=end-p+1;j<=end;j++)dev+=Math.abs((r.get(j).h+r.get(j).l+r.get(j).c)/3-m);dev/=p;double tp=(r.get(end).h+r.get(end).l+r.get(end).c)/3;return dev<=1e-9?0:(tp-m)/(.015*dev);}
    private double williamsSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end+1<p||end>=r.size())return -50;double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;for(int j=end-p+1;j<=end;j++){hi=Math.max(hi,r.get(j).h);lo=Math.min(lo,r.get(j).l);}return hi<=lo?-50:-100*(hi-r.get(end).c)/(hi-lo);}
    private double rocSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end<p||end>=r.size())return 0;double b=r.get(end-p).c;return Math.abs(b)<1e-9?0:100*(r.get(end).c-b)/b;}
    private double[] dmiSignalAt(List<MarketEngine.Candle> r,int p,int adxP,int end){if(end<Math.max(p,adxP*2)||end>=r.size())return new double[]{0,0,0};double[] cur=diSignalAt(r,p,end);double sum=0;for(int e=end-adxP+1;e<=end;e++)sum+=diSignalAt(r,p,e)[2];return new double[]{sum/adxP,cur[0],cur[1]};}
    private double[] diSignalAt(List<MarketEngine.Candle> r,int p,int end){if(end<p||end>=r.size())return new double[]{0,0,0};double tr=0,pd=0,md=0;for(int j=end-p+1;j<=end;j++){MarketEngine.Candle x=r.get(j),q=r.get(j-1);double up=x.h-q.h,dn=q.l-x.l;pd+=up>dn&&up>0?up:0;md+=dn>up&&dn>0?dn:0;tr+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-q.c),Math.abs(x.l-q.c)));}if(tr<=1e-9)return new double[]{0,0,0};double plus=100*pd/tr,minus=100*md/tr,den=plus+minus,dx=den<=1e-9?0:100*Math.abs(plus-minus)/den;return new double[]{plus,minus,dx};}
    private boolean bullReversalSignal(List<MarketEngine.Candle> r,int i){if(i<1)return false;MarketEngine.Candle p0=r.get(i-1),x=r.get(i);double range=Math.max(.00001,x.h-x.l),body=Math.abs(x.c-x.o),lower=Math.min(x.o,x.c)-x.l;return (p0.c<p0.o&&x.c>x.o&&x.o<=p0.c&&x.c>=p0.o)||(body<=range*.4&&lower>=range*.5);}
    private boolean bearReversalSignal(List<MarketEngine.Candle> r,int i){if(i<1)return false;MarketEngine.Candle p0=r.get(i-1),x=r.get(i);double range=Math.max(.00001,x.h-x.l),body=Math.abs(x.c-x.o),upper=x.h-Math.max(x.o,x.c);return (p0.c>p0.o&&x.c<x.o&&x.o>=p0.c&&x.c<=p0.o)||(body<=range*.4&&upper>=range*.5);}

    private String fmt0(double v){return Double.isNaN(v)?"--":String.format(Locale.JAPAN,"%.0f",v);}

    public synchronized Action fastTick(double mid){
        Action action=new Action();if(Double.isNaN(mid))return action;
        JSONArray ps=positions();if(ps.length()==0)return action;ArrayList<String> events=new ArrayList<>();
        double ratio=marginMaintenanceRatio(mid);
        if(!Double.isNaN(ratio)&&ratio<LIQUIDATION_LEVEL_PCT){
            for(int i=ps.length()-1;i>=0;i--){JSONObject pos=ps.optJSONObject(i);if(pos==null)continue;String dir=pos.optString("dir","");double exit="long".equals(dir)?bidFromMid(mid):askFromMid(mid);events.add(closePosition(pos,exit,"リアルタイムロスカット（証拠金維持率50%割れ）",null));}
            savePositions(new JSONArray());
        }else{
            for(int i=ps.length()-1;i>=0;i--){
                JSONObject pos=ps.optJSONObject(i);if(pos==null)continue;String dir=pos.optString("dir","");double st=pos.optDouble("stop",Double.NaN),tg=pos.optDouble("target",Double.NaN);
                double mark="long".equals(dir)?bidFromMid(mid):askFromMid(mid);String reason=null;
                if("long".equals(dir)){if(!Double.isNaN(st)&&mark<=st)reason="リアルタイム損切り";else if(!Double.isNaN(tg)&&mark>=tg)reason="リアルタイム利確";}
                else{if(!Double.isNaN(st)&&mark>=st)reason="リアルタイム損切り";else if(!Double.isNaN(tg)&&mark<=tg)reason="リアルタイム利確";}
                if(reason!=null){events.add(closePosition(pos,mark,reason,null));ps.remove(i);}
            }
            savePositions(ps);
        }
        if(!events.isEmpty()){StringBuilder b=new StringBuilder();for(String e:events){if(b.length()>0)b.append(" / ");b.append(e);}action.message=b.toString();action.notable=true;p.edit().putString("lastAction",action.message).apply();}
        return action;
    }

    private ArrayList<String> checkMarginLiquidation(MarketEngine.Analysis a,Map<String,List<MarketEngine.Candle>> raw){
        ArrayList<String> out=new ArrayList<>();JSONArray ps=positions();if(ps.length()==0)return out;
        List<MarketEngine.Candle> bars=raw.get("M5");if(bars==null||bars.isEmpty())return out;
        double mid=bars.get(bars.size()-1).c,ratio=marginMaintenanceRatio(mid);
        if(Double.isNaN(ratio)||ratio>=LIQUIDATION_LEVEL_PCT)return out;
        int count=ps.length();
        for(int i=ps.length()-1;i>=0;i--){
            JSONObject pos=ps.optJSONObject(i);if(pos==null)continue;
            String dir=pos.optString("dir","");double exit="long".equals(dir)?bidFromMid(mid):askFromMid(mid);
            closePosition(pos,exit,"ロスカット（証拠金維持率50%割れ）",a);
        }
        savePositions(new JSONArray());
        out.add("ロスカット発動：証拠金維持率"+String.format(Locale.JAPAN,"%.1f%%",ratio)+" / "+count+"ポジションを全決済");
        return out;
    }

    private ArrayList<String> checkExits(MarketEngine.Analysis a,Map<String,List<MarketEngine.Candle>> raw){
        ArrayList<String> out=new ArrayList<>();JSONArray ps=positions();List<MarketEngine.Candle> bars=raw.get("M5");
        if(bars==null||bars.isEmpty())return out;double mid=bars.get(bars.size()-1).c;
        for(int i=ps.length()-1;i>=0;i--){
            JSONObject pos=ps.optJSONObject(i);if(pos==null)continue;String dir=pos.optString("dir","");double st=pos.optDouble("stop",Double.NaN),tg=pos.optDouble("target",Double.NaN);long ob=pos.optLong("openBar",0);
            Double exit=null;String reason=null;
            for(MarketEngine.Candle r:bars){
                if(r.timeMs<=ob)continue;
                double exitLow="long".equals(dir)?bidFromMid(r.l):askFromMid(r.l),exitHigh="long".equals(dir)?bidFromMid(r.h):askFromMid(r.h);
                boolean sh="long".equals(dir)?exitLow<=st:exitHigh>=st,th="long".equals(dir)?exitHigh>=tg:exitLow<=tg;
                if(sh&&th){exit=st;reason="同一5分足で両方到達→保守的に損切り";break;}
                if(sh){exit=st;reason="損切り";break;}if(th){exit=tg;reason="利確";break;}
            }
            if(exit==null&&System.currentTimeMillis()-pos.optLong("openedAt",0)>=48L*3600_000L){exit="long".equals(dir)?bidFromMid(mid):askFromMid(mid);reason="48時間経過";}
            if(exit!=null){out.add(closePosition(pos,exit,reason,a));ps.remove(i);}
        }
        savePositions(ps);return out;
    }

    private String closePosition(JSONObject pos,double exit,String reason,MarketEngine.Analysis a){
        String dir=pos.optString("dir","");double en=pos.optDouble("entry",Double.NaN),st=pos.optDouble("stop",Double.NaN),tg=pos.optDouble("target",Double.NaN);int u=pos.optInt("units",0);long opened=pos.optLong("openedAt",0);
        double pnl=("long".equals(dir)?exit-en:en-exit)*u;double riskYen=Math.abs(en-st)*Math.max(0,u);double rMultiple=riskYen>1e-9?pnl/riskYen:0;double bal=balance()+pnl;int w=wins(),l=losses();if(pnl>0)w++;else l++;int c=closed()+1;
        JSONArray arr;try{arr=new JSONArray(p.getString("trades","[]"));}catch(Exception e){arr=new JSONArray();}
        JSONObject t=new JSONObject();String review=buildReview(pos,exit,pnl,reason,a);
        try{
            t.put("dir",dir).put("entry",en).put("exit",exit).put("stop",st).put("target",tg).put("units",u).put("pnl",pnl).put("riskYen",riskYen).put("rMultiple",rMultiple).put("reason",reason).put("closedAt",System.currentTimeMillis()).put("openedAt",opened)
             .put("durationMin",Math.max(0,(System.currentTimeMillis()-opened)/60000)).put("entryReadiness",pos.optInt("entryReadiness",0))
             .put("entryAgreement",pos.optInt("entryAgreement",0)).put("entryBackgroundRisk",pos.optInt("entryBackgroundRisk",0))
             .put("entryTrendRegime",pos.optString("entryTrendRegime","neutral")).put("entryVolatilityRegime",pos.optString("entryVolatilityRegime","通常ボラ"))
             .put("entryVolatilityExpansionScore",pos.optInt("entryVolatilityExpansionScore",0)).put("entryAttackScore",pos.optInt("entryAttackScore",0)).put("entryAttackReason",pos.optString("entryAttackReason",""))
             .put("entryAllTimeframeScore",pos.optInt("entryAllTimeframeScore",0)).put("entryPbAllScore",pos.optInt("entryPbAllScore",0)).put("entryPbLowerScore",pos.optInt("entryPbLowerScore",0)).put("entryPbUpperRisk",pos.optInt("entryPbUpperRisk",0)).put("entryPbTopDownScore",pos.optInt("entryPbTopDownScore",0)).put("entryPbLowerLead",pos.optBoolean("entryPbLowerLead",false)).put("entryLowerTimeframeLead",pos.optBoolean("entryLowerTimeframeLead",false)).put("entryCounterTrend",pos.optBoolean("entryCounterTrend",false)).put("entryContextSide",pos.optString("entryContextSide","neutral"))
             .put("entryMode",pos.optString("entryMode","通常")).put("entryExploration",pos.optBoolean("entryExploration",false)).put("entryTechnicalRegime",pos.optString("entryTechnicalRegime","")).put("entryLearningBonus",pos.optInt("entryLearningBonus",0)).put("entryIndicatorAgreement",pos.optInt("entryIndicatorAgreement",0)).put("entryTechnicalScore",pos.optInt("entryTechnicalScore",0))
             .put("entryTechnical",pos.optString("entryTechnical","")).put("review",review);
            for(String k:MarketEngine.TF)t.put("entryTf_"+k,pos.optString("entryTf_"+k,"neutral"));
            JSONArray n=new JSONArray();n.put(t);for(int i=0;i<Math.min(49,arr.length());i++)n.put(arr.get(i));arr=n;
        }catch(Exception ignored){}
        Tuning tune=tuneStrategy(arr);updateLearningProfile(arr);String strategy=buildStrategyStatus(tune,arr);
        String msg=("long".equals(dir)?"ロング":"ショート")+"決済: "+reason+" / "+String.format(Locale.JAPAN,"%+.0f円",pnl);
        p.edit().putLong("balance",Double.doubleToRawLongBits(bal)).putLong("totalPnl",Double.doubleToRawLongBits(totalPnl()+pnl)).putInt("wins",w).putInt("losses",l).putInt("closed",c)
         .putString("trades",arr.toString()).putString("lastReview",review+"\n\n【次回の対応】"+tune.advice).putString("strategyStatus",strategy)
         .putInt("adaptiveReadiness",tune.readiness).putInt("adaptiveAgreement",tune.agreement).putBoolean("adaptiveRequireH1",tune.requireH1)
         .putInt("adaptiveAttackScore",tune.attackScore).putInt("adaptiveAttackRiskPct",tune.attackRiskPct).putString("lastAction",msg).apply();
        return msg;
    }

    private String buildReview(JSONObject pos,double exit,double pnl,String reason,MarketEngine.Analysis a){
        String dir=pos.optString("dir","");
        double en=pos.optDouble("entry",Double.NaN);
        int entryAgree=pos.optInt("entryAgreement",0),entryReady=pos.optInt("entryReadiness",0),aligned=0,conflicts=0;
        String side="long".equals(dir)?"up":"down",opp="long".equals(dir)?"down":"up";
        for(String k:DISPLAY_TF){
            String d=pos.optString("entryTf_"+k,"neutral");
            if(side.equals(d))aligned++;else if(opp.equals(d))conflicts++;
        }
        long opened=pos.optLong("openedAt",0),min=Math.max(0,(System.currentTimeMillis()-opened)/60000);
        int exitAligned=0,exitOpp=0;
        for(String k:DISPLAY_TF){
            String d=(a!=null&&a.tf.containsKey(k))?a.tf.get(k).direction:tfDir(k);
            if(side.equals(d))exitAligned++;else if(opp.equals(d))exitOpp++;
        }

        String entryMode=pos.optString("entryMode","通常");
        boolean attack="先行攻め".equals(entryMode);
        StringBuilder b=new StringBuilder();

        b.append("【結果・判定】\n");
        b.append(pnl>0?"利益確保 ":"損失 ");
        b.append(String.format(Locale.JAPAN,"%+.0f円",pnl)).append(" / ").append(reason);
        b.append("\n").append(attack?"先行エントリー":"通常エントリー");

        b.append("\n\n【今回の原因】\n");
        if(pnl>0){
            if("利確".equals(reason))b.append("・想定方向への値動きが継続し、利確ラインへ到達。");
            else b.append("・目標到達前でも、時間条件の中で利益を残して決済。");
            b.append("\n・エントリー時の同方向は10足中").append(aligned).append("足。");
            if(aligned<6)b.append(" 全面一致ではありませんでした。");
            else b.append(" 時間足の整合性は高めでした。");
        }else{
            if(reason.contains("損切り")){
                b.append("・エントリー後に想定と逆方向へ進み、損切りラインへ到達。");
                if(min<=60)b.append("\n・").append(min).append("分での決済で、直後の逆行が大きい取引でした。");
            }else{
                b.append("・時間経過に対して十分な伸びが出ず、優位性が低下。");
            }
            if(entryAgree<55)b.append("\n・エントリー時一致度 ").append(entryAgree).append("% と低め。");
            if(conflicts>=2)b.append("\n・10足中 ").append(conflicts).append("足が反対方向で、時間足の衝突あり。");
            if(exitOpp>=3)b.append("\n・決済時には反対方向が ").append(exitOpp).append("足まで増加。");
        }

        b.append("\n\n【検証指標】\n");
        b.append("準備度 ").append(entryReady).append("% / 一致度 ").append(entryAgree).append("%");
        b.append("\n保有 ").append(min).append("分 / エントリー時同方向 ").append(aligned).append("/10 / 決済時同方向 ").append(exitAligned).append("/10");
        if(attack){
            b.append("\n先行点 ").append(pos.optInt("entryAttackScore",0)).append(" / 全10足方向 ").append(pos.optInt("entryAllTimeframeScore",0)).append("%");
            b.append(" / ボラ拡大予兆 ").append(pos.optInt("entryVolatilityExpansionScore",0)).append("点");
        }

        b.append("\n\n【各指標の総合分析】\n");
        String tech=pos.optString("entryTechnical","");
        if(!tech.isEmpty())b.append("・総合指標：").append(tech).append(" / 総合技術点 ").append(pos.optInt("entryTechnicalScore",0)).append("%\n");
        b.append("・PB：全体 ").append(pos.optInt("entryPbAllScore",0)).append("% / 下位足 ").append(pos.optInt("entryPbLowerScore",0)).append("% / 上位足連動更新 ").append(pos.optInt("entryPbTopDownScore",0)).append("% / 上位足逆風 ").append(pos.optInt("entryPbUpperRisk",0)).append("%");
        if(pos.optBoolean("entryCounterTrend",false)){
            b.append("\n・時間軸：上位足の逆風を認識しつつ、下位足の方向成立を優先したエントリー。");
        }
        b.append("\n・PBはRCI・MACDなどと同じ指標の一つとして総合判断に使用。");

        b.append("\n\n【検証ポイント】\n");
        if(pnl<=0){
            boolean any=false;
            if(!side.equals(pos.optString("entryTf_H1","neutral"))){b.append("・H1が同方向でない状態で入った点を要確認。\n");any=true;}
            if(!side.equals(pos.optString("entryTf_M30","neutral"))){b.append("・M30の確認が弱かった点を要確認。\n");any=true;}
            if(entryAgree<55){b.append("・一致度55%未満のエントリー条件を厳しくする候補。\n");any=true;}
            if(min<=60){b.append("・短時間損切りが続く場合はM15/M30の確認を強化する候補。\n");any=true;}
            if(!any)b.append("・今回の負け方を次の数取引と比較し、共通点を確認します。");
        }else{
            b.append("・今回と同程度の時間足整合性・準備度で再現性があるか、次の数取引で確認します。");
        }
        return b.toString().trim();
    }

    private static final class Tuning{int readiness=65,agreement=75,attackScore=52,attackRiskPct=20;boolean requireH1=false;String advice="通常エントリーは慎重条件を維持し、先行エントリーは低リスクの常時探索で成功・失敗の両方を集めます。";}

    private Tuning tuneStrategy(JSONArray arr){
        Tuning t=new Tuning();
        int n=0,wins=0,losses=0,quickLoss=0,lowAgreementLoss=0,h1ConflictLoss=0,highBackgroundLoss=0,rN=0;
        double rSum=0;
        for(int i=0;i<Math.min(40,arr.length())&&n<20;i++){
            JSONObject x=arr.optJSONObject(i);if(x==null||"先行攻め".equals(x.optString("entryMode","")))continue;
            n++;double pnl=x.optDouble("pnl",0);if(pnl>0)wins++;else{
                losses++;
                if(x.optLong("durationMin",999)<=60)quickLoss++;
                if(x.optInt("entryAgreement",100)<75)lowAgreementLoss++;
                if(x.optInt("entryBackgroundRisk",0)>=45)highBackgroundLoss++;
                String dir=x.optString("dir"),h1=x.optString("entryTf_H1","neutral"),wanted="long".equals(dir)?"up":"down";
                if(!wanted.equals(h1))h1ConflictLoss++;
            }
            if(x.has("rMultiple")){rSum+=Math.max(-3.0,Math.min(3.0,x.optDouble("rMultiple",0)));rN++;}
        }
        double wr=n==0?0:wins*100.0/n,avgR=rN==0?Double.NaN:rSum/rN;
        if(n<12){
            t.advice="通常エントリーは"+n+"件。12件までは基本条件を維持し、少数の負けだけで条件を締めません。";
        }else{
            if(wr<45){t.readiness=68;t.agreement=78;t.advice="通常エントリー"+n+"件の勝率"+String.format(Locale.JAPAN,"%.1f",wr)+"%。急に停止せず、準備度68%・一致度78%へ小幅調整します。";}
            else if(wr<55){t.readiness=66;t.agreement=76;t.advice="通常エントリー"+n+"件の勝率"+String.format(Locale.JAPAN,"%.1f",wr)+"%。基本条件付近で検証を継続します。";}
            else{t.advice="通常エントリー"+n+"件の勝率"+String.format(Locale.JAPAN,"%.1f",wr)+"%。基本条件を維持し再現性を確認します。";}
            if(losses>=4&&quickLoss*2>=losses){t.readiness=Math.max(t.readiness,70);t.advice+=" 短時間損切りが負けの半数以上なので準備度を少し上げます。";}
            if(losses>=4&&lowAgreementLoss*2>=losses){t.agreement=Math.max(t.agreement,80);t.advice+=" 低一致度の負けが半数以上なので一致度を少し上げます。";}
            if(losses>=5&&h1ConflictLoss*100>=losses*70){t.requireH1=true;t.advice+=" H1逆行が負けの70%以上で確認されたため通常エントリーのみH1同方向を要求します。";}
            if(losses>=4&&highBackgroundLoss*2>=losses){t.readiness=Math.max(t.readiness,70);t.advice+=" 上位足逆風での負けが多いため通常エントリーの準備度を少し上げます。";}
            if(rN>=10&&!Double.isNaN(avgR)&&avgR<-.10){t.readiness=Math.max(t.readiness,70);t.agreement=Math.max(t.agreement,80);t.advice+=" 期待Rがマイナスのため条件を小幅に引き締めます。";}
        }
        t.readiness=Math.min(75,t.readiness);t.agreement=Math.min(82,t.agreement);

        int attackN=0,attackW=0,attackLossStreak=0,attackRN=0;double attackRSum=0;boolean streakOpen=true;
        for(int i=0;i<Math.min(50,arr.length());i++){
            JSONObject x=arr.optJSONObject(i);if(x==null||!"先行攻め".equals(x.optString("entryMode","")))continue;
            attackN++;boolean win=x.optDouble("pnl",0)>0;if(win)attackW++;
            if(streakOpen){if(win)streakOpen=false;else attackLossStreak++;}
            if(x.has("rMultiple")){attackRSum+=Math.max(-3.0,Math.min(3.0,x.optDouble("rMultiple",0)));attackRN++;}
        }
        double awr=attackN==0?0:attackW*100.0/attackN,aR=attackRN==0?Double.NaN:attackRSum/attackRN;
        if(attackN<EXPLORATION_TARGET_TRADES){
            t.attackScore=50;t.attackRiskPct=20;
            t.advice+=" 先行エントリーは"+attackN+"件。探索を止めず、低リスクで幅広い条件の成功・失敗を集めます。";
        }else{
            if(!Double.isNaN(aR)&&attackRN>=12){
                if(aR<-.10){t.attackScore=54;t.attackRiskPct=15;t.advice+=" 先行エントリーの期待Rがマイナス。頻度は維持し、サイズを15%へ落として失敗パターンを追加検証します。";}
                else if(aR>.15){t.attackScore=48;t.attackRiskPct=25;t.advice+=" 先行エントリーの期待Rがプラス。閾値48・リスク25%で探索範囲を広げます。";}
                else {t.attackScore=50;t.attackRiskPct=20;t.advice+=" 先行エントリーの期待Rは中立圏。低リスクの常時探索を継続します。";}
            }else if(awr<45){t.attackScore=54;t.attackRiskPct=15;t.advice+=" 先行エントリー"+attackN+"件の勝率"+String.format(Locale.JAPAN,"%.1f",awr)+"%。停止せず、サイズを落として失敗条件の学習を継続します。";}
            else if(awr>=60){t.attackScore=48;t.attackRiskPct=25;t.advice+=" 先行エントリー"+attackN+"件の勝率"+String.format(Locale.JAPAN,"%.1f",awr)+"%。探索範囲を広げて再現性を確認します。";}
        }
        if(attackLossStreak>=3){t.attackRiskPct=Math.min(t.attackRiskPct,12);t.attackScore=Math.min(55,Math.max(t.attackScore,52));t.advice+=" 直近3連敗以上でも探索は停止せず、エントリー頻度を残したままリスクだけ12%へ落とします。";}
        return t;
    }

    private static final class FactorEdge{
        int hiN=0,loN=0;double hi=0,lo=0;
        void add(boolean high,double outcome){if(high){hiN++;hi+=outcome;}else{loN++;lo+=outcome;}}
    }
    private double normalizedOutcome(JSONObject x){
        if(x==null)return 0;
        if(x.has("rMultiple"))return Math.max(-2.0,Math.min(2.0,x.optDouble("rMultiple",0)));
        double pnl=x.optDouble("pnl",0);return pnl>0?1:pnl<0?-1:0;
    }
    private int edgeAdjustment(FactorEdge f){
        if(f.hiN<6||f.loN<6)return 0;
        double edge=f.hi/f.hiN-f.lo/f.loN;
        if(edge>=.30)return 3;if(edge<=-.30)return -3;return 0;
    }
    private void updateLearningProfile(JSONArray arr){
        FactorEdge tech=new FactorEdge(),pb=new FactorEdge(),trend=new FactorEdge(),vol=new FactorEdge(),agree=new FactorEdge();
        int n=0;
        for(int i=0;i<Math.min(50,arr.length());i++){
            JSONObject x=arr.optJSONObject(i);if(x==null)continue;n++;
            double o=normalizedOutcome(x);
            tech.add(x.optInt("entryTechnicalScore",0)>=65,o);
            pb.add(Math.max(x.optInt("entryPbAllScore",0),x.optInt("entryPbTopDownScore",0))>=55,o);
            trend.add(x.optInt("entryAllTimeframeScore",0)>=55,o);
            vol.add(x.optInt("entryVolatilityExpansionScore",0)>=55,o);
            agree.add(x.optInt("entryAgreement",0)>=75,o);
        }
        int ta=edgeAdjustment(tech),pa=edgeAdjustment(pb),tra=edgeAdjustment(trend),va=edgeAdjustment(vol),aa=edgeAdjustment(agree);
        String status="学習サンプル "+n+"件 / RCI・MACD "+signedAdj(ta)+" / PB "+signedAdj(pa)+" / 時間足 "+signedAdj(tra)+" / ボラ "+signedAdj(va)+" / 一致度 "+signedAdj(aa);
        p.edit().putInt("learnTechAdj",ta).putInt("learnPbAdj",pa).putInt("learnTrendAdj",tra).putInt("learnVolAdj",va).putInt("learnAgreementAdj",aa)
         .putInt("learningSampleCount",n).putString("learningProfile",status).apply();
    }
    private String signedAdj(int v){return v>0?"+"+v:v<0?String.valueOf(v):"0";}
    private int learnedEntryBonus(EntryDecision d){
        int b=0;
        if(d.technicalScore>=65)b+=p.getInt("learnTechAdj",0);
        if(d.pbAllScore>=55)b+=p.getInt("learnPbAdj",0);
        if(d.allTimeframeScore>=55)b+=p.getInt("learnTrendAdj",0);
        if(d.expansionScore>=55)b+=p.getInt("learnVolAdj",0);
        if(d.agreement>=75)b+=p.getInt("learnAgreementAdj",0);
        return Math.max(-6,Math.min(6,b));
    }
    private boolean explorationNeeded(){
        // 先行エントリーは学習用の常時探索枠。失敗が続いても停止せず、tuneStrategy()でサイズを下げて経験を蓄積する。
        return true;
    }
    public String learningProfile(){return p.getString("learningProfile","学習サンプルを収集中");}
    public int learningSampleCount(){return p.getInt("learningSampleCount",0);}
    public boolean explorationActive(){return explorationNeeded();}

    private String buildStrategyStatus(Tuning t,JSONArray arr){
        int n=0,w=0,an=0,aw=0;
        for(int i=0;i<Math.min(40,arr.length())&&n<20;i++){
            JSONObject x=arr.optJSONObject(i);if(x==null||"先行攻め".equals(x.optString("entryMode","")))continue;
            n++;if(x.optDouble("pnl",0)>0)w++;
        }
        for(int i=0;i<Math.min(50,arr.length());i++){
            JSONObject x=arr.optJSONObject(i);
            if(x!=null&&"先行攻め".equals(x.optString("entryMode",""))){an++;if(x.optDouble("pnl",0)>0)aw++;}
        }
        String wr=n==0?"--":String.format(Locale.JAPAN,"%.1f%%",w*100.0/n);
        String awr=an==0?"--":String.format(Locale.JAPAN,"%.1f%%",aw*100.0/an);
        String normal="通常エントリー：直近"+n+"件 / 勝率 "+wr+" / 準備度≥"+t.readiness+"% / 一致度≥"+t.agreement+"%"+(t.requireH1?" / H1同方向必須":"");
        String attack="先行エントリー："+an+"件 / 勝率 "+awr+" / 先行点目安≥"+t.attackScore+" / リスク"+t.attackRiskPct+"% / 常時探索中";
        return normal+"\n"+attack+"\n"+learningProfile();
    }

    public double unrealized(double mid){if(Double.isNaN(mid))return 0;JSONArray a=positions();double total=0;for(int i=0;i<a.length();i++)total+=positionUnrealized(a.optJSONObject(i),mid);return total;}
    public JSONArray trades(){try{return new JSONArray(p.getString("trades","[]"));}catch(Exception e){return new JSONArray();}}
}
