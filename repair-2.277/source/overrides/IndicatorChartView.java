package com.konchan.chappyfx;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import org.json.*;
import java.util.*;

public class IndicatorChartView extends View {
    public static final int MODE_RCI=1, MODE_MACD=2;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Double> closes=new ArrayList<>();
    private int mode=MODE_RCI;
    private final int BG=Color.rgb(17,27,38),GRID=Color.rgb(47,62,77),TEXT=Color.rgb(205,215,224);
    private final int MAGENTA=Color.rgb(220,42,184),GREEN=Color.rgb(62,201,89),BLUE=Color.rgb(88,126,205);
    private final int POS=Color.rgb(255,105,32),NEG=Color.rgb(40,205,236);

    public IndicatorChartView(Context c){super(c);init();}
    public IndicatorChartView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){setBackgroundColor(BG);p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.NORMAL));}
    public void setMode(int m){mode=m;invalidate();}
    public void setCandles(JSONArray a){
        closes.clear();
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)closes.add(x.optDouble("c",Double.NaN));}
        invalidate();
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float w=getWidth(),h=getHeight(),left=dp(8),right=w-dp(48),top=dp(22),bottom=h-dp(12);
        p.setStyle(Paint.Style.FILL);p.setColor(BG);c.drawRect(0,0,w,h,p);
        p.setTextSize(sp(10));p.setTextAlign(Paint.Align.LEFT);p.setColor(TEXT);p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));
        c.drawText(mode==MODE_RCI?"RCI  5 / 10 / 20":"MACD  5-20-9",left,sp(13),p);
        if(closes.size()<20){p.setTextAlign(Paint.Align.CENTER);c.drawText("データ待ち",w/2,h/2,p);return;}
        if(mode==MODE_RCI)drawRci(c,left,right,top,bottom);else drawMacd(c,left,right,top,bottom);
    }

    private void drawRci(Canvas c,float left,float right,float top,float bottom){
        gridLine(c,left,right,yRci(80,top,bottom),"+80");gridLine(c,left,right,yRci(0,top,bottom),"0");gridLine(c,left,right,yRci(-80,top,bottom),"-80");
        int from=Math.max(0,closes.size()-48),count=closes.size()-from;if(count<2)return;
        drawRciLine(c,5,from,count,left,right,top,bottom,MAGENTA);
        drawRciLine(c,10,from,count,left,right,top,bottom,GREEN);
        drawRciLine(c,20,from,count,left,right,top,bottom,BLUE);
    }
    private void drawRciLine(Canvas c,int period,int from,int count,float left,float right,float top,float bottom,int color){
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.6f));Path path=new Path();boolean started=false;
        for(int j=0;j<count;j++){int idx=from+j;double v=rci(period,idx+1);if(Double.isNaN(v))continue;float x=left+(right-left)*(j/(float)Math.max(1,count-1)),y=yRci(v,top,bottom);if(!started){path.moveTo(x,y);started=true;}else path.lineTo(x,y);}
        if(started)c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }

    private void drawMacd(Canvas c,float left,float right,float top,float bottom){
        int n=closes.size();double[] macd=new double[n],signal=new double[n],hist=new double[n];
        double af=2.0/6.0,as=2.0/21.0,ag=2.0/10.0,fast=closes.get(0),slow=closes.get(0),sig=0;
        for(int i=0;i<n;i++){double q=closes.get(i);if(i>0){fast=q*af+fast*(1-af);slow=q*as+slow*(1-as);}macd[i]=fast-slow;if(i==0)sig=macd[i];else sig=macd[i]*ag+sig*(1-ag);signal[i]=sig;hist[i]=macd[i]-sig;}
        int from=Math.max(0,n-48),count=n-from;double max=.0001;
        for(int i=from;i<n;i++)max=Math.max(max,Math.max(Math.abs(macd[i]),Math.max(Math.abs(signal[i]),Math.abs(hist[i]))));
        float zero=(top+bottom)/2f;gridLine(c,left,right,zero,"0");
        float slot=(right-left)/Math.max(1,count),bar=Math.max(dp(1),slot*.55f);
        for(int j=0;j<count;j++){int i=from+j;float x=left+slot*(j+.5f),y=yMacd(hist[i],max,top,bottom);p.setColor(hist[i]>=0?POS:NEG);p.setStyle(Paint.Style.FILL);c.drawRect(x-bar/2,Math.min(zero,y),x+bar/2,Math.max(zero,y),p);}
        drawSeries(c,macd,from,count,max,left,right,top,bottom,MAGENTA);drawSeries(c,signal,from,count,max,left,right,top,bottom,GREEN);
        p.setTextAlign(Paint.Align.LEFT);p.setTextSize(sp(9));p.setColor(TEXT);c.drawText(String.format(Locale.JAPAN,"%.3f",max),right+4,top+sp(3),p);c.drawText(String.format(Locale.JAPAN,"-%.3f",max),right+4,bottom,p);
    }
    private void drawSeries(Canvas c,double[] v,int from,int count,double max,float left,float right,float top,float bottom,int color){
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.6f));Path path=new Path();
        for(int j=0;j<count;j++){float x=left+(right-left)*(j/(float)Math.max(1,count-1)),y=yMacd(v[from+j],max,top,bottom);if(j==0)path.moveTo(x,y);else path.lineTo(x,y);}c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
    private void gridLine(Canvas c,float left,float right,float y,String label){p.setColor(GRID);p.setStrokeWidth(dp(1));c.drawLine(left,y,right,y,p);p.setColor(TEXT);p.setTextSize(sp(9));p.setTextAlign(Paint.Align.LEFT);c.drawText(label,right+4,y+sp(3),p);}
    private double rci(int period,int end){if(end<period)return Double.NaN;int start=end-period;double sum=0;for(int i=0;i<period;i++){double price=closes.get(start+i);int less=0,equal=0;for(int j=0;j<period;j++){double q=closes.get(start+j);if(q<price)less++;else if(Double.compare(q,price)==0)equal++;}double priceRank=less+(equal+1)/2.0,timeRank=i+1,d=timeRank-priceRank;sum+=d*d;}double den=period*(period*period-1.0);return den<=0?Double.NaN:100.0*(1.0-6.0*sum/den);}
    private float yRci(double v,float top,float bottom){double z=Math.max(-100,Math.min(100,v));return (float)(top+(100-z)/200.0*(bottom-top));}
    private float yMacd(double v,double max,float top,float bottom){return (float)((top+bottom)/2.0-v/(max*2.0)*(bottom-top));}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float sp(float v){return v*getResources().getDisplayMetrics().scaledDensity;}
}
