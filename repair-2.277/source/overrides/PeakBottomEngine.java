package com.konchan.chappyfx;

import java.util.*;

final class PeakBottomEngine {
    static final int UP=1, DOWN=-1, NEUTRAL=0;
    private static final int PERIOD=10; // reference mobile initial setting
    private static final String[] ORDER={"M5","M15","M30","H1","H2","H4","H8","D","W","MN"};

    static final class Point {
        final int index; final long timeMs; final double price; final boolean peak;
        Point(int index,long timeMs,double price,boolean peak){this.index=index;this.timeMs=timeMs;this.price=price;this.peak=peak;}
    }
    static final class State {
        final ArrayList<Point> points=new ArrayList<>();
        int waveDir=NEUTRAL,structureDir=NEUTRAL,confirmedCount=0;
        double lastPeak=Double.NaN,lastBottom=Double.NaN;
        long lastPeakTime=0,lastBottomTime=0;
        String phase="判定待ち";
        String compact(){
            if(waveDir==UP)return "上昇波・ピーク待ち";
            if(waveDir==DOWN)return "下降波・ボトム待ち";
            return "判定待ち";
        }
    }
    static final class TurnEstimate {
        boolean available=false,targetPeak=false,candidateActive=false,candidatePeak=false,nextOppAvailable=false;
        String tf="",contextSummary="";
        int samples=0,expectedBars=0,elapsedBars=0,remainingBars=0,lowBars=0,highBars=0,progress=0,confidence=0;
        int contextSupport=50,contextUsed=0,candidateMaturity=0;
        double candidatePrice=Double.NaN;
        long remainingMinutes=0,lowMinutes=0,highMinutes=0;
        long nextOppRemainingMinutes=0,nextOppLowMinutes=0,nextOppHighMinutes=0;
    }

    static final class MultiTimeframe {
        final LinkedHashMap<String,State> byTf=new LinkedHashMap<>();
        State state(String tf){State s=byTf.get(tf);return s==null?new State():s;}
        int primaryBias(){
            int x=consensus(new String[]{"H8","D","W","MN"});
            if(x!=NEUTRAL)return x;
            return consensus(new String[]{"H4","H2","H1"});
        }
        int higherBiasFor(String tf){
            int idx=indexOf(tf);if(idx<0)return primaryBias();
            ArrayList<String> keys=new ArrayList<>();
            for(int i=idx+1;i<ORDER.length;i++)keys.add(ORDER[i]);
            if(keys.isEmpty())return NEUTRAL;
            return consensus(keys.toArray(new String[0]));
        }
        String higherSummaryFor(String tf){
            int bias=higherBiasFor(tf);
            StringBuilder b=new StringBuilder();
            State h8=byTf.get("H8"),d=byTf.get("D");
            if(h8!=null)b.append("8H ").append(h8.compact());
            if(d!=null){if(b.length()>0)b.append(" / ");b.append("日足 ").append(d.compact());}
            if(b.length()==0)b.append("上位足PB 判定待ち");
            else b.append(" → ").append(bias==UP?"上優勢":bias==DOWN?"下優勢":"方向競合");
            return b.toString();
        }
        private int consensus(String[] keys){
            int up=0,down=0;
            for(String k:keys){State s=byTf.get(k);if(s==null)continue;if(s.waveDir==UP)up++;else if(s.waveDir==DOWN)down++;}
            if(up>0&&down==0)return UP;if(down>0&&up==0)return DOWN;return NEUTRAL;
        }
    }

