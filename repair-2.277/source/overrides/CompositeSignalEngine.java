package com.konchan.chappyfx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class CompositeSignalEngine {
    public static final class Component {
        public String name="",detail="";
        public int score=0; // -100 weak USD / +100 strong USD
        public double weight=1.0;
    }
    public static final class Result {
        public String label="中立",headline="複合データは中立";
        public int score=0,strength=0,confidence=0,used=0;
        public final ArrayList<Component> components=new ArrayList<>();
        public String summary(){
            StringBuilder b=new StringBuilder();
            b.append("複合先行スコア ").append(strength).append("/100（").append(label).append("）");
            for(Component c:components)b.append("\n・").append(c.name).append("：").append(c.detail).append(" [").append(c.score>0?"+":"").append(c.score).append("]");
            return b.toString();
        }
    }

    private static final String FRED="https://fred.stlouisfed.org/graph/fredgraph.csv?id=";

    public Result employment() {
        Result r=new Result(); double weighted=0,weights=0; int ok=0;
        ok+=addSafely(r,"雇用者数モメンタム",1.30,()->{
            Series s=fred("PAYEMS"); double d1=diff(s,0),d2=diff(s,1),d3=diff(s,2),avg=(d1+d2+d3)/3.0;
            int sc=scoreLinear(avg,40,220);
            return comp("雇用者数モメンタム",String.format(Locale.JAPAN,"直近 %.0f千人 / 3か月平均 %.0f千人",d1,avg),sc,1.30);
        });
        ok+=addSafely(r,"新規失業保険",1.35,()->{
            Series s=fred("ICSA"); double a=avgLast(s,4,0),b=avgLast(s,4,4),pct=(a-b)/Math.max(1,b)*100;
            int sc=(int)Math.round(clamp(-pct*11,-100,100));
            return comp("新規失業保険",String.format(Locale.JAPAN,"4週平均 %.0f千件 / 直前4週比 %+.1f%%",a/1000,pct),sc,1.35);
        });
        ok+=addSafely(r,"継続失業保険",1.10,()->{
            Series s=fred("CCSA"); double a=avgLast(s,4,0),b=avgLast(s,4,4),pct=(a-b)/Math.max(1,b)*100;
            int sc=(int)Math.round(clamp(-pct*9,-100,100));
            return comp("継続失業保険",String.format(Locale.JAPAN,"4週平均 %.0f千件 / 直前4週比 %+.1f%%",a/1000,pct),sc,1.10);
        });
        ok+=addSafely(r,"求人需要（JOLTS）",1.15,()->{
            Series s=fred("JTSJOL"); double a=last(s,0),b=last(s,1),pct=(a-b)/Math.max(1,b)*100;
            int sc=(int)Math.round(clamp(pct*13,-100,100));
            return comp("求人需要（JOLTS）",String.format(Locale.JAPAN,"%.0f千件 / 前月比 %+.1f%%",a,pct),sc,1.15);
        });
        ok+=addSafely(r,"失業率",1.20,()->{
            Series s=fred("UNRATE"); double a=last(s,0),b=last(s,1),delta=a-b;
            int sc=(int)Math.round(clamp(-delta*250,-100,100));
            return comp("失業率",String.format(Locale.JAPAN,"%.1f%% / 前月差 %+.1fpt",a,delta),sc,1.20);
        });
        ok+=addSafely(r,"労働参加率",0.70,()->{
            Series s=fred("CIVPART"); double a=last(s,0),b=last(s,1),delta=a-b;
            int sc=(int)Math.round(clamp(delta*180,-100,100));
            return comp("労働参加率",String.format(Locale.JAPAN,"%.1f%% / 前月差 %+.1fpt",a,delta),sc,.70);
        });
        ok+=addSafely(r,"平均時給",0.85,()->{
            Series s=fred("CES0500000003"); double pct=mom(s);
            int sc=(int)Math.round(clamp((pct-.25)*180,-100,100));
            return comp("平均時給",String.format(Locale.JAPAN,"前月比 %+.2f%%",pct),sc,.85);
        });
        return finalizeResult(r,"雇用");
    }

    public Result inflation(){
        Result r=new Result();
        addSafely(r,"CPI",1.1,()->{Series s=fred("CPIAUCSL");double m=mom(s),y=yoy(s);return comp("CPI",String.format(Locale.JAPAN,"前月比 %+.2f%% / 前年比 %.2f%%",m,y),scoreLinear(m,.0,.6),1.1);});
        addSafely(r,"コアCPI",1.35,()->{Series s=fred("CPILFESL");double m=mom(s),y=yoy(s);return comp("コアCPI",String.format(Locale.JAPAN,"前月比 %+.2f%% / 前年比 %.2f%%",m,y),scoreLinear(m,.05,.55),1.35);});
        addSafely(r,"PCE",.9,()->{Series s=fred("PCEPI");double m=mom(s),y=yoy(s);return comp("PCE",String.format(Locale.JAPAN,"前月比 %+.2f%% / 前年比 %.2f%%",m,y),scoreLinear(m,.0,.5),.9);});
        addSafely(r,"コアPCE",1.25,()->{Series s=fred("PCEPILFE");double m=mom(s),y=yoy(s);return comp("コアPCE",String.format(Locale.JAPAN,"前月比 %+.2f%% / 前年比 %.2f%%",m,y),scoreLinear(m,.05,.5),1.25);});
        addSafely(r,"生産者物価",.75,()->{Series s=fred("PPIACO");double m=mom(s),y=yoy(s);return comp("生産者物価",String.format(Locale.JAPAN,"前月比 %+.2f%% / 前年比 %.2f%%",m,y),scoreLinear(m,-.4,1.0),.75);});
        addSafely(r,"ガソリン価格",.55,()->{Series s=fred("GASREGW");double pct=(last(s,0)/last(s,4)-1)*100;return comp("ガソリン価格",String.format(Locale.JAPAN,"4週比 %+.1f%%",pct),(int)Math.round(clamp(pct*10,-100,100)),.55);});
        return finalizeResult(r,"物価");
    }

    public Result growth(){
        Result r=new Result();
        addSafely(r,"実質GDP",1.25,()->{Series s=fred("GDPC1");double q=(Math.pow(last(s,0)/last(s,1),4)-1)*100;return comp("実質GDP",String.format(Locale.JAPAN,"直近四半期 約%+.1f%%年率",q),scoreLinear(q,-1,5),1.25);});
        addSafely(r,"鉱工業生産",1.0,()->{Series s=fred("INDPRO");double m=mom(s);return comp("鉱工業生産",String.format(Locale.JAPAN,"前月比 %+.2f%%",m),scoreLinear(m,-1,1),1.0);});
        addSafely(r,"小売売上",1.0,()->{Series s=fred("RSAFS");double m=mom(s);return comp("小売売上",String.format(Locale.JAPAN,"前月比 %+.2f%%",m),scoreLinear(m,-1.5,1.5),1.0);});
        addSafely(r,"失業率",.8,()->{Series s=fred("UNRATE");double d=last(s,0)-last(s,1);return comp("失業率",String.format(Locale.JAPAN,"%.1f%% / 前月差 %+.1fpt",last(s,0),d),(int)Math.round(clamp(-d*250,-100,100)),.8);});
        addSafely(r,"新規失業保険",.8,()->{Series s=fred("ICSA");double a=avgLast(s,4,0),b=avgLast(s,4,4),pct=(a-b)/Math.max(1,b)*100;return comp("新規失業保険",String.format(Locale.JAPAN,"直前4週比 %+.1f%%",pct),(int)Math.round(clamp(-pct*10,-100,100)),.8);});
        return finalizeResult(r,"景気");
    }

    public Result rates(){
        Result r=new Result();
        addSafely(r,"米2年金利",1.35,()->{Series s=fred("DGS2");double d=last(s,0)-last(s,5);return comp("米2年金利",String.format(Locale.JAPAN,"%.2f%% / 約1週差 %+.2fpt",last(s,0),d),(int)Math.round(clamp(d*300,-100,100)),1.35);});
        addSafely(r,"米10年金利",1.0,()->{Series s=fred("DGS10");double d=last(s,0)-last(s,5);return comp("米10年金利",String.format(Locale.JAPAN,"%.2f%% / 約1週差 %+.2fpt",last(s,0),d),(int)Math.round(clamp(d*240,-100,100)),1.0);});
        addSafely(r,"FF金利",.7,()->{Series s=fred("DFF");double d=last(s,0)-last(s,30);return comp("FF金利",String.format(Locale.JAPAN,"%.2f%% / 30日前差 %+.2fpt",last(s,0),d),(int)Math.round(clamp(d*160,-100,100)),.7);});
        return finalizeResult(r,"金利");
    }

    private interface Loader{Component load()throws Exception;}
    private int addSafely(Result r,String name,double weight,Loader l){
        try{Component c=l.load();r.components.add(c);return 1;}catch(Exception ignored){return 0;}
    }
    private Result finalizeResult(Result r,String theme){
        double sum=0,w=0;for(Component c:r.components){sum+=c.score*c.weight;w+=c.weight;}
        if(w<=0){r.label="データ不足";r.headline=theme+"の複合データ不足";r.confidence=20;return r;}
        r.score=(int)Math.round(sum/w);r.strength=Math.min(100,Math.abs(r.score));
        r.label=r.score>=35?"強め":r.score<=-35?"弱め":"中立〜まちまち";
        r.headline=theme+"の複合データは"+r.label;
        r.used=r.components.size();r.confidence=Math.min(86,38+r.used*7);
        return r;
    }

    private static Component comp(String n,String d,int s,double w){Component c=new Component();c.name=n;c.detail=d;c.score=s;c.weight=w;return c;}
    private static int scoreLinear(double v,double weak,double strong){
        if(Double.isNaN(v))return 0;if(v<=weak)return -100;if(v>=strong)return 100;return (int)Math.round(-100+200*(v-weak)/(strong-weak));
    }
    private static final class Series{final ArrayList<Double> v=new ArrayList<>();}
    private static Series fred(String id)throws Exception{
        String start=LocalDate.now().minusYears(3).toString();String csv=get(FRED+URLEncoder.encode(id,"UTF-8")+"&cosd="+start);
        Series s=new Series();String[] lines=csv.split("\r?\n");
        for(int i=1;i<lines.length;i++){String[] a=lines[i].split(",",-1);if(a.length<2)continue;try{s.v.add(Double.parseDouble(a[1].trim()));}catch(Exception ignored){}}
        if(s.v.size()<2)throw new IOException(id+"不足");return s;
    }
    private static double last(Series s,int back){return s.v.get(s.v.size()-1-back);}
    private static double diff(Series s,int back){return last(s,back)-last(s,back+1);}
    private static double avgLast(Series s,int n,int off){double x=0;for(int i=off;i<off+n;i++)x+=last(s,i);return x/n;}
    private static double mom(Series s){return (last(s,0)/last(s,1)-1)*100;}
    private static double yoy(Series s){if(s.v.size()<13)return Double.NaN;return (last(s,0)/last(s,12)-1)*100;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static String get(String u)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("User-Agent","ChappyFxDemo/2 Android");int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("HTTP "+code);return body;}
    private static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}
}
