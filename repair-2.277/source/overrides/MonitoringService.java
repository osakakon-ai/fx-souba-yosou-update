package com.konchan.chappyfx;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.microsoft.signalr.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitoringService extends Service {
    public static final String ACTION_START="com.konchan.chappyfx.START";
    public static final String ACTION_STOP="com.konchan.chappyfx.STOP";
    public static final String ACTION_RUN_NOW="com.konchan.chappyfx.RUN_NOW";
    private static final int ONGOING_ID=1001;
    private static final String CH_MONITOR="monitor", CH_EVENTS="events";
    private static final long FULL_SYNC_MS=5L*60_000L, LIGHT_ANALYSIS_MS=15_000L, MID_PERSIST_MS=5_000L, NOTIFICATION_REFRESH_MS=15_000L;
    private static final long RAPID_HOLD_MS=3L*60_000L;

    private final AtomicBoolean cycleRunning=new AtomicBoolean(false),causeRunning=new AtomicBoolean(false);
    private ScheduledExecutorService analysisScheduler;
    private ExecutorService causeExecutor;
    private DemoStore store;
    private PowerManager.WakeLock wakeLock;
    private BiquoteStream priceStream;
    private volatile boolean analysisScheduled=false,streamConnected=false;
    private volatile String streamStatus="接続準備中";
    private volatile long rapidUntil=0,lastShockPersistAt=0,lastShockNotifyAt=0,lastCauseRequestAt=0,lastLightAnalysisAt=0,lastMidPersistAt=0,lastNotificationAt=0;
    private final ArrayDeque<TickSample> tickSamples=new ArrayDeque<>();
    private final Object liveM5Lock=new Object();
    private MarketEngine.Candle liveM5;

    private static final class TickSample {
        final long t; final double mid;
        TickSample(long t,double mid){this.t=t;this.mid=mid;}
    }
    private static final class ShockMove {
        final double delta; final int sec; final double ratio;
        ShockMove(double delta,int sec,double ratio){this.delta=delta;this.sec=sec;this.ratio=ratio;}
    }

    @Override public void onCreate(){
        super.onCreate();
        store=new DemoStore(this);
        createChannels();
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ChappyFX:AnalysisWakeLock");
        wakeLock.setReferenceCounted(false);
        analysisScheduler=Executors.newSingleThreadScheduledExecutor();
        causeExecutor=Executors.newSingleThreadExecutor();
        priceStream=new BiquoteStream(new BiquoteStream.Listener(){
            @Override public void onTick(BiquoteStream.Tick tick){onLiveTick(tick);}
            @Override public void onState(boolean connected,String detail){
                streamConnected=connected;streamStatus=detail==null?"":detail;updateOngoing();
            }
        });
        rapidUntil=store.shockRapidUntil();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String a=intent==null?ACTION_START:intent.getAction();
        // Continuous monitoring is the default for this app. ACTION_STOP is intentionally treated as restart
        // because the UI exposes monitoring as always-on.
        if(ACTION_STOP.equals(a))a=ACTION_START;
        ensureForeground();store.setRunning(true);
        if(priceStream!=null)priceStream.start();
        startAnalysisSchedule();
        if(ACTION_RUN_NOW.equals(a)&&analysisScheduler!=null)analysisScheduler.execute(this::runCycle);
        return START_STICKY;
    }

    private synchronized void startAnalysisSchedule(){
        if(analysisScheduled||analysisScheduler==null||analysisScheduler.isShutdown())return;
        analysisScheduled=true;
        analysisScheduler.scheduleWithFixedDelay(this::runCycle,0,FULL_SYNC_MS,TimeUnit.MILLISECONDS);
    }

    private synchronized void onLiveTick(BiquoteStream.Tick t){
        if(t==null||Double.isNaN(t.mid)||t.mid<=0)return;
        long now=System.currentTimeMillis();
        updateLiveM5(t.mid,now);

        DemoStore.Action fast=store.fastTick(t.mid);
        if(fast.notable)notifyEvent(fast.message);

        if(now-lastMidPersistAt>=MID_PERSIST_MS){
            store.setLastMid(t.mid);lastMidPersistAt=now;
        }

        ShockMove shock=recordAndDetect(now,t.mid);
        if(shock!=null)triggerShock(shock,now);
        isRapidNow();

        if(now-lastLightAnalysisAt>=LIGHT_ANALYSIS_MS&&analysisScheduler!=null&&!analysisScheduler.isShutdown()){
            lastLightAnalysisAt=now;
            analysisScheduler.execute(this::runLightAnalysis);
        }

        if(fast.notable||now-lastNotificationAt>=NOTIFICATION_REFRESH_MS){
            lastNotificationAt=now;updateOngoing();
        }
    }

    private void updateLiveM5(double mid,long now){
        long bucket=(now/(5L*60_000L))*(5L*60_000L);
        synchronized(liveM5Lock){
            if(liveM5==null||liveM5.timeMs!=bucket){
                MarketEngine.Candle base=null;
                List<MarketEngine.Candle> cached=store.candleList("M5");
                if(!cached.isEmpty()){
                    MarketEngine.Candle last=cached.get(cached.size()-1);
                    if(last.timeMs==bucket)base=last;
                }
                liveM5=base==null
                    ?new MarketEngine.Candle(bucket,mid,mid,mid,mid,0,true)
                    :new MarketEngine.Candle(bucket,base.o,Math.max(base.h,mid),Math.min(base.l,mid),mid,0,true);
            }else{
                liveM5=new MarketEngine.Candle(bucket,liveM5.o,Math.max(liveM5.h,mid),Math.min(liveM5.l,mid),mid,0,true);
            }
        }
    }

    private Map<String,List<MarketEngine.Candle>> buildLiveRaw(){
        LinkedHashMap<String,List<MarketEngine.Candle>> raw=new LinkedHashMap<>();
        MarketEngine.Candle live;
        synchronized(liveM5Lock){live=liveM5;}
        for(String tf:DemoStore.DISPLAY_TF){
            ArrayList<MarketEngine.Candle> rows=new ArrayList<>(store.candleList(tf));
            if("M5".equals(tf)&&live!=null){
                if(!rows.isEmpty()&&rows.get(rows.size()-1).timeMs==live.timeMs)rows.set(rows.size()-1,live);
                else rows.add(live);
                while(rows.size()>500)rows.remove(0);
            }
            if(rows.isEmpty())return null;
            raw.put(tf,rows);
        }
        return raw;
    }

    private void runLightAnalysis(){
        if(cycleRunning.get())return;
        try{
            Map<String,List<MarketEngine.Candle>> raw=buildLiveRaw();
            if(raw==null)return;
            MarketEngine.Analysis analysis=MarketEngine.analyzeAll(raw);
            // 15秒経路は判定結果だけを更新する。ローソク足履歴・W/MN・検証ラボの保存は5分同期へ集約する。
            store.saveAnalysis(analysis);
            DemoStore.Action action=store.step(analysis,raw);
            if(action.notable)notifyEvent(action.message);
        }catch(Exception e){
            String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            store.saveError("軽量解析: "+msg);
        }
    }

    private ShockMove recordAndDetect(long now,double mid){
        synchronized(tickSamples){
            TickSample newest=tickSamples.peekLast();
            if(newest==null||now-newest.t>=900L)tickSamples.addLast(new TickSample(now,mid));
            while(!tickSamples.isEmpty()&&now-tickSamples.peekFirst().t>6L*60_000L)tickSamples.removeFirst();
            ShockMove best=null;
            int[] sec={30,120,300};double[] threshold={0.12,0.25,0.40};
            for(int i=0;i<sec.length;i++){
                TickSample old=sampleAtOrBefore(now-sec[i]*1000L);
                if(old==null)continue;
                double delta=mid-old.mid,ratio=Math.abs(delta)/threshold[i];
                if(ratio>=1.0&&(best==null||ratio>best.ratio))best=new ShockMove(delta,sec[i],ratio);
            }
            return best;
        }
    }

    private TickSample sampleAtOrBefore(long target){
        TickSample candidate=null;
        for(TickSample s:tickSamples){if(s.t<=target)candidate=s;else break;}
        return candidate;
    }

    private void detectStartupShock(List<MarketEngine.Candle> m5){
        synchronized(tickSamples){if(tickSamples.size()>=6)return;}
        if(m5==null||m5.isEmpty())return;
        MarketEngine.Candle r=m5.get(m5.size()-1);
        double delta=r.c-r.o;
        if(Math.abs(delta)>=0.25)triggerShock(new ShockMove(delta,300,Math.abs(delta)/0.25),System.currentTimeMillis());
    }

    private void triggerShock(ShockMove shock,long now){
        boolean wasRapid=isRapidNow();
        rapidUntil=Math.max(rapidUntil,now+RAPID_HOLD_MS);
        if(!wasRapid||now-lastShockPersistAt>=20_000L){
            store.saveShockState(true,shock.delta,shock.sec,now,rapidUntil);
            lastShockPersistAt=now;
        }
        if(!wasRapid||now-lastShockNotifyAt>=120_000L){
            notifyEvent("ドル円急変："+shock.sec+"秒で"+String.format(Locale.JAPAN,"%+.3f円",shock.delta)+"。急変モードへ切替");
            lastShockNotifyAt=now;
        }
        if(now-lastCauseRequestAt>=120_000L&&causeRunning.compareAndSet(false,true)){
            lastCauseRequestAt=now;
            final double move=shock.delta;final int sec=shock.sec;final long detected=now;
            causeExecutor.execute(()->{
                try{
                    OfficialInfoClient.ShockResult r=new OfficialInfoClient().investigateShock(move,sec,detected);
                    store.saveShockInvestigation(r);
                    updateOngoing();
                }catch(Exception ignored){}finally{causeRunning.set(false);}
            });
        }
    }

    private boolean isRapidNow(){
        long now=System.currentTimeMillis();
        if(rapidUntil<=0)rapidUntil=store.shockRapidUntil();
        boolean rapid=rapidUntil>now;
        if(!rapid&&store.shockRapid()){store.endShockMode();rapidUntil=0;}
        return rapid;
    }

    private void runCycle(){
        if(!store.isRunning()||!cycleRunning.compareAndSet(false,true))return;
        boolean locked=false;
        try{
            if(wakeLock!=null&&!wakeLock.isHeld()){wakeLock.acquire(90_000L);locked=true;}
            Map<String,List<MarketEngine.Candle>> raw=new BiquoteClient().fetchAll();
            List<MarketEngine.Candle> deepMn=store.candleList("MN");
            if(deepMn.size()>=48){
                List<MarketEngine.Candle> recentMn=raw.get("MN");
                TreeMap<String,MarketEngine.Candle> mm=new TreeMap<>();
                for(MarketEngine.Candle x:deepMn)mm.put(monthKey(x.timeMs),x);
                if(recentMn!=null)for(MarketEngine.Candle x:recentMn)mm.put(monthKey(x.timeMs),x);
                raw.put("MN",new ArrayList<>(mm.values()));
            }
            MarketEngine.Analysis analysis=MarketEngine.analyzeAll(raw);
            store.saveAnalysis(analysis);store.saveExtendedAnalysis(raw);
            for(Map.Entry<String,List<MarketEngine.Candle>> e:raw.entrySet()){
                if("MN".equals(e.getKey())&&store.candles("MN").length()>=48&&e.getValue()!=null&&e.getValue().size()<48)continue;
                store.saveCandles(e.getKey(),e.getValue());
            }

            // Research Lab advances only on the five-minute full-sync path.
            // Its own bar guards prevent duplicate evaluation of unchanged bars.
            ResearchLabEngine.step(this,raw);

            List<MarketEngine.Candle> m5=raw.get("M5");
            double mid=m5.get(m5.size()-1).c;
            store.setLastMid(mid);lastMidPersistAt=System.currentTimeMillis();
            synchronized(liveM5Lock){liveM5=m5.get(m5.size()-1);}
            detectStartupShock(m5);

            if(store.importantInfoCheckedAt()==0||System.currentTimeMillis()-store.importantInfoCheckedAt()>=15L*60_000L){
                OfficialInfoClient.Analysis info=new OfficialInfoClient().fetch();
                if(info.fetchSucceeded)store.saveImportantAnalysis(info);
            }

            DemoStore.Action action=store.step(analysis,raw);
            if(action.notable)notifyEvent(action.message);
            updateOngoing();
        }catch(Exception e){
            String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            store.saveError(msg);updateOngoing();
        }finally{
            if(locked&&wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}
            cycleRunning.set(false);
        }
    }

    private String monthKey(long t){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));c.setTimeInMillis(t);
        return String.format(Locale.ROOT,"%04d-%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1);
    }

    private void ensureForeground(){
        Notification n=ongoingNotification();
        if(Build.VERSION.SDK_INT>=34)startForeground(ONGOING_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(ONGOING_ID,n);
    }

    private void updateOngoing(){
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ONGOING_ID,ongoingNotification());}catch(Exception ignored){}
    }

    private Notification ongoingNotification(){
        Intent i=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int lc=store.longPositionCount(),sc=store.shortPositionCount();
        String pos=!store.hasPosition()?"待機":lc>0&&sc>0?("L"+lc+" / S"+sc):lc>0?("ロング"+lc):("ショート"+sc);
        double equity=store.balance()+store.unrealized(store.lastMid());
        String mode=streamConnected?"WebSocketリアルタイム / 軽量解析15秒 / 全足同期5分":"WebSocket再接続中 / 全足同期5分";
        String body=mode+" | "+pos+" | 資産 "+String.format(Locale.JAPAN,"¥%,.0f",equity);
        return new Notification.Builder(this,CH_MONITOR).setSmallIcon(com.konchan.chappyfx.R.drawable.ic_launcher).setContentTitle("チャッピーFXデモ監視中").setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body+" / "+streamStatus)).setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private void notifyEvent(String msg){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        Intent i=new Intent(this,DemoTradeActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CH_EVENTS).setSmallIcon(com.konchan.chappyfx.R.drawable.ic_launcher).setContentTitle("チャッピーFXデモ").setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg)).setContentIntent(pi).setAutoCancel(true).build();
        nm.notify((int)(System.currentTimeMillis()%100000)+2000,n);
    }

    private void createChannels(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel c=new NotificationChannel(CH_MONITOR,"FXデモ監視",NotificationManager.IMPORTANCE_LOW);c.setDescription("USD/JPYのWebSocket継続監視状態");nm.createNotificationChannel(c);
            NotificationChannel e=new NotificationChannel(CH_EVENTS,"FXデモ取引",NotificationManager.IMPORTANCE_DEFAULT);e.setDescription("デモエントリー・決済・急変通知");nm.createNotificationChannel(e);
        }
    }

    private void stopMonitoring(){
        store.setRunning(false);
        if(priceStream!=null)priceStream.stop();
        if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}
        if(analysisScheduler!=null)analysisScheduler.shutdownNow();
        if(causeExecutor!=null)causeExecutor.shutdownNow();
        analysisScheduled=false;
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }

    @Override public void onDestroy(){
        store.setRunning(false);
        if(priceStream!=null)priceStream.stop();
        if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}
        if(analysisScheduler!=null)analysisScheduler.shutdownNow();
        if(causeExecutor!=null)causeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}

