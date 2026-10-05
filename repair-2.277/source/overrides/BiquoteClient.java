package com.konchan.chappyfx;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;

public final class BiquoteClient {
    private static final String BASE="https://biquote.io/api/USDJPY/ohlc";
    private static final String BOJ_DATA_URL="https://www.stat-search.boj.or.jp/api/v1/getDataCode";
    private static final String YAHOO_CHART_URL="https://query1.finance.yahoo.com/v8/finance/chart/JPY=X";

    public Map<String,List<MarketEngine.Candle>> fetchAll() throws Exception {
        LinkedHashMap<String,List<MarketEngine.Candle>> out=new LinkedHashMap<>();
        out.put("M5",fetch("5m",300));out.put("M15",fetch("15m",300));out.put("M30",fetch("30m",300));
        List<MarketEngine.Candle> h1=fetch("1h",500);out.put("H1",h1);
        // 2H/4H/8H are rebuilt from H1 on JST clock boundaries (00:00 anchor).
        // This prevents UTC-origin 09/11/13... style offsets from shifting P/B positions.
        out.put("H2",aggregateFixedHoursJst(h1,2));out.put("H4",aggregateFixedHoursJst(h1,4));out.put("H8",aggregateFixedHoursJst(h1,8));
        List<MarketEngine.Candle> daily=fetch("1d",1000);out.put("D",daily);
        out.put("W",aggregateCalendar(daily,false));out.put("MN",aggregateCalendar(daily,true));
        for(String k:MarketEngine.TF)if(out.get(k)==null||out.get(k).size()<18)throw new IOException(k+"データ不足: "+(out.get(k)==null?0:out.get(k).size()));
        if(out.get("W")==null||out.get("W").size()<18)throw new IOException("Wデータ不足: "+(out.get("W")==null?0:out.get("W").size()));
        if(out.get("MN")==null||out.get("MN").size()<12)throw new IOException("MNデータ不足: "+(out.get("MN")==null?0:out.get("MN").size()));
        return out;
    }

    public List<MarketEngine.Candle> fetchTimeframe(String tf) throws Exception {
        if("M5".equals(tf))return fetch("5m",300);
        if("M15".equals(tf))return fetch("15m",300);
        if("M30".equals(tf))return fetch("30m",300);
        if("H1".equals(tf))return fetch("1h",500);
        if("H2".equals(tf))return aggregateFixedHoursJst(fetch("1h",500),2);
        if("H4".equals(tf))return aggregateFixedHoursJst(fetch("1h",500),4);
        if("H8".equals(tf))return aggregateFixedHoursJst(fetch("1h",500),8);
        if("D".equals(tf))return fetch("1d",1000);
        if("W".equals(tf))return aggregateCalendar(fetch("1d",1000),false);
        if("MN".equals(tf)){
            List<MarketEngine.Candle> recent=aggregateCalendar(fetch("1d",1000),true);
            try{
                List<MarketEngine.Candle> history24h=fetchYahooMonthlyHistory(8);
                List<MarketEngine.Candle> merged=mergeMonthly(history24h,recent);
                if(merged.size()>=48)return merged;
            }catch(Exception ignored){}
            try{
                List<MarketEngine.Candle> official=fetchBojMonthlyHistory(8);
                List<MarketEngine.Candle> merged=mergeMonthly(official,recent);
                if(merged.size()>=48)return merged;
            }catch(Exception ignored){}
            return aggregateCalendar(fetchDailyHistory(1800),true);
        }
        throw new IllegalArgumentException("未対応の時間足: "+tf);
    }