    static MultiTimeframe analyzeAll(Map<String,List<MarketEngine.Candle>> raw){
        MultiTimeframe m=new MultiTimeframe();if(raw==null)return m;
        for(String tf:ORDER){List<MarketEngine.Candle> rows=raw.get(tf);if(rows!=null&&!rows.isEmpty())m.byTf.put(tf,analyze(rows));}
        return m;
    }
    static State analyze(List<MarketEngine.Candle> rows){return analyze(rows,rows==null?-1:rows.size()-1);}
    static State analyze(List<MarketEngine.Candle> rows,int end){
        if(rows==null||rows.isEmpty()||end<0)return new State();
        int n=Math.min(rows.size(),end+1);
        long[] t=new long[n];double[] h=new double[n],l=new double[n],c=new double[n];
        for(int i=0;i<n;i++){MarketEngine.Candle x=rows.get(i);t[i]=x.timeMs;h[i]=x.h;l[i]=x.l;c[i]=x.c;}
        return analyze(t,h,l,c,n);
    }
    static State analyze(long[] t,double[] h,double[] l,double[] c,int n){
        State s=new State();if(t==null||h==null||l==null||c==null)return s;
        int limit=Math.min(n,Math.min(Math.min(t.length,h.length),Math.min(l.length,c.length)));
        if(limit<PERIOD*2+1)return s;

        /*
         * Confirmed area:
         *   Peak  = H[t] > max(previous PERIOD highs)
         *           && H[t] >= max(next PERIOD highs)
         *   Bottom= L[t] < min(previous PERIOD lows)
         *           && L[t] <= min(next PERIOD lows)
         *
         * Candidate ordering follows the documented alternating rule:
         * consecutive Peaks keep the higher one, consecutive Bottoms keep
         * the lower one, and equal values keep the older point.
         */
        for(int i=PERIOD;i<limit-PERIOD;i++){
            double pastHigh=-Double.MAX_VALUE,pastLow=Double.MAX_VALUE;
            double futureHigh=-Double.MAX_VALUE,futureLow=Double.MAX_VALUE;
            for(int j=i-PERIOD;j<i;j++){
                pastHigh=Math.max(pastHigh,h[j]);
                pastLow=Math.min(pastLow,l[j]);
            }
            for(int j=i+1;j<=i+PERIOD;j++){
                futureHigh=Math.max(futureHigh,h[j]);
                futureLow=Math.min(futureLow,l[j]);
            }
            boolean pk=h[i]>pastHigh&&h[i]>=futureHigh;
            boolean bt=l[i]<pastLow&&l[i]<=futureLow;
            appendCandidate(s,t,h,l,i,pk,bt);
        }

        s.confirmedCount=s.points.size();

        /*
         * Live tail:
         * the comparison screenshots consistently show one still-moving
         * opposite-side marker after the latest confirmed turning point.
         * Do not create multiple trailing candidates.  Instead, from the bar
         * after the latest confirmed point through the current bar, track
         * exactly one opposite extreme.  This marker repaints until a future
         * confirmed point supersedes it.
         */
        if(!s.points.isEmpty()){
            Point lastConfirmed=s.points.get(s.points.size()-1);
            int from=lastConfirmed.index+1;
            if(from<limit){
                int extremeIndex=from;
                if(lastConfirmed.peak){
                    double extreme=l[from];
                    for(int i=from+1;i<limit;i++){
                        if(l[i]<extreme){extreme=l[i];extremeIndex=i;}
                    }
                    s.points.add(new Point(extremeIndex,t[extremeIndex],l[extremeIndex],false));
                }else{
                    double extreme=h[from];
                    for(int i=from+1;i<limit;i++){
                        if(h[i]>extreme){extreme=h[i];extremeIndex=i;}
                    }
                    s.points.add(new Point(extremeIndex,t[extremeIndex],h[extremeIndex],true));
                }
            }
        }

        double prevPeak=Double.NaN,prevBottom=Double.NaN;
        for(Point p:s.points){
            if(p.peak){prevPeak=s.lastPeak;s.lastPeak=p.price;s.lastPeakTime=p.timeMs;}
            else{prevBottom=s.lastBottom;s.lastBottom=p.price;s.lastBottomTime=p.timeMs;}
        }
        if(!s.points.isEmpty()){
            Point last=s.points.get(s.points.size()-1);
            s.waveDir=last.peak?DOWN:UP;
            s.phase=s.compact();
        }
        if(!Double.isNaN(prevPeak)&&!Double.isNaN(prevBottom)&&!Double.isNaN(s.lastPeak)&&!Double.isNaN(s.lastBottom)){
            if(s.lastPeak>prevPeak&&s.lastBottom>prevBottom)s.structureDir=UP;
            else if(s.lastPeak<prevPeak&&s.lastBottom<prevBottom)s.structureDir=DOWN;
        }
        return s;
    }

