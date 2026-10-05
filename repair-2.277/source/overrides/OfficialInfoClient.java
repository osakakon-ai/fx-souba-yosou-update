package com.konchan.chappyfx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class OfficialInfoClient {
    public static final class Item {
        public String source,country,title,titleJa,url,date; public long dateMs; public int risk; public String direction,category;
    }
    public static final class Analysis {
        public final ArrayList<Item> items=new ArrayList<>(); public int risk; public String summary="重要情報なし"; public long checkedAt; public boolean fetchSucceeded=false;
    }
    public static final class ShockResult {
        public boolean fetchSucceeded=false;
        public String summary="原因調査待ち",detail="",sources="",confidence="未判定";
        public long checkedAt=0;
    }

    private static final String BOJ="https://www.boj.or.jp/rss/whatsnew.xml";
    private static final String MOF="https://www.mof.go.jp/news.rss";
    private static final String FED_PRESS="https://www.federalreserve.gov/feeds/press_all.xml";
    private static final String FED_SPEECH="https://www.federalreserve.gov/feeds/speeches.xml";
    private static final String TREASURY="https://home.treasury.gov/news/press-releases";
    private static final String BLS_EMPSIT="https://www.bls.gov/feed/empsit.rss";
    private static final String BLS_CPI="https://www.bls.gov/feed/cpi.rss";
    private static final String BLS_JOLTS="https://www.bls.gov/feed/jolts.rss";
    private static final String BLS_PPI="https://www.bls.gov/feed/ppi.rss";
    private static final String CENSUS_ECON="https://www.census.gov/economic-indicators/indicator.xml";

    public Analysis fetch() {
        Analysis a=new Analysis();a.checkedAt=System.currentTimeMillis();int ok=0;
        try{readRss(a,BOJ,"日銀","日本",10);ok++;}catch(Exception ignored){}
        try{readRss(a,MOF,"財務省","日本",10);ok++;}catch(Exception ignored){}
        try{readRss(a,FED_PRESS,"FRB","米国",10);ok++;}catch(Exception ignored){}
        try{readRss(a,FED_SPEECH,"FRB発言","米国",10);ok++;}catch(Exception ignored){}
        try{readTreasury(a);ok++;}catch(Exception ignored){}
        try{readRss(a,BLS_EMPSIT,"米BLS・雇用統計","米国",6);ok++;}catch(Exception ignored){}
        try{readRss(a,BLS_CPI,"米BLS・CPI","米国",6);ok++;}catch(Exception ignored){}
        try{readRss(a,BLS_JOLTS,"米BLS・JOLTS","米国",6);ok++;}catch(Exception ignored){}
        try{readRss(a,BLS_PPI,"米BLS・PPI","米国",6);ok++;}catch(Exception ignored){}
        try{readRss(a,CENSUS_ECON,"米国勢調査局","米国",10);ok++;}catch(Exception ignored){}
        if(ok==0){a.risk=55;a.summary="公式情報を取得できませんでした。保存済みデータを維持します。";a.fetchSucceeded=false;return a;}a.fetchSucceeded=true;
        a.items.removeIf(x->x.title==null||x.title.isEmpty()||!isFxRelevant(x.title));
        for(Item x:a.items){classify(x);a.risk=Math.max(a.risk,x.risk);}
        a.items.sort((x,y)->Integer.compare(y.risk,x.risk));
        if(a.items.size()>40)a.items.subList(40,a.items.size()).clear();
        if(a.items.isEmpty()){a.risk=15;a.summary="公式情報を確認しましたが、直近のドル円重要材料は検出されませんでした。";}
        else{
            Item x=a.items.get(0);
            a.summary=(a.risk>=80?"高警戒：":a.risk>=55?"注意：":"通常：")+x.source+"「"+displayTitle(x)+"」";
        }
        return a;
    }

    public ShockResult investigateShock(double moveYen,int windowSec,long detectedAt){
        ShockResult r=new ShockResult();r.checkedAt=System.currentTimeMillis();
        Analysis a=fetch();r.fetchSucceeded=a.fetchSucceeded;
        String move=(moveYen>=0?"+":"")+String.format(Locale.JAPAN,"%.3f円",moveYen);
        if(!a.fetchSucceeded){r.summary="原因を特定できませんでした";r.detail="ドル円が"+windowSec+"秒で"+move+"動きました。公式情報の取得に失敗したため、原因候補は未確認です。";r.confidence="未確認";return r;}
        ArrayList<Item> candidates=new ArrayList<>();
        for(Item x:a.items){
            if(x==null||x.dateMs<=0)continue;long age=detectedAt-x.dateMs;
            if(age>=-15L*60_000L&&age<=12L*3600_000L)candidates.add(x);
        }
        candidates.sort((x,y)->Integer.compare(shockScore(y,moveYen,detectedAt),shockScore(x,moveYen,detectedAt)));
        if(candidates.isEmpty()){
            r.summary="公式情報では原因未特定";
            r.detail="ドル円が"+windowSec+"秒で"+move+"動きました。日銀・財務省・FRB・米財務省・BLS・米国勢調査局の直近公式情報を照合しましたが、急変時刻と結び付く材料を特定できませんでした。要人発言、市場フロー、地政学要因など別材料の可能性があります。";
            r.confidence="低";r.sources="確認先：日銀 / 財務省 / FRB / 米財務省 / 米BLS / 米国勢調査局";return r;
        }
        Item top=candidates.get(0);long age=detectedAt-top.dateMs;boolean align=shockDirectionMatches(moveYen,top.direction);
        if(age<=2L*3600_000L&&top.risk>=70&&align)r.confidence="高";else if(age<=6L*3600_000L&&top.risk>=55)r.confidence="中";else r.confidence="低";
        r.summary="原因候補："+top.source+"「"+displayTitle(top)+"」";
        r.detail="ドル円が"+windowSec+"秒で"+move+"動いた時刻と、"+(age<0?"急変直後":shockAgo(age))+"の公式発表を照合しました。"+(align?"値動き方向とも整合するため原因候補として優先表示しています。":"時刻は近いものの方向性まで断定できないため候補として表示しています。")+" 因果関係を断定する表示ではありません。";
        StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(3,candidates.size());i++){Item z=candidates.get(i);if(b.length()>0)b.append("\n");b.append("・").append(z.source).append(" / ").append(displayTitle(z)).append(" / ").append(displayDateJst(z));}r.sources=b.toString();return r;
    }
    private static int shockScore(Item x,double move,long detectedAt){
        long age=detectedAt-x.dateMs;int s=x.risk;if(age<=30L*60_000L)s+=55;else if(age<=2L*3600_000L)s+=40;else if(age<=6L*3600_000L)s+=20;if(shockDirectionMatches(move,x.direction))s+=25;String q=(x.title==null?"":x.title).toLowerCase(Locale.US);if(has(q,"fomc","intervention","介入","employment situation","consumer price","cpi","jolts","producer price","retail sales","durable goods"))s+=15;return s;
    }
    private static boolean shockDirectionMatches(double move,String d){if(d==null)return false;if(move>0)return d.contains("ドル円上昇")||d.contains("円安")||d.contains("ドル高");if(move<0)return d.contains("ドル円下落")||d.contains("円高")||d.contains("ドル安");return false;}
    private static String shockAgo(long ms){long min=Math.max(0,ms/60000);return min<60?min+"分前":String.format(Locale.JAPAN,"%.1f時間前",min/60.0);}

    private void readRss(Analysis a,String url,String source,String country,int max)throws Exception{
        String xml=get(url);
        Pattern item=Pattern.compile("<item\\b[^>]*>(.*?)</item>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Matcher m=item.matcher(xml);int n=0;
        while(m.find()&&n<max){
            String b=m.group(1);Item x=new Item();x.source=source;x.country=country;
            x.title=clean(tag(b,"title"));x.url=clean(tag(b,"link"));x.date=clean(tag(b,"pubDate"));x.dateMs=parseDate(x.date);x.titleJa=translateTitle(x.source,x.title);
            if(x.title!=null&&!x.title.isEmpty()){a.items.add(x);n++;}
        }
        if(n==0){
            Pattern entry=Pattern.compile("<entry\\b[^>]*>(.*?)</entry>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
            m=entry.matcher(xml);
            while(m.find()&&n<max){String b=m.group(1);Item x=new Item();x.source=source;x.country=country;x.title=clean(tag(b,"title"));x.date=clean(tag(b,"updated"));x.dateMs=parseDate(x.date);x.url="";x.titleJa=translateTitle(x.source,x.title);if(x.title!=null&&!x.title.isEmpty()){a.items.add(x);n++;}}
        }
    }

    private void readTreasury(Analysis a)throws Exception{
        String h=get(TREASURY);
        Pattern p=Pattern.compile("<h3[^>]*>\\s*<a[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Matcher m=p.matcher(h);int n=0;
        while(m.find()&&n<12){Item x=new Item();x.source="米財務省";x.country="米国";x.url=absolute(m.group(1),TREASURY);x.title=clean(strip(m.group(2)));x.titleJa=translateTitle(x.source,x.title);x.date="";x.dateMs=0;if(!x.title.isEmpty()){a.items.add(x);n++;}}
    }

    public static String displayTitle(Item x){
        if(x==null)return "";
        if(x.titleJa!=null&&!x.titleJa.trim().isEmpty())return x.titleJa.trim();
        return x.title==null?"":x.title;
    }

    public static String displayDateJst(Item x){
        if(x==null||x.dateMs<=0)return "日時: 公式情報に時刻記載なし";
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("M/d HH:mm",java.util.Locale.JAPAN);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        return "日時: "+f.format(new java.util.Date(x.dateMs))+"（日本時間）";
    }

    private static long parseDate(String s){
        if(s==null||s.trim().isEmpty())return 0;
        String[] patterns={"EEE, dd MMM yyyy HH:mm:ss z","EEE, d MMM yyyy HH:mm:ss z","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mm:ss'Z'","yyyy-MM-dd'T'HH:mm:ss.SSSXXX","yyyy-MM-dd"};
        for(String p:patterns){
            try{
                java.text.SimpleDateFormat f=new java.text.SimpleDateFormat(p,java.util.Locale.US);
                f.setLenient(true);
                if(p.endsWith("'Z'"))f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d=f.parse(s.trim());
                if(d!=null)return d.getTime();
            }catch(Exception ignored){}
        }
        return 0;
    }

    private static String translateTitle(String source,String title){
        if(title==null||title.trim().isEmpty())return "";
        String t=title.trim();
        if(!containsLatin(t))return t;
        String q=t.toLowerCase(java.util.Locale.US);

        if(q.contains("federal reserve issues fomc statement"))return "FRBがFOMC声明を公表";
        if(q.contains("federal reserve board and federal open market committee")&&q.contains("economic projections"))return "FRB・FOMCが会合の経済見通しを公表";
        if(q.contains("minutes of the federal open market committee"))return "FOMC議事要旨を公表";
        if(q.contains("fomc minutes"))return "FOMC議事要旨を公表";
        if(q.contains("fomc statement"))return "FOMC声明";
        if(q.contains("monetary policy report"))return "FRB金融政策報告";
        if(q.contains("financial stability report"))return "FRB金融安定報告";
        if(q.contains("employment situation"))return "米雇用統計（Employment Situation）";
        if(q.contains("consumer price index")||q.contains("consumer prices"))return "米消費者物価指数（CPI）";
        if(q.contains("job openings and labor turnover")||q.contains("jolts"))return "米求人・労働異動調査（JOLTS）";
        if(q.contains("producer price index")||q.contains("producer prices"))return "米生産者物価指数（PPI）";
        if(q.contains("retail sales"))return "米小売売上高";
        if(q.contains("durable goods"))return "米耐久財受注";
        if(q.contains("new residential sales")||q.contains("new home sales"))return "米新築住宅販売";
        if(q.contains("international trade")||q.contains("trade deficit"))return "米貿易統計";

        int comma=t.indexOf(',');
        if(("FRB発言".equals(source)||"FRB".equals(source))&&comma>0&&comma<t.length()-1){
            String person=speakerJa(t.substring(0,comma).trim());
            String topic=topicJa(t.substring(comma+1).trim());
            return person+"："+topic;
        }

        if("米財務省".equals(source)){
            if(q.contains("foreign exchange")||q.contains("currency"))return "米財務省：為替・通貨政策に関する発表";
            if(q.contains("treasury market")||q.contains("treasury securities"))return "米財務省：米国債市場に関する発表";
            if(q.contains("economic")||q.contains("financial"))return "米財務省：経済・金融に関する発表";
            return "米財務省の公式発表";
        }

        if(q.contains("inflation"))return source+"：インフレ・物価に関する発表";
        if(q.contains("labor market")||q.contains("employment"))return source+"：雇用・労働市場に関する発表";
        if(q.contains("economic outlook"))return source+"：経済見通しに関する発表";
        if(q.contains("monetary policy"))return source+"：金融政策に関する発表";
        if(q.contains("interest rate")||q.contains("rates"))return source+"：金利に関する発表";
        if(q.contains("treasury market"))return source+"：米国債市場に関する発表";
        return source+"の経済・金融に関する公式発表";
    }

    private static String speakerJa(String s){
        String q=s.toLowerCase(java.util.Locale.US).trim();
        if(q.contains("powell"))return "パウエル議長";
        if(q.contains("jefferson"))return "ジェファーソン副議長";
        if(q.contains("waller"))return "ウォラー理事";
        if(q.contains("cook"))return "クック理事";
        if(q.contains("bowman"))return "ボウマン副議長";
        if(q.contains("williams"))return "ウィリアムズ総裁";
        if(q.contains("kashkari"))return "カシュカリ総裁";
        if(q.contains("bostic"))return "ボスティック総裁";
        if(q.contains("daly"))return "デイリー総裁";
        if(q.contains("goolsbee"))return "グールズビー総裁";
        if(q.contains("collins"))return "コリンズ総裁";
        return "FRB関係者";
    }

    private static String topicJa(String s){
        String q=s.toLowerCase(java.util.Locale.US);
        if(q.contains("navigating economic shocks")&&q.contains("monetary policymaker"))return "経済ショックへの対応―金融政策担当者の視点";
        if(q.contains("economic outlook")&&q.contains("policy communication"))return "経済見通しと政策コミュニケーションについて";
        if(q.equals("economic outlook")||q.startsWith("economic outlook"))return "経済見通し";
        if(q.contains("discount window")&&q.contains("treasury market"))return "連銀貸出制度の近代化と米国債市場の機能";
        if(q.contains("inflation"))return "インフレ・物価動向について";
        if(q.contains("labor market")||q.contains("employment"))return "雇用・労働市場について";
        if(q.contains("monetary policy"))return "金融政策について";
        if(q.contains("financial stability"))return "金融安定について";
        if(q.contains("treasury market"))return "米国債市場について";
        if(q.contains("interest rate")||q.contains("rates"))return "金利について";
        return "経済・金融政策に関する講演";
    }

    private static boolean containsLatin(String s){
        for(int i=0;i<s.length();i++){char ch=s.charAt(i);if((ch>='A'&&ch<='Z')||(ch>='a'&&ch<='z'))return true;}
        return false;
    }

    private static boolean isFxRelevant(String s){
        String q=s.toLowerCase(Locale.US);
        String[] k={"金融政策","政策金利","金利","物価","インフレ","為替","外国為替","介入","円","総裁","経済見通し","fomc","monetary policy","interest rate","inflation","economic outlook","labor market","employment","employment situation","job openings","jolts","consumer price","producer price","retail sales","durable goods","new residential sales","new home sales","international trade","trade deficit","dollar","foreign exchange","currency","treasury market","chair","governor","cpi","ppi","gdp"};
        for(String z:k)if(q.contains(z.toLowerCase(Locale.US)))return true;return false;
    }

    private static void classify(Item x){
        String q=(x.title==null?"":x.title).toLowerCase(Locale.US);
        int r=30;String cat="関連情報";
        if(has(q,"為替","介入","foreign exchange","currency","fomc","金融政策","monetary policy","政策金利","interest rate")){r=90;cat="金融政策・為替";}
        else if(has(q,"employment situation","consumer price","producer price","job openings","jolts","retail sales","durable goods")){r=80;cat="主要経済指標";}
        else if(has(q,"総裁","chair","governor","economic outlook","経済見通し","inflation","インフレ","物価","labor market","employment","cpi","ppi","gdp","international trade","trade deficit","new home sales","new residential sales")){r=70;cat="要人発言・経済指標";}
        else if(has(q,"treasury market","国債","金利")){r=55;cat="金利・債券";}
        x.risk=r;x.category=cat;x.direction=direction(x.country,q);
    }

    private static String direction(String country,String q){
        if(has(q,"介入","intervention"))return "円高方向への急変に警戒";
        boolean hawk=has(q,"利上げ","引き締め","rate hike","higher rates","tightening","inflation pressure");
        boolean dove=has(q,"利下げ","緩和","rate cut","easing","lower rates");
        if("日本".equals(country)){if(hawk)return "円高・ドル円下落材料になり得る";if(dove)return "円安・ドル円上昇材料になり得る";}
        if("米国".equals(country)){if(hawk)return "ドル高・ドル円上昇材料になり得る";if(dove)return "ドル安・ドル円下落材料になり得る";}
        return "方向は本文・市場反応を確認";
    }
    private static boolean has(String q,String... ks){for(String k:ks)if(q.contains(k.toLowerCase(Locale.US)))return true;return false;}

    private static String get(String u)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestProperty("User-Agent","ChappyFxDemo/2 Android");c.setRequestProperty("Accept","application/xml,text/xml,application/rss+xml,text/html,*/*");
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("HTTP "+code);return body;
    }
    private static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}
    private static String tag(String b,String t){Matcher m=Pattern.compile("<"+t+"\\b[^>]*>(.*?)</"+t+">",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(b);return m.find()?m.group(1):"";}
    private static String strip(String s){return s==null?"":s.replaceAll("<[^>]+>"," ");}
    private static String clean(String s){if(s==null)return "";return strip(s).replace("<![CDATA[","").replace("]]>","").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">").replaceAll("\\s+"," ").trim();}
    private static String absolute(String u,String base){if(u==null)return "";if(u.startsWith("http"))return u;try{return new URL(new URL(base),u).toString();}catch(Exception e){return u;}}
}