    private List<MarketEngine.Candle> fetchYahooMonthlyHistory(int years) throws Exception {
        long period2=Instant.now().plusSeconds(86400L).getEpochSecond();
        long period1=ZonedDateTime.now(ZoneOffset.UTC).minusYears(Math.max(6,years)).toEpochSecond();
        String url=YAHOO_CHART_URL
                +"?period1="+period1
                +"&period2="+period2
                +"&interval=1d&events=history&includeAdjustedClose=false";
        URL u=new URL(url);HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestMethod("GET");
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Mozilla/5.0 ChappyFxDemo/1.0");
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();
        if(code<200||code>=300)throw new IOException("history24h HTTP "+code);

        JSONObject root=new JSONObject(body);
        JSONObject chart=root.optJSONObject("chart");if(chart==null)throw new IOException("history24h chartなし");
        JSONArray result=chart.optJSONArray("result");if(result==null||result.length()==0)throw new IOException("history24h resultなし");
        JSONObject r=result.optJSONObject(0);if(r==null)throw new IOException("history24h result不正");
        JSONArray ts=r.optJSONArray("timestamp");
        JSONObject indicators=r.optJSONObject("indicators");
        JSONArray quotes=indicators==null?null:indicators.optJSONArray("quote");
        JSONObject q=quotes==null||quotes.length()==0?null:quotes.optJSONObject(0);
        if(ts==null||q==null)throw new IOException("history24h OHLCなし");

        JSONArray oa=q.optJSONArray("open"),ha=q.optJSONArray("high"),la=q.optJSONArray("low"),ca=q.optJSONArray("close");
        if(oa==null||ha==null||la==null||ca==null)throw new IOException("history24h arraysなし");
        int n=Math.min(ts.length(),Math.min(oa.length(),Math.min(ha.length(),Math.min(la.length(),ca.length()))));
        ArrayList<MarketEngine.Candle> daily=new ArrayList<>();
        for(int i=0;i<n;i++){
            if(ts.isNull(i)||oa.isNull(i)||ha.isNull(i)||la.isNull(i)||ca.isNull(i))continue;
            long tm=ts.optLong(i,0L)*1000L;if(tm<=0)continue;
            double o=oa.optDouble(i,Double.NaN),h=ha.optDouble(i,Double.NaN),l=la.optDouble(i,Double.NaN),cl=ca.optDouble(i,Double.NaN);
            if(Double.isNaN(o)||Double.isNaN(h)||Double.isNaN(l)||Double.isNaN(cl)||h<l)continue;
            daily.add(new MarketEngine.Candle(tm,o,h,l,cl,0,false));
        }
        daily.sort(Comparator.comparingLong(x->x.timeMs));
        List<MarketEngine.Candle> monthly=aggregateCalendar(daily,true);
        if(monthly.size()<48)throw new IOException("history24h 月足履歴不足: "+monthly.size()+"本");
        return monthly;
    }