final class BiquoteStream {
    private static final String HUB_URL="https://biquote.io/hubs/tick";
    interface Listener {
        void onTick(Tick tick);
        void onState(boolean connected,String detail);
    }
    static final class Tick {
        public String symbol="";
        public double bid=Double.NaN,ask=Double.NaN,mid=Double.NaN;
        public String timestamp="",marketState="";
        public long quoteAgeSeconds=-1;
        transient long receivedAt=0;
    }

    private static volatile Tick latest;
    private static volatile boolean liveConnected=false;

    static Tick latestTick(){return latest;}
    static boolean isConnected(){return liveConnected;}

    private final Listener listener;
    private final ScheduledExecutorService retryExecutor=Executors.newSingleThreadScheduledExecutor();
    private HubConnection connection;
    private ScheduledFuture<?> retryFuture;
    private boolean stopped=true,connecting=false;

    BiquoteStream(Listener listener){this.listener=listener;}

    synchronized void start(){
        if(!stopped)return;
        stopped=false;
        scheduleConnect(0);
    }

    private synchronized void scheduleConnect(long delayMs){
        if(stopped)return;
        if(retryFuture!=null&&!retryFuture.isDone())return;
        retryFuture=retryExecutor.schedule(this::connect,delayMs,TimeUnit.MILLISECONDS);
    }

