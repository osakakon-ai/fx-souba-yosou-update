package com.konchan.chappyfx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

public final class EmploymentForecastClient {
    public static final class Result {
        public long checkedAt,nextReleaseMs;
        public String referenceMonth="",latestPayrollMonth="",bias="中立",summary="";
        public double payrollCenterK=Double.NaN,payrollLowK=Double.NaN,payrollHighK=Double.NaN;
        public double unemploymentRate=Double.NaN,wageMonthlyPct=Double.NaN;
        public int confidence=0,signalScore=0;
        public boolean fetchSucceeded=false;
        public final ArrayList<String> factors=new ArrayList<>();
        public double latestPayrollChangeK=Double.NaN;
    }
    private static final String FRED="https://fred.stlouisfed.org/graph/fredgraph.csv?id=";
    private static final String BLS_CAL="https://www.bls.gov/schedule/news_release/empsit.htm";

    public Result fetch(double calibrationK){
        Result r=new Result();r.checkedAt=System.currentTimeMillis();
        try{
            Schedule s=nextSchedule();r.nextReleaseMs=s.releaseMs;r.referenceMonth=s.referenceMonth;
            Series pay=fred("PAYEMS"), un=fred("UNRATE"), claims=fred("ICSA"), cont=fred("CCSA"), jolts=fred("JTSJOL"), wage=fred("CES0500000003");
            if(pay.values.size()<5)throw new IOException("PAYEMSデータ不足");
            r.latestPayrollMonth=pay.dates.get(pay.dates.size()-1);
            r.latestPayrollChangeK=diff(pay,0);

            double d1=diff(pay,0),d2=diff(pay,1),d3=diff(pay,2);
            double baseline=d1*.50+d2*.30+d3*.20;
            double score=0;int inputs=0;

            if(claims.values.size()>=8){
                double a=avgLast(claims,4,0),b=avgLast(claims,4,4),pct=(a-b)/Math.max(1,b)*100;
                score+=clamp(-pct*7,-28,28);inputs++;
                r.factors.add("新規失業保険申請："+fmt0(a/1000)+"千件（直前4週比 "+signed1(pct)+"%）");
            }
            if(cont.values.size()>=8){
                double a=avgLast(cont,4,0),b=avgLast(cont,4,4),pct=(a-b)/Math.max(1,b)*100;
                score+=clamp(-pct*5,-20,20);inputs++;
                r.factors.add("継続受給者："+fmt0(a/1000)+"千件（直前4週比 "+signed1(pct)+"%）");
            }
            if(jolts.values.size()>=2){
                double a=last(jolts,0),b=last(jolts,1),pct=(a-b)/Math.max(1,b)*100;
                score+=clamp(pct*5,-18,18);inputs++;
                r.factors.add("求人件数（JOLTS）："+fmt0(a)+"千件（前月比 "+signed1(pct)+"%）");
            }
            if(un.values.size()>=2){
                double a=last(un,0),b=last(un,1),delta=a-b;
                score+=clamp(-delta*120,-18,18);inputs++;
                r.factors.add("失業率："+fmt1(a)+"%（前月差 "+signed1(delta)+"pt）");
            }
            if(wage.values.size()>=2){
                double a=last(wage,0),b=last(wage,1),pct=(a-b)/Math.max(.01,b)*100;
                r.wageMonthlyPct=pct;score+=clamp((pct-.25)*20,-8,8);inputs++;
                r.factors.add("平均時給：前月比 "+signed1(pct)+"%");
            }
            r.factors.add("非農業部門雇用者数：直近 "+fmt0(d1)+"千人 / 2か月前 "+fmt0(d2)+"千人 / 3か月前 "+fmt0(d3)+"千人");

            score=clamp(score,-100,100);
            double adjustment=score*.55+calibrationK;
            double center=clamp(baseline+adjustment,-250,450);
            double vol=Math.abs(d1-d2)+Math.abs(d2-d3);
            double width=clamp(45+vol*.18,45,105);

            r.signalScore=(int)Math.round(score);
            r.payrollCenterK=Math.round(center);
            r.payrollLowK=Math.round(center-width);
            r.payrollHighK=Math.round(center+width);
            double ur=last(un,0);
            if(score>=40)ur-=.1;else if(score<=-40)ur+=.1;
            r.unemploymentRate=Math.round(ur*10.0)/10.0;
            int conf=48+inputs*5-(int)Math.min(18,vol/12.0);
            if(Math.abs(calibrationK)>15)conf-=4;
            r.confidence=(int)clamp(conf,42,78);
            r.bias=score>=30?"雇用やや強め":score<=-30?"雇用やや弱め":"中立〜まちまち";
            r.summary="非農業部門雇用者数 "+signed0(r.payrollCenterK)+"千人（推定レンジ "+signed0(r.payrollLowK)+"〜"+signed0(r.payrollHighK)+"千人）";
            r.fetchSucceeded=true;
        }catch(Exception e){
            r.summary="事前分析データを取得できませんでした";
        }
        return r;
    }