    private List<MarketEngine.Candle> fetchBojMonthlyHistory(int years) throws Exception {
        int endYear=ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).getYear();
        int startYear=Math.max(1998,endYear-Math.max(4,years)+1);
        String startDate=startYear+"01";
        String endDate=endYear+String.format(Locale.ROOT,"%02d",ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).getMonthValue());
        String codes="FXERD01,FXERD02,FXERD03,FXERD04";
        String url=BOJ_DATA_URL
                +"?format=json&lang=en&db=FM08"
                +"&startDate="+URLEncoder.encode(startDate,"UTF-8")
                +"&endDate="+URLEncoder.encode(endDate,"UTF-8")
                +"&code="+URLEncoder.encode(codes,"UTF-8");
        URL u=new URL(url);HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestMethod("GET");
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","ChappyFxDemo/1.0 Android");
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();
        if(code<200||code>=300)throw new IOException("BOJ HTTP "+code);
        JSONObject root=new JSONObject(body);
        if(root.optInt("STATUS",0)!=200)throw new IOException("BOJ STATUS "+root.optInt("STATUS",0)+": "+root.optString("MESSAGE",""));
        JSONArray result=root.optJSONArray("RESULTSET");if(result==null||result.length()==0)throw new IOException("BOJ RESULTSETなし");

        final class Day { double o=Double.NaN,h=Double.NaN,l=Double.NaN,cl=Double.NaN; }
        TreeMap<Integer,Day> days=new TreeMap<>();
        for(int r=0;r<result.length();r++){
            JSONObject rs=result.optJSONObject(r);if(rs==null)continue;
            String sc=rs.optString("SERIES_CODE","");
            JSONObject vals=rs.optJSONObject("VALUES");if(vals==null)continue;
            JSONArray dates=vals.optJSONArray("SURVEY_DATES"),values=vals.optJSONArray("VALUES");
            if(dates==null||values==null)continue;
            int n=Math.min(dates.length(),values.length());
            for(int i=0;i<n;i++){
                int d=dates.optInt(i,0);if(d<19980101)continue;
                Object vo=values.opt(i);if(vo==null||vo==JSONObject.NULL)continue;
                double v;try{v=Double.parseDouble(String.valueOf(vo));}catch(Exception e){continue;}
                Day x=days.get(d);if(x==null){x=new Day();days.put(d,x);}
                if("FXERD01".equals(sc))x.o=v;
                else if("FXERD02".equals(sc))x.h=v;
                else if("FXERD03".equals(sc))x.l=v;
                else if("FXERD04".equals(sc))x.cl=v;
            }
        }

        ArrayList<MarketEngine.Candle> out=new ArrayList<>();
        int monthKey=-1;double mo=Double.NaN,mh=Double.NaN,ml=Double.NaN,mc=Double.NaN;long mt=0;
        for(Map.Entry<Integer,Day> e:days.entrySet()){
            int d=e.getKey(),mk=d/100;Day x=e.getValue();
            if(mk!=monthKey&&monthKey!=-1){
                if(!Double.isNaN(mo)&&!Double.isNaN(mh)&&!Double.isNaN(ml)&&!Double.isNaN(mc))out.add(new MarketEngine.Candle(mt,mo,mh,ml,mc,0,false));
                mo=mh=ml=mc=Double.NaN;mt=0;
            }
            if(mk!=monthKey){monthKey=mk;int y=mk/100,m=mk%100;mt=LocalDate.of(y,m,1).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();}
            if(Double.isNaN(mo)){
                if(!Double.isNaN(x.o))mo=x.o;
                else if(!Double.isNaN(x.cl))mo=x.cl;
            }
            if(!Double.isNaN(x.h))mh=Double.isNaN(mh)?x.h:Math.max(mh,x.h);
            if(!Double.isNaN(x.l))ml=Double.isNaN(ml)?x.l:Math.min(ml,x.l);
            if(!Double.isNaN(x.cl))mc=x.cl;
        }
        if(monthKey!=-1&&!Double.isNaN(mo)&&!Double.isNaN(mh)&&!Double.isNaN(ml)&&!Double.isNaN(mc))out.add(new MarketEngine.Candle(mt,mo,mh,ml,mc,0,false));
        if(out.size()<48)throw new IOException("BOJ月足履歴不足: "+out.size()+"本");
        return out;
    }

    private List<MarketEngine.Candle> mergeMonthly(List<MarketEngine.Candle> older,List<MarketEngine.Candle> recent){
        TreeMap<String,MarketEngine.Candle> m=new TreeMap<>();
        if(older!=null)for(MarketEngine.Candle x:older)m.put(monthKey(x.timeMs),x);
        if(recent!=null)for(MarketEngine.Candle x:recent)m.put(monthKey(x.timeMs),x);
        return new ArrayList<>(m.values());
    }

    private String monthKey(long t){
        ZonedDateTime z=Instant.ofEpochMilli(t).atZone(ZoneId.of("Asia/Tokyo"));
        return String.format(Locale.ROOT,"%04d-%02d",z.getYear(),z.getMonthValue());
    }

    private List<MarketEngine.Candle> aggregateFixedHoursJst(List<MarketEngine.Candle> hourly,int spanHours){
        ArrayList<MarketEngine.Candle> out=new ArrayList<>();if(hourly==null||hourly.isEmpty())return out;
        if(spanHours!=2&&spanHours!=4&&spanHours!=8)throw new IllegalArgumentException("unsupported fixed-hour span: "+spanHours);
        final ZoneId zone=ZoneId.of("Asia/Tokyo");
        long bucket=Long.MIN_VALUE;MarketEngine.Candle first=null,last=null;double hi=Double.NaN,lo=Double.NaN;
        for(MarketEngine.Candle r:hourly){
            ZonedDateTime z=Instant.ofEpochMilli(r.timeMs).atZone(zone);
            int startHour=(z.getHour()/spanHours)*spanHours;
            long key=z.toLocalDate().atTime(startHour,0).atZone(zone).toInstant().toEpochMilli();
            if(bucket!=Long.MIN_VALUE&&key!=bucket&&first!=null&&last!=null){
                out.add(new MarketEngine.Candle(bucket,first.o,hi,lo,last.c,0,false));
                first=null;last=null;hi=Double.NaN;lo=Double.NaN;
            }
            if(first==null){first=r;hi=r.h;lo=r.l;}else{hi=Math.max(hi,r.h);lo=Math.min(lo,r.l);}
            last=r;bucket=key;
        }
        if(first!=null&&last!=null)out.add(new MarketEngine.Candle(bucket,first.o,hi,lo,last.c,0,false));
        validateFixedHourAlignment(out,spanHours);
        return out;
    }

    private void validateFixedHourAlignment(List<MarketEngine.Candle> rows,int spanHours){
        ZoneId zone=ZoneId.of("Asia/Tokyo");
        long prev=Long.MIN_VALUE;
        for(MarketEngine.Candle r:rows){
            ZonedDateTime z=Instant.ofEpochMilli(r.timeMs).atZone(zone);
            if(z.getMinute()!=0||z.getSecond()!=0||z.getHour()%spanHours!=0)throw new IllegalStateException(spanHours+"H JST境界不正: "+z);
            if(prev!=Long.MIN_VALUE&&r.timeMs<=prev)throw new IllegalStateException(spanHours+"H 時系列順序不正");
            prev=r.timeMs;
        }
    }

    private List<MarketEngine.Candle> aggregateCalendar(List<MarketEngine.Candle> daily,boolean monthly){
        ArrayList<MarketEngine.Candle> out=new ArrayList<>();if(daily==null||daily.isEmpty())return out;
        WeekFields wf=WeekFields.ISO;String key=null;MarketEngine.Candle first=null,last=null;double hi=Double.NaN,lo=Double.NaN;
        for(MarketEngine.Candle r:daily){
            ZonedDateTime z=Instant.ofEpochMilli(r.timeMs).atZone(ZoneId.of("Asia/Tokyo"));
            String k=monthly?(z.getYear()+"-"+z.getMonthValue()):(z.get(wf.weekBasedYear())+"-W"+z.get(wf.weekOfWeekBasedYear()));
            if(key!=null&&!key.equals(k)&&first!=null){
                out.add(new MarketEngine.Candle(first.timeMs,first.o,hi,lo,last.c,0,false));
                first=null;last=null;hi=Double.NaN;lo=Double.NaN;
            }
            if(first==null){first=r;hi=r.h;lo=r.l;}else{hi=Math.max(hi,r.h);lo=Math.min(lo,r.l);}
            last=r;key=k;
        }
        if(first!=null&&last!=null)out.add(new MarketEngine.Candle(first.timeMs,first.o,hi,lo,last.c,0,false));
        return out;
    }

    private List<MarketEngine.Candle> fetch(String interval,int limit) throws Exception {
        return fetchPage(interval,limit,null);
    }

    private List<MarketEngine.Candle> fetchDailyHistory(int targetBars) throws Exception {
        // Use explicit date windows.  A plain latest-bars request may not expose
        // enough older D1 history for monthly RCI/PB, while from/to is documented.
        int years=Math.max(6,(int)Math.ceil(targetBars/250.0));
        TreeMap<Long,MarketEngine.Candle> merged=new TreeMap<>();
        ZonedDateTime end=ZonedDateTime.now(ZoneOffset.UTC).plusDays(1);
        int remaining=years;
        while(remaining>0){
            int span=Math.min(2,remaining);
            ZonedDateTime start=end.minusYears(span);
            List<MarketEngine.Candle> part;
            try{part=fetchRange("1d",1000,start.toInstant(),end.toInstant());}
            catch(Exception e){
                if(merged.size()>=1100)break;
                throw e;
            }
            if(part!=null)for(MarketEngine.Candle x:part)merged.put(x.timeMs,x);
            end=start;remaining-=span;
        }
        ArrayList<MarketEngine.Candle> out=new ArrayList<>(merged.values());
        if(out.size()>targetBars)return new ArrayList<>(out.subList(out.size()-targetBars,out.size()));
        return out;
    }

    private List<MarketEngine.Candle> fetchRange(String interval,int limit,Instant from,Instant to) throws Exception {
        StringBuilder q=new StringBuilder(BASE)
                .append("?interval=").append(URLEncoder.encode(interval,"UTF-8"))
                .append("&limit=").append(Math.max(1,Math.min(1000,limit)))
                .append("&from=").append(URLEncoder.encode(from.toString(),"UTF-8"))
                .append("&to=").append(URLEncoder.encode(to.toString(),"UTF-8"));
        return fetchUrl(interval,q.toString());
    }

    private List<MarketEngine.Candle> fetchPage(String interval,int limit,Long toMs) throws Exception {
        StringBuilder q=new StringBuilder(BASE)
                .append("?interval=").append(URLEncoder.encode(interval,"UTF-8"))
                .append("&limit=").append(Math.max(1,Math.min(1000,limit)));
        if(toMs!=null)q.append("&to=").append(URLEncoder.encode(Instant.ofEpochMilli(toMs).toString(),"UTF-8"));
        return fetchUrl(interval,q.toString());
    }

    private List<MarketEngine.Candle> fetchUrl(String interval,String url) throws Exception {
        URL u=new URL(url);HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestMethod("GET");c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","ChappyFxDemo/1.0 Android");
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();if(code<200||code>=300)throw new IOException(interval+" HTTP "+code+": "+body);
        JSONObject j=new JSONObject(body);JSONArray bars=j.optJSONArray("bars");if(bars==null)throw new IOException(interval+" barsなし");ArrayList<MarketEngine.Candle> out=new ArrayList<>();
        for(int i=0;i<bars.length();i++){JSONObject b=bars.getJSONObject(i);String t=b.optString("openTime","");if(t.isEmpty())continue;double o=b.optDouble("open",Double.NaN),h=b.optDouble("high",Double.NaN),l=b.optDouble("low",Double.NaN),cl=b.optDouble("close",Double.NaN);if(Double.isNaN(o)||Double.isNaN(h)||Double.isNaN(l)||Double.isNaN(cl))continue;long tm=Instant.parse(t).toEpochMilli();long vol=b.optLong("tickVolume",b.optLong("volume",0));boolean open=b.optBoolean("isOpen",false);out.add(new MarketEngine.Candle(tm,o,h,l,cl,vol,open));}
        out.sort(Comparator.comparingLong(x->x.timeMs));return out;
    }
    private static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}
}
