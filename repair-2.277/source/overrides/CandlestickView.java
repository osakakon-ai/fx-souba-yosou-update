package com.konchan.chappyfx;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import org.json.*;
import java.text.*;
import java.util.*;

public class CandlestickView extends View {
    private static final int DISPLAY_COUNT=48,HISTORY_LIMIT=500;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<CandlePoint> candles=new ArrayList<>();
    private final int BG=Color.rgb(17,27,38), GRID=Color.rgb(47,62,77), TEXT=Color.rgb(205,215,224);
    private final int UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), PRICE=Color.rgb(241,199,95);
    private String timeframeLabel="5分足",peakBottomContext=""; private boolean loading=false; private double livePrice=Double.NaN;
    private PeakBottomEngine.State peakBottomState=new PeakBottomEngine.State();
    private static final class CandlePoint{long t;double o,h,l,c;CandlePoint(long t,double o,double h,double l,double c){this.t=t;this.o=o;this.h=h;this.l=l;this.c=c;}}
    public CandlestickView(Context c){super(c);init();}
    public CandlestickView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){setBackgroundColor(BG);p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.NORMAL));}

    public void setTimeframeLabel(String label){timeframeLabel=(label==null||label.isEmpty())?"時間足":label;invalidate();}
    public void setPeakBottomContext(String text){peakBottomContext=text==null?"":text;invalidate();}
    public void setLoading(boolean v){loading=v;invalidate();}
    public void setLivePrice(double v){
        livePrice=v;
        if(!Double.isNaN(v)&&!candles.isEmpty()){
            CandlePoint last=candles.get(candles.size()-1);
            long now=System.currentTimeMillis();
            boolean changed=false;
            if(isCalendarTimeframe()){
                if(sameCalendarPeriod(last.t,now)){
                    last.h=Math.max(last.h,v);last.l=Math.min(last.l,v);last.c=v;changed=true;
                }
                // A new week/month is created only by fetched OHLC aggregation.
                // Do not synthesize a daily candle inside a weekly/monthly chart.
            }else{
                long dur=timeframeDurationMs();
                if(now<last.t+dur){
                    last.h=Math.max(last.h,v);last.l=Math.min(last.l,v);last.c=v;changed=true;
                }else{
                    long start=last.t+dur;
                    while(start+dur<=now)start+=dur;
                    double o=last.c;
                    candles.add(new CandlePoint(start,o,Math.max(o,v),Math.min(o,v),v));
                    while(candles.size()>HISTORY_LIMIT)candles.remove(0);
                    changed=true;
                }
            }
            if(changed)recalcPeakBottom();
        }
        invalidate();
    }

    private boolean isCalendarTimeframe(){return timeframeLabel.startsWith("週")||timeframeLabel.startsWith("月");}
    private boolean sameCalendarPeriod(long a,long b){
        Calendar x=Calendar.getInstance(TimeZone.getTimeZone("UTC")),y=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        x.setFirstDayOfWeek(Calendar.MONDAY);x.setMinimalDaysInFirstWeek(4);y.setFirstDayOfWeek(Calendar.MONDAY);y.setMinimalDaysInFirstWeek(4);
        x.setTimeInMillis(a);y.setTimeInMillis(b);
        if(timeframeLabel.startsWith("月"))return x.get(Calendar.YEAR)==y.get(Calendar.YEAR)&&x.get(Calendar.MONTH)==y.get(Calendar.MONTH);
        return x.getWeekYear()==y.getWeekYear()&&x.get(Calendar.WEEK_OF_YEAR)==y.get(Calendar.WEEK_OF_YEAR);
    }

    public void setCandles(JSONArray a){
        candles.clear();
        if(a!=null){
            int from=Math.max(0,a.length()-HISTORY_LIMIT);
            for(int i=from;i<a.length();i++){
                JSONObject x=a.optJSONObject(i);if(x==null)continue;
                candles.add(new CandlePoint(x.optLong("t"),x.optDouble("o"),x.optDouble("h"),x.optDouble("l"),x.optDouble("c")));
            }
        }
        recalcPeakBottom();invalidate();
    }

    private void recalcPeakBottom(){
        int n=candles.size();long[] t=new long[n];double[] h=new double[n],l=new double[n],cl=new double[n];
        for(int i=0;i<n;i++){CandlePoint x=candles.get(i);t[i]=x.t;h[i]=x.h;l[i]=x.l;cl[i]=x.c;}
        peakBottomState=PeakBottomEngine.analyze(t,h,l,cl,n);
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float w=getWidth(),h=getHeight(),left=dp(8),top=dp(46),right=w-dp(58),bottom=h-dp(44);
        p.setStyle(Paint.Style.FILL);p.setColor(BG);c.drawRect(0,0,w,h,p);
        if(candles.size()<2){
            p.setColor(TEXT);p.setTextSize(sp(13));p.setTextAlign(Paint.Align.CENTER);
            c.drawText(loading?timeframeLabel+"を取得中…":timeframeLabel+"データを取得するとローソク足を表示します",w/2,h/2,p);return;
        }
        int drawFrom=Math.max(0,candles.size()-DISPLAY_COUNT),visibleCount=candles.size()-drawFrom;
        double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;
        for(int i=drawFrom;i<candles.size();i++){CandlePoint x=candles.get(i);hi=Math.max(hi,x.h);lo=Math.min(lo,x.l);}
        if(!Double.isNaN(livePrice)){hi=Math.max(hi,livePrice);lo=Math.min(lo,livePrice);}
        double range=Math.max(.01,hi-lo);hi+=range*.08;lo-=range*.08;range=hi-lo;
        p.setStrokeWidth(dp(1));p.setTextSize(sp(10));p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.NORMAL));
        for(int i=0;i<=4;i++){
            float yy=top+(bottom-top)*i/4f;p.setColor(GRID);c.drawLine(left,yy,right,yy,p);
            double price=hi-range*i/4.0;p.setColor(TEXT);p.setTextAlign(Paint.Align.LEFT);c.drawText(String.format(Locale.JAPAN,"%.3f",price),right+6,yy+sp(4),p);
        }
        float slot=(right-left)/(visibleCount+2f),body=Math.max(dp(2),slot*.58f);
        for(int i=drawFrom;i<candles.size();i++){
            CandlePoint x=candles.get(i);int vi=i-drawFrom;float cx=left+slot*(vi+.5f);
            float yh=y(x.h,hi,range,top,bottom),yl=y(x.l,hi,range,top,bottom),yo=y(x.o,hi,range,top,bottom),yc=y(x.c,hi,range,top,bottom);
            int color=x.c>=x.o?UP:DOWN;p.setColor(color);p.setStrokeWidth(Math.max(dp(1),slot*.08f));c.drawLine(cx,yh,cx,yl,p);
            float bt=Math.min(yo,yc),bb=Math.max(yo,yc);if(bb-bt<dp(1.5f))bb=bt+dp(1.5f);
            p.setStyle(Paint.Style.FILL);c.drawRect(cx-body/2,bt,cx+body/2,bb,p);
        }
        drawPeakBottom(c,left,top,bottom,slot,hi,range,drawFrom,visibleCount);
        CandlePoint last=candles.get(candles.size()-1);double linePrice=Double.isNaN(livePrice)?last.c:livePrice;
        float py=y(linePrice,hi,range,top,bottom);
        p.setColor(PRICE);p.setStrokeWidth(dp(1));c.drawLine(left,py,right,py,p);
        p.setColor(TEXT);p.setTextSize(sp(9));p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.NORMAL));
        int tickCount=Math.min(6,visibleCount);float labelY=h-dp(10);
        for(int i=0;i<tickCount;i++){
            int local=tickCount==1?0:Math.round((visibleCount-1)*(i/(float)(tickCount-1)));
            int idx=drawFrom+local;CandlePoint x=candles.get(idx);float cx=left+slot*(local+.5f);
            p.setColor(GRID);p.setStrokeWidth(dp(1));c.drawLine(cx,top,cx,bottom,p);p.setColor(TEXT);
            if(i==0)p.setTextAlign(Paint.Align.LEFT);else if(i==tickCount-1)p.setTextAlign(Paint.Align.RIGHT);else p.setTextAlign(Paint.Align.CENTER);
            c.drawText(timeLabel(x.t),cx,labelY,p);
        }
        p.setTextAlign(Paint.Align.LEFT);p.setColor(PRICE);p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));
        c.drawText(String.format(Locale.JAPAN,"%.3f",linePrice),right+6,Math.max(top+sp(10),Math.min(bottom,py))+sp(4),p);
    }

    private void drawPeakBottom(Canvas c,float left,float top,float bottom,float slot,double hi,double range,int drawFrom,int visibleCount){
        p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));p.setTextAlign(Paint.Align.LEFT);p.setTextSize(sp(11));p.setColor(PRICE);
        c.drawText("PB: "+peakBottomState.compact(),left,dp(17),p);
        if(!peakBottomContext.isEmpty()){p.setTextSize(sp(9));p.setColor(TEXT);c.drawText(peakBottomContext,left,dp(33),p);}
        int drawTo=drawFrom+visibleCount;
        for(PeakBottomEngine.Point q:peakBottomState.points){
            if(q.index<drawFrom||q.index>=drawTo)continue;
            int local=q.index-drawFrom;float x=left+slot*(local+.5f),yy=y(q.price,hi,range,top,bottom);
            p.setStyle(Paint.Style.FILL);p.setColor(q.peak?DOWN:UP);c.drawCircle(x,yy,dp(3.8f),p);
            p.setTextAlign(Paint.Align.CENTER);p.setTextSize(sp(15));p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
            c.drawText(q.peak?"P":"B",x,yy+(q.peak?-dp(10):dp(18)),p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }
    private long timeframeDurationMs(){
        if(timeframeLabel.startsWith("5分"))return 5L*60_000L;
        if(timeframeLabel.startsWith("15分"))return 15L*60_000L;
        if(timeframeLabel.startsWith("30分"))return 30L*60_000L;
        if(timeframeLabel.startsWith("1時間"))return 60L*60_000L;
        if(timeframeLabel.startsWith("2時間"))return 2L*60L*60_000L;
        if(timeframeLabel.startsWith("4時間"))return 4L*60L*60_000L;
        if(timeframeLabel.startsWith("8時間"))return 8L*60L*60_000L;
        if(timeframeLabel.startsWith("週"))return 7L*24L*60L*60_000L;
        if(timeframeLabel.startsWith("月"))return 31L*24L*60L*60_000L;
        return 24L*60L*60_000L;
    }
    private String timeLabel(long t){
        String pattern;
        if(timeframeLabel.startsWith("月"))pattern="yyyy/M";
        else if(timeframeLabel.startsWith("週")||timeframeLabel.startsWith("日"))pattern="M/d";
        else if(timeframeLabel.startsWith("8")||timeframeLabel.startsWith("4")||timeframeLabel.startsWith("2"))pattern="M/d HH:mm";
        else pattern="HH:mm";
        return new SimpleDateFormat(pattern,Locale.JAPAN).format(new Date(t));
    }
    private float y(double v,double hi,double range,float top,float bottom){return (float)(top+(hi-v)/range*(bottom-top));}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float sp(float v){return v*getResources().getDisplayMetrics().scaledDensity;}
}