    private static final class Schedule{long releaseMs;String referenceMonth;}
    private Schedule nextSchedule()throws Exception{
        String h=get(BLS_CAL);
        Pattern p=Pattern.compile("<tr[^>]*>\\s*<td[^>]*>([^<]+)</td>\\s*<td[^>]*>([^<]+)</td>\\s*<td[^>]*>([^<]+)</td>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Matcher m=p.matcher(h);long now=System.currentTimeMillis();Schedule best=null;
        while(m.find()){
            String ref=clean(m.group(1)),date=clean(m.group(2)),time=clean(m.group(3));
            try{
                DateTimeFormatter df=new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM. dd, uuuu hh:mm a").toFormatter(Locale.US);
                LocalDateTime ldt=LocalDateTime.parse(date+" "+time,df);
                ZonedDateTime ny=ldt.atZone(ZoneId.of("America/New_York"));
                long ms=ny.toInstant().toEpochMilli();
                if(ms>=now-3*3600_000L&&(best==null||ms<best.releaseMs)){
                    best=new Schedule();best.releaseMs=ms;best.referenceMonth=parseRefMonth(ref);
                }
            }catch(Exception ignored){}
        }
        if(best==null)throw new IOException("BLS次回日程なし");
        return best;
    }
    private String parseRefMonth(String s){
        try{
            YearMonth ym=YearMonth.parse(s.trim(),DateTimeFormatter.ofPattern("MMMM uuuu",Locale.US));
            return ym.toString();
        }catch(Exception e){return s;}
    }

    private static final class Series{final ArrayList<String> dates=new ArrayList<>();final ArrayList<Double> values=new ArrayList<>();}
    private Series fred(String id)throws Exception{
        String start=LocalDate.now().minusYears(2).toString();
        String csv=get(FRED+URLEncoder.encode(id,"UTF-8")+"&cosd="+start);
        Series s=new Series();String[] lines=csv.split("\\r?\\n");
        for(int i=1;i<lines.length;i++){
            String[] a=lines[i].split(",",-1);if(a.length<2)continue;
            try{double v=Double.parseDouble(a[1].trim());s.dates.add(a[0].trim());s.values.add(v);}catch(Exception ignored){}
        }
        return s;
    }
    private static double last(Series s,int back){return s.values.get(s.values.size()-1-back);}
    private static double diff(Series s,int back){return last(s,back)-last(s,back+1);}
    private static double avgLast(Series s,int n,int offset){double x=0;for(int i=offset;i<offset+n;i++)x+=last(s,i);return x/n;}

    private static String get(String u)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("User-Agent","ChappyFxDemo/2 Android");c.setRequestProperty("Accept","text/csv,text/html,*/*");
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("HTTP "+code);return body;
    }
    private static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}
    private static String clean(String s){return s==null?"":s.replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replaceAll("\\s+"," ").trim();}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static String fmt0(double v){return String.format(Locale.JAPAN,"%.0f",v);}
    private static String fmt1(double v){return String.format(Locale.JAPAN,"%.1f",v);}
    private static String signed0(double v){return String.format(Locale.JAPAN,"%+.0f",v);}
    private static String signed1(double v){return String.format(Locale.JAPAN,"%+.1f",v);}
}