    private static void appendCandidate(State s,long[] t,double[] h,double[] l,int i,boolean pk,boolean bt){
        if(!pk&&!bt)return;

        boolean choosePeak;
        if(pk&&bt){
            if(s.points.isEmpty())choosePeak=true;
            else choosePeak=!s.points.get(s.points.size()-1).peak;
        }else choosePeak=pk;

        Point chosen=new Point(i,t[i],choosePeak?h[i]:l[i],choosePeak);
        if(s.points.isEmpty()){s.points.add(chosen);return;}

        int lastIndex=s.points.size()-1;
        Point last=s.points.get(lastIndex);
        if(last.peak==chosen.peak){
            boolean replace=chosen.peak?chosen.price>last.price:chosen.price<last.price;
            if(replace)s.points.set(lastIndex,chosen);
        }else{
            s.points.add(chosen);
        }
    }

    static TurnEstimate estimateNextTurn(String tf,List<MarketEngine.Candle> rows){
        TurnEstimate e=new TurnEstimate();e.tf=tf==null?"":tf;
        if(rows==null||rows.size()<PERIOD*2+1)return e;
        State s=analyze(rows);if(s.confirmedCount<=0||s.confirmedCount>s.points.size())return e;
        Point anchor=s.points.get(s.confirmedCount-1);e.targetPeak=!anchor.peak;
        fillCycleEstimate(e,tf,rows,s,anchor,e.targetPeak,false);
        if(!e.available)return e;
        if(s.points.size()>s.confirmedCount){
            Point live=s.points.get(s.points.size()-1);
            if(live.peak==e.targetPeak){
                e.candidateActive=true;e.candidatePeak=live.peak;e.candidatePrice=live.price;e.candidateMaturity=e.progress;
                TurnEstimate next=new TurnEstimate();next.tf=e.tf;
                fillCycleEstimate(next,tf,rows,s,live,!live.peak,true);
                if(next.available){
                    e.nextOppAvailable=true;e.nextOppRemainingMinutes=next.remainingMinutes;
                    e.nextOppLowMinutes=next.lowMinutes;e.nextOppHighMinutes=next.highMinutes;
                }
            }
        }
        return e;
    }

    static TurnEstimate estimateNextTurn(String tf,Map<String,List<MarketEngine.Candle>> raw){
        List<MarketEngine.Candle> rows=raw==null?null:raw.get(tf);
        TurnEstimate e=estimateNextTurn(tf,rows);
        if(!e.available||raw==null)return e;
        MultiTimeframe all=analyzeAll(raw);
        int wanted=e.targetPeak?UP:DOWN,targetIdx=indexOf(tf);
        double support=0,oppose=0;int used=0;
        ArrayList<String> upperSupport=new ArrayList<>(),upperOppose=new ArrayList<>();
        for(int i=0;i<ORDER.length;i++){
            if(i==targetIdx)continue;
            State s=all.byTf.get(ORDER[i]);if(s==null||s.confirmedCount<=0)continue;
            int d=confirmedWaveDirection(s);if(d==NEUTRAL)continue;
            double w=contextWeight(targetIdx,i);used++;
            if(d==wanted){support+=w;if(i>targetIdx)upperSupport.add(ORDER[i]);}
            else{oppose+=w;if(i>targetIdx)upperOppose.add(ORDER[i]);}
        }
        e.contextUsed=used;
        if(support+oppose>0)e.contextSupport=Math.max(0,Math.min(100,(int)Math.round(100.0*support/(support+oppose))));
        e.contextSummary=contextSummary(e.targetPeak,e.contextSupport,used,upperSupport,upperOppose);
        return e;
    }

