package com.konchan.chappyfx;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class FreeOfficialDataEngine {
    public static final class Component {
        public String name="",detail="",source="";
        public int score=0;
        public double weight=1.0;
    }
    public static final class Result {
        public long checkedAt;
        public int score=0,strength=0,confidence=0;
        public String label="中立",headline="無料公式データの複合分析",sourceNote="無料・登録不要の公式情報のみ使用";
        public boolean fromCache=false;
        public final ArrayList<Component> components=new ArrayList<>();
        public String summary(){
            StringBuilder b=new StringBuilder();
            b.append("無料公式データ総合 ").append(strength).append("/100（").append(label).append("）");
            for(Component c:components)b.append("\n・").append(c.name).append("：").append(c.detail).append(" [").append(c.score>0?"+":"").append(c.score).append("]");
            return b.toString();
        }
    }

    private static final long CACHE_MS=12L*60L*60L*1000L;
    private static final Set<String> FREE_HOSTS=new HashSet<>(Arrays.asList(
            "api.bls.gov","www.bls.gov","www.census.gov","data.sec.gov","www.sec.gov"
    ));
    private static final String BLS="https://api.bls.gov/publicAPI/v1/timeseries/data/";
    private static final String CENSUS_BFS="https://www.census.gov/econ/bfs/current/index.html";
    private static final String SEC_UA="ChappyFXDemo/2 official-data research; contact via GitHub osakakon-ai";

    public Result employment(DemoStore store){
        long now=System.currentTimeMillis();
        if(store!=null&&store.freeOfficialCheckedAt("employment")>0&&now-store.freeOfficialCheckedAt("employment")<CACHE_MS){
            Result cached=parseCache(store.freeOfficialCache("employment"));
            if(cached!=null){cached.fromCache=true;return cached;}
        }
        Result r=new Result();r.checkedAt=now;
        fetchBlsEmployment(r);
        fetchCensusBusinessFormation(r);
        fetchSecLaborTone(r);
        finalizeResult(r);
        if(store!=null)store.saveFreeOfficialCache("employment",toJson(r),r.checkedAt);
        return r;
    }

    public Result inflation(DemoStore store){
        Result cached=loadCached(store,"inflation");if(cached!=null)return cached;
        Result r=new Result();r.checkedAt=System.currentTimeMillis();
        fetchBlsInflation(r);finalizeTheme(r,"物価");saveCached(store,"inflation",r);return r;
    }

    public Result growth(DemoStore store){
        Result cached=loadCached(store,"growth");if(cached!=null)return cached;
        Result r=new Result();r.checkedAt=System.currentTimeMillis();
        fetchBlsGrowth(r);fetchCensusBusinessFormation(r);finalizeTheme(r,"景気");saveCached(store,"growth",r);return r;
    }

    private Result loadCached(DemoStore store,String type){
        if(store==null)return null;long t=store.freeOfficialCheckedAt(type);if(t<=0||System.currentTimeMillis()-t>=CACHE_MS)return null;
        Result x=parseCache(store.freeOfficialCache(type));if(x!=null)x.fromCache=true;return x;
    }
    private void saveCached(DemoStore store,String type,Result r){if(store!=null)store.saveFreeOfficialCache(type,toJson(r),r.checkedAt);}

    private void fetchBlsEmployment(Result r){
        try{
            String[] ids={
                    "CES0000000001","LNS14000000","LNS11300000","CES0500000003",
                    "JTS00000000JOL","JTS00000000HIL","JTS00000000QUL","JTS00000000LDL"
            };
            JSONObject body=new JSONObject();JSONArray a=new JSONArray();for(String id:ids)a.put(id);body.put("seriesid",a);
            String json=post(BLS,body.toString());
            JSONObject root=new JSONObject(json);
            if(!"REQUEST_SUCCEEDED".equals(root.optString("status")))return;
            JSONArray series=root.optJSONObject("Results").optJSONArray("series");
            HashMap<String,ArrayList<Double>> map=new HashMap<>();
            for(int i=0;i<series.length();i++){
                JSONObject s=series.optJSONObject(i);if(s==null)continue;
                ArrayList<Double> vals=new ArrayList<>();JSONArray d=s.optJSONArray("data");
                if(d!=null)for(int j=0;j<d.length();j++){JSONObject x=d.optJSONObject(j);if(x==null)continue;String p=x.optString("period");if(!p.matches("M(0[1-9]|1[0-2])"))continue;try{vals.add(Double.parseDouble(x.optString("value")));}catch(Exception ignored){}}
                map.put(s.optString("seriesID"),vals);
            }
            addBlsPayroll(r,map.get("CES0000000001"));
            addBlsUnemployment(r,map.get("LNS14000000"));
            addBlsParticipation(r,map.get("LNS11300000"));
            addBlsWage(r,map.get("CES0500000003"));
            addBlsJolts(r,"求人（JOLTS）",map.get("JTS00000000JOL"),1.10,false);
            addBlsJolts(r,"採用（JOLTS）",map.get("JTS00000000HIL"),1.00,false);
            addBlsJolts(r,"自発的離職（JOLTS）",map.get("JTS00000000QUL"),.55,false);
            addBlsJolts(r,"解雇・離職（JOLTS）",map.get("JTS00000000LDL"),1.00,true);
        }catch(Exception ignored){}
    }

    private void addBlsPayroll(Result r,ArrayList<Double> v){
        if(v==null||v.size()<4)return;double d=v.get(0)-v.get(1),d2=v.get(1)-v.get(2),d3=v.get(2)-v.get(3),avg=(d+d2+d3)/3.0;
        add(r,"非農業部門雇用",String.format(Locale.JAPAN,"直近 %+.0f千人 / 3か月平均 %+.0f千人",d,avg),scoreLinear(avg,20,220),1.35,"BLS");
    }
    private void addBlsUnemployment(Result r,ArrayList<Double> v){
        if(v==null||v.size()<2)return;double a=v.get(0),d=a-v.get(1);add(r,"失業率",String.format(Locale.JAPAN,"%.1f%% / 前月差 %+.1fpt",a,d),(int)Math.round(clamp(-d*260,-100,100)),1.25,"BLS");
    }
    private void addBlsParticipation(Result r,ArrayList<Double> v){
        if(v==null||v.size()<2)return;double a=v.get(0),d=a-v.get(1);add(r,"労働参加率",String.format(Locale.JAPAN,"%.1f%% / 前月差 %+.1fpt",a,d),(int)Math.round(clamp(d*180,-100,100)),.70,"BLS");
    }
    private void addBlsWage(Result r,ArrayList<Double> v){
        if(v==null||v.size()<2)return;double pct=(v.get(0)/v.get(1)-1)*100;add(r,"平均時給",String.format(Locale.JAPAN,"前月比 %+.2f%%",pct),(int)Math.round(clamp((pct-.25)*180,-100,100)),.85,"BLS");
    }
    private void addBlsJolts(Result r,String name,ArrayList<Double> v,double w,boolean inverse){
        if(v==null||v.size()<2)return;double pct=(v.get(0)/Math.max(.01,v.get(1))-1)*100;int s=(int)Math.round(clamp(pct*13,-100,100));if(inverse)s=-s;
        add(r,name,String.format(Locale.JAPAN,"前月比 %+.1f%%",pct),s,w,"BLS");
    }

    private void fetchBlsInflation(Result r){
        try{
            String[] ids={"CUUR0000SA0","CUUR0000SA0L1E","CES0500000003"};
            HashMap<String,ArrayList<Double>> map=fetchBls(ids);
            addBlsIndexChange(r,"CPI総合",map.get("CUUR0000SA0"),1.20);
            addBlsIndexChange(r,"コアCPI",map.get("CUUR0000SA0L1E"),1.40);
            addBlsWage(r,map.get("CES0500000003"));
        }catch(Exception ignored){}
    }

    private void fetchBlsGrowth(Result r){
        try{
            String[] ids={"CES0000000001","LNS14000000","JTS00000000JOL","JTS00000000HIL"};
            HashMap<String,ArrayList<Double>> map=fetchBls(ids);
            addBlsPayroll(r,map.get("CES0000000001"));
            addBlsUnemployment(r,map.get("LNS14000000"));
            addBlsJolts(r,"求人（JOLTS）",map.get("JTS00000000JOL"),1.00,false);
            addBlsJolts(r,"採用（JOLTS）",map.get("JTS00000000HIL"),.90,false);
        }catch(Exception ignored){}
    }

    private HashMap<String,ArrayList<Double>> fetchBls(String[] ids)throws Exception{
        JSONObject body=new JSONObject();JSONArray a=new JSONArray();for(String id:ids)a.put(id);body.put("seriesid",a);
        String json=post(BLS,body.toString());JSONObject root=new JSONObject(json);
        HashMap<String,ArrayList<Double>> map=new HashMap<>();
        if(!"REQUEST_SUCCEEDED".equals(root.optString("status")))return map;
        JSONArray series=root.optJSONObject("Results").optJSONArray("series");
        for(int i=0;i<series.length();i++){
            JSONObject s=series.optJSONObject(i);if(s==null)continue;ArrayList<Double> vals=new ArrayList<>();
            JSONArray d=s.optJSONArray("data");if(d!=null)for(int j=0;j<d.length();j++){
                JSONObject x=d.optJSONObject(j);if(x==null)continue;String p=x.optString("period");
                if(!p.matches("M(0[1-9]|1[0-2])"))continue;
                try{vals.add(Double.parseDouble(x.optString("value")));}catch(Exception ignored){}
            }
            map.put(s.optString("seriesID"),vals);
        }
        return map;
    }

    private void addBlsIndexChange(Result r,String name,ArrayList<Double> v,double w){
        if(v==null||v.size()<13)return;
        double m=(v.get(0)/Math.max(.01,v.get(1))-1)*100;
        double y=(v.get(0)/Math.max(.01,v.get(12))-1)*100;
        int s=(int)Math.round(clamp((m-.20)*220,-100,100));
        add(r,name,String.format(Locale.JAPAN,"前月比 %+.2f%% / 前年比 %.2f%%",m,y),s,w,"BLS");
    }

    private void fetchCensusBusinessFormation(Result r){
        try{
            String h=get(CENSUS_BFS,"ChappyFXDemo/2 official-data research");
            Pattern p=Pattern.compile("Business Applications for ([^<]+?) were ([0-9,]+), an? (increase|decrease) of ([0-9.]+) percent",Pattern.CASE_INSENSITIVE);
            Matcher m=p.matcher(strip(h));
            if(m.find()){
                double pct=Double.parseDouble(m.group(4));if("decrease".equalsIgnoreCase(m.group(3)))pct=-pct;
                int score=(int)Math.round(clamp(pct*10,-100,100));
                add(r,"新規事業申請",m.group(1).trim()+" "+m.group(2)+"件 / 前月比 "+signed1(pct)+"%",score,.65,"Census BFS");
            }
            Pattern p2=Pattern.compile("Projected Business Formations \\(within 4 quarters\\) for ([^<]+?) were ([0-9,]+), an? (increase|decrease) of ([0-9.]+) percent",Pattern.CASE_INSENSITIVE);
            Matcher m2=p2.matcher(strip(h));
            if(m2.find()){
                double pct=Double.parseDouble(m2.group(4));if("decrease".equalsIgnoreCase(m2.group(3)))pct=-pct;
                int score=(int)Math.round(clamp(pct*12,-100,100));
                add(r,"雇用主企業の形成見通し",m2.group(1).trim()+" "+m2.group(2)+"件 / 前月比 "+signed1(pct)+"%",score,.75,"Census BFS");
            }
        }catch(Exception ignored){}
    }

    private void fetchSecLaborTone(Result r){
        String[][] companies={
                {"Walmart","0000104169"},{"Amazon","0001018724"},{"UPS","0001090727"},{"Home Depot","0000354950"}
        };
        int positive=0,negative=0,docs=0;
        StringBuilder detail=new StringBuilder();
        for(String[] co:companies){
            try{
                SecDoc d=latestSecDoc(co[1]);if(d==null)continue;
                String html=secGet(d.url).toLowerCase(Locale.US);docs++;
                int pos=countAny(html," hiring ","workforce growth","headcount growth","additional employees","increase our workforce");
                int neg=countAny(html,"layoff","workforce reduction","headcount reduction","reduce our workforce","elimination of positions","restructuring charges");
                positive+=Math.min(3,pos);negative+=Math.min(3,neg);
                if(detail.length()>0)detail.append(" / ");
                detail.append(co[0]).append(" +").append(Math.min(3,pos)).append(" -").append(Math.min(3,neg));
            }catch(Exception ignored){}
        }
        if(docs>0){
            int raw=positive-negative;int score=(int)Math.round(clamp(raw*18,-100,100));
            add(r,"SEC企業文書の雇用トーン","代表"+docs+"社: "+detail,score,.55,"SEC EDGAR");
        }
    }

    private static final class SecDoc{String url;}
    private SecDoc latestSecDoc(String cik)throws Exception{
        String json=secGet("https://data.sec.gov/submissions/CIK"+cik+".json");
        JSONObject recent=new JSONObject(json).getJSONObject("filings").getJSONObject("recent");
        JSONArray forms=recent.getJSONArray("form"),acc=recent.getJSONArray("accessionNumber"),docs=recent.getJSONArray("primaryDocument");
        for(int i=0;i<forms.length();i++){
            String form=forms.optString(i);
            if(!("10-Q".equals(form)||"10-K".equals(form)))continue;
            String accession=acc.optString(i).replace("-",""),doc=docs.optString(i);if(doc.isEmpty())continue;
            String bare=cik.replaceFirst("^0+","");
            SecDoc d=new SecDoc();d.url="https://www.sec.gov/Archives/edgar/data/"+bare+"/"+accession+"/"+doc;return d;
        }
        return null;
    }

    private void finalizeResult(Result r){
        double sum=0,w=0;for(Component c:r.components){sum+=c.score*c.weight;w+=c.weight;}
        if(w<=0){r.label="データ不足";r.confidence=20;r.sourceNote="無料公式データを取得できませんでした。既存分析のみ使用します。";return;}
        r.score=(int)Math.round(sum/w);r.strength=Math.min(100,Math.abs(r.score));
        r.label=r.score>=35?"労働市場強め":r.score<=-35?"労働市場弱め":"中立〜まちまち";
        int sources=0;boolean bls=false,census=false,sec=false;
        for(Component c:r.components){if("BLS".equals(c.source))bls=true;if(c.source.startsWith("Census"))census=true;if(c.source.startsWith("SEC"))sec=true;}
        if(bls)sources++;if(census)sources++;if(sec)sources++;
        r.confidence=Math.min(88,42+r.components.size()*4+sources*5);
        r.headline="登録不要の無料公式データを複合分析";
        r.sourceNote="使用: "+(bls?"BLS ":"")+(census?"Census ":"")+(sec?"SEC ":"")+"／有料API・課金サービス・APIキーは使用しません。";
    }

    private void finalizeTheme(Result r,String theme){
        double sum=0,w=0;for(Component c:r.components){sum+=c.score*c.weight;w+=c.weight;}
        if(w<=0){r.label="データ不足";r.confidence=20;r.headline=theme+"の無料公式データ不足";r.sourceNote="無料公式データを取得できませんでした。";return;}
        r.score=(int)Math.round(sum/w);r.strength=Math.min(100,Math.abs(r.score));
        r.label=r.score>=35?theme+"強め":r.score<=-35?theme+"弱め":"中立〜まちまち";
        boolean bls=false,census=false,sec=false;for(Component c:r.components){if("BLS".equals(c.source))bls=true;if(c.source.startsWith("Census"))census=true;if(c.source.startsWith("SEC"))sec=true;}
        int sources=(bls?1:0)+(census?1:0)+(sec?1:0);
        r.confidence=Math.min(88,40+r.components.size()*5+sources*5);
        r.headline="登録不要の無料公式データで"+theme+"を複合分析";
        r.sourceNote="使用: "+(bls?"BLS ":"")+(census?"Census ":"")+(sec?"SEC ":"")+"／有料API・APIキー・課金サービスは使用しません。";
    }

    private static void add(Result r,String n,String d,int s,double w,String src){Component c=new Component();c.name=n;c.detail=d;c.score=s;c.weight=w;c.source=src;r.components.add(c);}
    private static int scoreLinear(double v,double weak,double strong){if(v<=weak)return -100;if(v>=strong)return 100;return (int)Math.round(-100+200*(v-weak)/(strong-weak));}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static String signed1(double v){return String.format(Locale.JAPAN,"%+.1f",v);}
    private static int countAny(String s,String... words){int n=0;for(String w:words){int p=0;while((p=s.indexOf(w,p))>=0){n++;p+=w.length();if(n>=20)return n;}}return n;}
    private static String strip(String s){return s==null?"":s.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ");}

    private static HttpURLConnection open(String u,String ua)throws Exception{
        URL url=new URL(u);String host=url.getHost().toLowerCase(Locale.US);
        if(!"https".equalsIgnoreCase(url.getProtocol()))throw new SecurityException("非HTTPS接続を停止");
        if(!FREE_HOSTS.contains(host))throw new SecurityException("有料・未承認ホストをブロック: "+host);
        String q=url.getQuery()==null?"":url.getQuery().toLowerCase(Locale.US);
        if(q.contains("apikey=")||q.contains("api_key=")||q.contains("token=")||q.contains("access_token=")||q.contains("key="))throw new SecurityException("APIキー・認証付き接続を停止");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent",ua);c.setRequestProperty("Accept","application/json,text/html,text/plain,*/*");return c;
    }
    private static String get(String u,String ua)throws Exception{HttpURLConnection c=open(u,ua);int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String b=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("HTTP "+code);return b;}
    private static String post(String u,String body)throws Exception{
        HttpURLConnection c=open(u,"ChappyFXDemo/2 official-data research");c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String b=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("HTTP "+code);return b;
    }
    private static long lastSecRequestAt=0;
    private static synchronized String secGet(String u)throws Exception{
        long now=System.currentTimeMillis(),wait=400-(now-lastSecRequestAt);
        if(wait>0)Thread.sleep(wait);
        String out=get(u,SEC_UA);lastSecRequestAt=System.currentTimeMillis();return out;
    }

    private static String read(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString(StandardCharsets.UTF_8.name());}

    private static String toJson(Result r){
        try{
            JSONObject o=new JSONObject();o.put("checkedAt",r.checkedAt).put("score",r.score).put("strength",r.strength).put("confidence",r.confidence).put("label",r.label).put("headline",r.headline).put("sourceNote",r.sourceNote);
            JSONArray a=new JSONArray();for(Component c:r.components){JSONObject x=new JSONObject();x.put("name",c.name).put("detail",c.detail).put("source",c.source).put("score",c.score).put("weight",c.weight);a.put(x);}o.put("components",a);return o.toString();
        }catch(Exception e){return "";}
    }
    private static Result parseCache(String raw){
        if(raw==null||raw.isEmpty())return null;
        try{
            JSONObject o=new JSONObject(raw);Result r=new Result();r.checkedAt=o.optLong("checkedAt");r.score=o.optInt("score");r.strength=o.optInt("strength");r.confidence=o.optInt("confidence");r.label=o.optString("label","中立");r.headline=o.optString("headline","無料公式データの複合分析");r.sourceNote=o.optString("sourceNote","");
            JSONArray a=o.optJSONArray("components");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;Component c=new Component();c.name=x.optString("name");c.detail=x.optString("detail");c.source=x.optString("source");c.score=x.optInt("score");c.weight=x.optDouble("weight",1);r.components.add(c);}return r;
        }catch(Exception e){return null;}
    }
}
