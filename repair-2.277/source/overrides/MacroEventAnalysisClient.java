package com.konchan.chappyfx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class MacroEventAnalysisClient {
    public static final class Result {
        public long checkedAt;
        public String eventType="",headline="",outlook="",dataSummary="",watchPoints="",upScenario="",downScenario="",sourceNote="";
        public int confidence=0;
        public boolean fetchSucceeded=false;
    }

    private static final String FRED="https://fred.stlouisfed.org/graph/fredgraph.csv?id=";

    public Result analyze(String source,String title,String category,String direction,DemoStore store){
        Result r=new Result();r.checkedAt=System.currentTimeMillis();
        String q=((title==null?"":title)+" "+(category==null?"":category)).toLowerCase(Locale.US);
        try{
            if(has(q,"employment","payroll","雇用","失業","labor market","jolts")){
                return employment(source,title,store);
            }else if(has(q,"inflation","cpi","pce","物価","インフレ")){
                return inflation(source,title,store);
            }else if(has(q,"fomc","monetary policy","interest rate","金融政策","政策金利","利上げ","利下げ")){
                return monetary(source,title,store);
            }else if(has(q,"gdp","economic outlook","経済見通し","景気")){
                return growth(source,title,store);
            }else if(has(q,"treasury market","treasury securities","国債","金利・債券")){
                return rates(source,title,store);
            }else if(has(q,"foreign exchange","currency","為替","介入")){
                return fx(source,title,store);
            }else{
                return generic(source,title,category,direction,store);
            }
        }catch(Exception e){
            r.eventType=typeLabel(q);r.headline="保存済み市場データを使った事前分析";
            r.outlook="外部の補助データ取得に失敗したため、現在のドル円テクニカルとイベント重要度を中心に確認します。";
            r.dataSummary=techSummary(store);
            r.watchPoints="発表本文の強弱、従来方針との差、発表直後5〜30分の価格反応を優先して確認。";
            r.upScenario="ドル高材料が明確でM15・M30も上向く場合はドル円上昇シナリオ。";
            r.downScenario="ドル安・円高材料が明確でM15・M30も下向く場合はドル円下落シナリオ。";
            r.sourceNote="アプリ保存済み相場データを使用。";r.confidence=35;r.fetchSucceeded=false;return r;
        }
    }

    private Result employment(String source,String title,DemoStore store)throws Exception{
        EmploymentForecastClient.Result e=new EmploymentForecastClient().fetch(store.employmentCalibrationK());
        Result r=base("雇用・労働市場",source,title,store);
        if(e.fetchSucceeded){
            store.saveEmploymentForecast(e);
            r.headline="米雇用統計の先行データを統合";
            r.outlook=e.summary+" / "+e.bias;
            StringBuilder b=new StringBuilder();
            for(int i=0;i<e.factors.size()&&i<6;i++){if(i>0)b.append("\n");b.append("・").append(e.factors.get(i));}
            r.dataSummary=b.toString();
            r.watchPoints="非農業部門雇用者数だけでなく、失業率・平均時給・前月改定を同時に確認。初動と賃金の方向が食い違う場合は追随を急がない。";
            r.upScenario="雇用者数と賃金がともに強く、失業率も悪化しなければドル高材料になりやすい。";
            r.downScenario="雇用者数が弱く、失業率上昇や賃金鈍化も伴えばドル安材料になりやすい。";
            r.confidence=e.confidence;r.sourceNote="BLS公表日程、FRED経由のBLS/米労働省系列、アプリのドル円分析を使用。";r.fetchSucceeded=true;
        }
        return r;
    }

    private Result inflation(String source,String title,DemoStore store)throws Exception{
        Result r=base("物価・インフレ",source,title,store);
        Series cpi=fred("CPIAUCSL"),core=fred("CPILFESL"),pce=fred("PCEPI"),corePce=fred("PCEPILFE");
        double cpiM=mom(cpi),cpiY=yoy(cpi),coreM=mom(core),coreY=yoy(core),pceY=yoy(pce),corePceY=yoy(corePce);
        r.headline="物価の勢いを総合確認";
        r.outlook=(coreM>=.3||corePceY>=3.0)?"基調インフレはやや強め":"基調インフレは中立〜鈍化寄り";
        r.dataSummary=String.format(Locale.JAPAN,"CPI 前月比 %+.2f%% / 前年比 %.2f%%\nコアCPI 前月比 %+.2f%% / 前年比 %.2f%%\nPCE 前年比 %.2f%% / コアPCE 前年比 %.2f%%\n%s",cpiM,cpiY,coreM,coreY,pceY,corePceY,techSummary(store));
        r.watchPoints="市場は総合CPIだけでなくコア、住居費、サービス物価の粘着性を重視しやすい。前月値の改定にも注意。";
        r.upScenario="予想より強いインフレ、とくにコアやサービスが上振れすると米金利上昇を通じドル円上昇材料になりやすい。";
        r.downScenario="コアを含め明確に鈍化すれば利下げ期待が強まり、ドル円下落材料になりやすい。";
        r.confidence=62;r.sourceNote="FRED経由のBLS/BEA物価系列とアプリのドル円分析を使用。";r.fetchSucceeded=true;return r;
    }

    private Result monetary(String source,String title,DemoStore store)throws Exception{
        Result r=base("金融政策・FOMC",source,title,store);
        Series dff=fred("DFF"),y2=fred("DGS2"),y10=fred("DGS10");
        r.headline="政策金利と米金利の織り込みを確認";
        r.outlook=String.format(Locale.JAPAN,"実効FF金利 %.2f%% / 米2年 %.2f%% / 米10年 %.2f%%",last(dff),last(y2),last(y10));
        r.dataSummary=techSummary(store);
        r.watchPoints="声明文の変更、政策金利、ドットプロット、議長会見でのインフレ・雇用評価を順に確認。既に織り込まれた内容なら逆方向反応にも注意。";
        r.upScenario="想定よりタカ派で米2年金利が上昇し、短期足も上向けばドル円上昇シナリオが強まる。";
        r.downScenario="想定よりハト派で米2年金利が低下し、短期足も下向けばドル円下落シナリオが強まる。";
        r.confidence=68;r.sourceNote="Federal Reserve公式発表、FRED金利系列、アプリのドル円分析を使用。";r.fetchSucceeded=true;return r;
    }

    private Result growth(String source,String title,DemoStore store)throws Exception{
        Result r=base("景気・GDP・経済見通し",source,title,store);
        Series gdp=fred("GDPC1"),un=fred("UNRATE"),claims=fred("ICSA");
        double q=annualizedQuarterly(gdp),ur=last(un),cl=avgLast(claims,4);
        r.headline="景気の強さと雇用の減速兆候を確認";
        r.outlook=String.format(Locale.JAPAN,"実質GDPの直近四半期ペース 約%+.1f%%年率 / 失業率 %.1f%%",q,ur);
        r.dataSummary=String.format(Locale.JAPAN,"新規失業保険申請 4週平均 %.0f千件\n%s",cl/1000,techSummary(store));
        r.watchPoints="成長率だけでなく個人消費、設備投資、物価指標、在庫寄与を分けて確認。";
        r.upScenario="成長と需要が予想以上に強ければ米金利上昇を通じドル高材料になりやすい。";
        r.downScenario="成長鈍化が明確で雇用指標も弱ければドル安材料になりやすい。";
        r.confidence=55;r.sourceNote="FRED経由のBEA/BLS/米労働省系列とアプリのドル円分析を使用。";r.fetchSucceeded=true;return r;
    }

    private Result rates(String source,String title,DemoStore store)throws Exception{
        Result r=base("金利・米国債",source,title,store);
        Series y2=fred("DGS2"),y10=fred("DGS10");
        double a=last(y2),b=last(y10);
        r.headline="米2年・10年金利の方向を確認";
        r.outlook=String.format(Locale.JAPAN,"米2年 %.2f%% / 米10年 %.2f%% / 10年-2年 %+.2fpt",a,b,b-a);
        r.dataSummary=techSummary(store);
        r.watchPoints="ドル円は米2年金利への反応が強い局面があるため、発表後の2年金利とドル円の同時反応を確認。";
        r.upScenario="米金利上昇と短期足上向きが一致すればドル円上昇を後押ししやすい。";
        r.downScenario="米金利低下と短期足下向きが一致すればドル円下落を後押ししやすい。";
        r.confidence=60;r.sourceNote="FRED米国債利回り系列とアプリのドル円分析を使用。";r.fetchSucceeded=true;return r;
    }

    private Result fx(String source,String title,DemoStore store){
        Result r=base("為替・介入",source,title,store);
        r.headline="介入・為替政策の文言と現在の短期方向を確認";
        r.outlook="介入警戒は方向を断定する材料ではなく、急変動リスクとして扱います。";
        r.dataSummary=techSummary(store);
        r.watchPoints="『過度な変動』『投機的』『あらゆる手段』など文言の強さ、発言者、直後の値動きを確認。実際の介入有無は公式公表で確認。";
        r.upScenario="警戒発言だけで実弾介入がなく、米金利上昇・短期足上向きが続けばドル円が戻す場合がある。";
        r.downScenario="介入警戒が強まり、円買い反応と短期足下向きが重なると急落リスクが高まる。";
        r.confidence=50;r.sourceNote="財務省・日銀等の公式発表タイトルとアプリのドル円分析を使用。";r.fetchSucceeded=true;return r;
    }

    private Result generic(String source,String title,String category,String direction,DemoStore store){
        Result r=base(typeLabel((title+" "+category).toLowerCase(Locale.US)),source,title,store);
        r.headline="イベント内容と現在の相場状況を総合確認";
        r.outlook=direction==null||direction.isEmpty()?"方向は発表内容と市場反応を確認":direction;
        r.dataSummary=techSummary(store);
        r.watchPoints="発表内容が従来見通しからどれだけ変化したか、ドル円と米金利が同方向に反応するかを確認。";
        r.upScenario="ドル高材料と短期上向きが一致した場合は上昇シナリオ。";
        r.downScenario="ドル安・円高材料と短期下向きが一致した場合は下落シナリオ。";
        r.confidence=42;r.sourceNote="公式イベント情報とアプリのドル円分析を使用。";r.fetchSucceeded=true;return r;
    }

    private Result base(String type,String source,String title,DemoStore store){Result r=new Result();r.checkedAt=System.currentTimeMillis();r.eventType=type;return r;}
    private static String techSummary(DemoStore s){return "短期方向 M5 "+ja(s.tfDir("M5"))+" / M15 "+ja(s.tfDir("M15"))+" / M30 "+ja(s.tfDir("M30"))+" / H1 "+ja(s.tfDir("H1"))+"\n短期準備度 "+s.tradeReadiness()+"% / 短期一致度 "+s.tradeAgreement()+"% / 長期背景リスク "+s.backgroundRisk()+"%";}
    private static String ja(String d){return "up".equals(d)?"上":"down".equals(d)?"下":"中立";}
    private static boolean has(String q,String...ks){for(String k:ks)if(q.contains(k.toLowerCase(Locale.US)))return true;return false;}
    private static String typeLabel(String q){if(has(q,"金融","fomc"))return "金融政策";if(has(q,"雇用","employment"))return "雇用";if(has(q,"物価","inflation"))return "物価";if(has(q,"国債","treasury"))return "金利・債券";if(has(q,"為替","currency"))return "為替";return "経済・金融イベント";}

    private static final class Series{final ArrayList<Double> v=new ArrayList<>();}
    private Series fred(String id)throws Exception{
        String start=LocalDate.now().minusYears(3).toString();
        String csv=get(FRED+URLEncoder.encode(id,"UTF-8")+"&cosd="+start);
        Series s=new Series();String[] lines=csv.split("\\r?\\n");
        for(int i=1;i<lines.length;i++){String[] a=lines[i].split(",",-1);if(a.length<2)continue;try{s.v.add(Double.parseDouble(a[1].trim()));}catch(Exception ignored){}}
        if(s.v.size()<2)throw new IOException(id+"データ不足");return s;
    }
    private static double last(Series s){return s.v.get(s.v.size()-1);}
    private static double mom(Series s){double a=s.v.get(s.v.size()-1),b=s.v.get(s.v.size()-2);return (a/b-1)*100;}
    private static double yoy(Series s){if(s.v.size()<13)return Double.NaN;double a=s.v.get(s.v.size()-1),b=s.v.get(s.v.size()-13);return (a/b-1)*100;}
    private static double annualizedQuarterly(Series s){double a=s.v.get(s.v.size()-1),b=s.v.get(s.v.size()-2);return (Math.pow(a/b,4)-1)*100;}
    private static double avgLast(Series s,int n){double x=0;for(int i=0;i<n;i++)x+=s.v.get(s.v.size()-1-i);return x/n;}
    private static String get(String u)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setRequestProperty("User-Agent","ChappyFxDemo/2 Android");int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("HTTP "+code);return body;}
    private static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}
}