    private static void fillCycleEstimate(TurnEstimate e,String tf,List<MarketEngine.Candle> rows,State s,Point anchor,boolean targetPeak,boolean allowLiveAnchor){
        ArrayList<Integer> spans=new ArrayList<>();
        int limit=s.confirmedCount;
        for(int i=0;i<limit-1;i++){
            Point a=s.points.get(i),b=s.points.get(i+1);
            boolean sameTransition=targetPeak?(!a.peak&&b.peak):(a.peak&&!b.peak);
            int d=b.index-a.index;if(sameTransition&&d>0)spans.add(d);
        }
        if(spans.size()<3)return;
        int from=Math.max(0,spans.size()-12);ArrayList<Integer> recent=new ArrayList<>(spans.subList(from,spans.size()));
        Collections.sort(recent);e.samples=recent.size();e.targetPeak=targetPeak;
        e.expectedBars=percentile(recent,.50);e.lowBars=percentile(recent,.25);e.highBars=percentile(recent,.75);
        e.elapsedBars=Math.max(0,rows.size()-1-anchor.index);e.remainingBars=Math.max(0,e.expectedBars-e.elapsedBars);
        int mins=barMinutes(tf);e.remainingMinutes=(long)e.remainingBars*mins;e.lowMinutes=(long)Math.max(0,e.lowBars-e.elapsedBars)*mins;e.highMinutes=(long)Math.max(0,e.highBars-e.elapsedBars)*mins;
        e.progress=e.expectedBars<=0?0:Math.max(0,Math.min(100,(int)Math.round(e.elapsedBars*100.0/e.expectedBars)));
        int spread=Math.max(0,e.highBars-e.lowBars);
        int base=e.samples>=8?82:e.samples>=5?70:58;
        int penalty=e.expectedBars<=0?20:Math.min(25,(int)Math.round(spread*25.0/Math.max(1,e.expectedBars)));
        e.confidence=Math.max(35,Math.min(90,base-penalty));e.available=true;
    }

    private static int confirmedWaveDirection(State s){
        if(s==null||s.confirmedCount<=0||s.points.size()<s.confirmedCount)return NEUTRAL;
        Point p=s.points.get(s.confirmedCount-1);return p.peak?DOWN:UP;
    }
    private static double contextWeight(int targetIdx,int otherIdx){
        if(targetIdx<0)return 1.0;
        int d=Math.abs(otherIdx-targetIdx);
        if(otherIdx>targetIdx)return Math.min(2.0,1.0+.18*d);
        return Math.min(.75,.35+.05*d);
    }
    static int candidateAgeBars(String tf,boolean candidatePeak,Map<String,List<MarketEngine.Candle>> raw,MultiTimeframe all){
        if(all==null||raw==null)return 0;
        State s=all.byTf.get(tf);List<MarketEngine.Candle> rows=raw.get(tf);
        if(s==null||rows==null||s.points.size()<=s.confirmedCount)return 0;
        Point live=s.points.get(s.points.size()-1);
        if(live.peak!=candidatePeak)return 0;
        return Math.max(0,rows.size()-1-live.index);
    }

    private static boolean movingAwayFromLivePivot(String tf,Map<String,List<MarketEngine.Candle>> raw,MultiTimeframe all){
        if(all==null||raw==null)return false;
        State s=all.byTf.get(tf);List<MarketEngine.Candle> rows=raw.get(tf);
        if(s==null||rows==null||rows.size()<2||s.points.size()<=s.confirmedCount)return false;
        Point live=s.points.get(s.points.size()-1);
        int lookback=Math.min(3,rows.size()-1);
        double now=rows.get(rows.size()-1).c;
        double before=rows.get(rows.size()-1-lookback).c;
        return live.peak?now<before:now>before;
    }

    private static int settledHigherWave(String tf,Map<String,List<MarketEngine.Candle>> raw,MultiTimeframe all){
        if(all==null||raw==null)return NEUTRAL;
        State s=all.byTf.get(tf);List<MarketEngine.Candle> rows=raw.get(tf);
        if(s==null||rows==null||s.points.size()<=s.confirmedCount)return NEUTRAL;
        Point live=s.points.get(s.points.size()-1);
        int age=Math.max(0,rows.size()-1-live.index);
        // 上位足のP/Bが出て1〜3本は、まだ更新余地を判断材料にしない。
        if(age<4||!movingAwayFromLivePivot(tf,raw,all))return NEUTRAL;
        return live.peak?DOWN:UP;
    }