    private void connect(){
        synchronized(this){
            retryFuture=null;
            if(stopped||connecting)return;
            connecting=true;
        }

        final HubConnection h=HubConnectionBuilder.create(HUB_URL).build();
        h.on("ReceiveTick",(Tick t)->{
            if(t==null||t.symbol==null||!"USDJPY".equalsIgnoreCase(t.symbol)||Double.isNaN(t.mid)||t.mid<=0)return;
            t.receivedAt=System.currentTimeMillis();
            latest=t;
            if(listener!=null)listener.onTick(t);
        },Tick.class);
        h.onClosed(error->{
            synchronized(BiquoteStream.this){
                if(connection==h)connection=null;
                liveConnected=false;
            }
            if(listener!=null)listener.onState(false,error==null?"接続終了":("切断: "+shortMessage(error)));
            scheduleConnect(15_000L);
        });

        try{
            h.start().blockingAwait();
            h.send("Subscribe",(Object)new String[]{"USDJPY"});
            synchronized(this){
                if(stopped){
                    try{h.stop().blockingAwait();}catch(Exception ignored){}
                    connecting=false;return;
                }
                connection=h;liveConnected=true;
            }
            if(listener!=null)listener.onState(true,"USDJPYストリーム接続");
        }catch(Exception e){
            try{h.stop().blockingAwait();}catch(Exception ignored){}
            synchronized(this){liveConnected=false;}
            if(listener!=null)listener.onState(false,"接続失敗: "+shortMessage(e));
            scheduleConnect(15_000L);
        }finally{
            synchronized(this){connecting=false;}
        }
    }

    synchronized void stop(){
        stopped=true;
        if(retryFuture!=null){retryFuture.cancel(true);retryFuture=null;}
        HubConnection h=connection;connection=null;liveConnected=false;
        if(h!=null)try{h.stop().blockingAwait();}catch(Exception ignored){}
        retryExecutor.shutdownNow();
    }

    private static String shortMessage(Throwable e){
        String s=e==null?null:e.getMessage();
        if(s==null||s.isEmpty())return e==null?"不明":e.getClass().getSimpleName();
        return s.length()>100?s.substring(0,100):s;
    }
}