    static int updateContinuationScore(String tf,boolean candidatePeak,Map<String,List<MarketEngine.Candle>> raw,MultiTimeframe all){
        int targetIdx=indexOf(tf);
        if(targetIdx<0||all==null||raw==null)return 0;
        State target=all.byTf.get(tf);
        if(target==null||target.points.size()<=target.confirmedCount)return 0;
        Point current=target.points.get(target.points.size()-1);
        if(current.peak!=candidatePeak)return 0;

        // 対象自身のP/Bが4本以上更新されず、すでに反対方向へ離れている場合は
        // 「現在P/Bがさらに更新する候補」ではないので黄色にしない。
        int ownAge=candidateAgeBars(tf,candidatePeak,raw,all);
        if(ownAge>=4&&movingAwayFromLivePivot(tf,raw,all))return 0;

        int wanted=candidatePeak?UP:DOWN;
        int support=0,oppose=0;
        // 判定材料は対象より上位の時間足だけ。下位足は一切使わない。
        for(int i=targetIdx+1;i<ORDER.length;i++){
            int wave=settledHigherWave(ORDER[i],raw,all);
            if(wave==NEUTRAL)continue;
            if(wave==wanted)support++;else oppose++;
        }
        int used=support+oppose;
        if(used==0||support==0)return 0;
        return Math.max(0,Math.min(100,(int)Math.round(100.0*support/used)));
    }

    static int updateBiasFor(String tf,Map<String,List<MarketEngine.Candle>> raw,MultiTimeframe all){
        if(all==null)return NEUTRAL;
        State s=all.byTf.get(tf);
        if(s==null||s.points.size()<=s.confirmedCount)return NEUTRAL;
        Point live=s.points.get(s.points.size()-1);
        int score=updateContinuationScore(tf,live.peak,raw,all);
        if(score<60)return NEUTRAL;
        return live.peak?UP:DOWN;
    }

    static int updateDirectionScore(Map<String,List<MarketEngine.Candle>> raw,MultiTimeframe all,int wanted){
        if(raw==null||all==null||(wanted!=UP&&wanted!=DOWN))return 0;
        int used=0,match=0;
        for(String tf:ORDER){
            int b=updateBiasFor(tf,raw,all);
            if(b==NEUTRAL)continue;
            used++;if(b==wanted)match++;
        }
        return used==0?0:Math.max(0,Math.min(100,(int)Math.round(100.0*match/used)));
    }

    private static String contextSummary(boolean targetPeak,int score,int used,ArrayList<String> upperSupport,ArrayList<String> upperOppose){
        if(used<=0)return "全時間足の比較材料不足";
        String move=targetPeak?"高値更新":"安値更新";
        String level=score>=70?"高め":score>=55?"やや高め":score<=30?"低め":score<=45?"やや低め":"拮抗";
        StringBuilder b=new StringBuilder("全").append(used+1).append("時間足比較：").append(move).append("の可能性 ").append(level);
        ArrayList<String> src=!upperSupport.isEmpty()?upperSupport:upperOppose;
        if(!src.isEmpty()){
            b.append("（");
            for(int i=0;i<Math.min(4,src.size());i++){if(i>0)b.append("・");b.append(tfJa(src.get(i)));}
            b.append(!upperSupport.isEmpty()?targetPeak?"もピーク待ち":"もボトム待ち":targetPeak?"はボトム待ち":"はピーク待ち").append("）");
        }
        return b.toString();
    }
    private static String tfJa(String tf){
        if("M5".equals(tf))return "5分";if("M15".equals(tf))return "15分";if("M30".equals(tf))return "30分";
        if("H1".equals(tf))return "1時間";if("H2".equals(tf))return "2時間";if("H4".equals(tf))return "4時間";if("H8".equals(tf))return "8時間";
        if("D".equals(tf))return "日足";if("W".equals(tf))return "週足";if("MN".equals(tf))return "月足";return tf;
    }

    private static int percentile(ArrayList<Integer> sorted,double q){
        if(sorted==null||sorted.isEmpty())return 0;
        int idx=(int)Math.round((sorted.size()-1)*q);idx=Math.max(0,Math.min(sorted.size()-1,idx));return sorted.get(idx);
    }
    private static int barMinutes(String tf){
        if("M5".equals(tf))return 5;if("M15".equals(tf))return 15;if("M30".equals(tf))return 30;
        if("H1".equals(tf))return 60;if("H2".equals(tf))return 120;if("H4".equals(tf))return 240;if("H8".equals(tf))return 480;
        if("D".equals(tf))return 1440;if("W".equals(tf))return 10080;if("MN".equals(tf))return 43200;return 5;
    }

    static int waveDirectionAt(List<MarketEngine.Candle> rows,int end){return analyze(rows,end).waveDir;}
    private static int indexOf(String tf){for(int i=0;i<ORDER.length;i++)if(ORDER[i].equals(tf))return i;return -1;}
}
