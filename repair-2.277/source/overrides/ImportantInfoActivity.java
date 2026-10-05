package com.konchan.chappyfx;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class ImportantInfoActivity extends Activity {
    private final int BG=Color.rgb(11,17,24), PANEL=Color.rgb(20,29,39), PANEL2=Color.rgb(26,37,49), TEXT=Color.rgb(248,251,253), MUTED=Color.rgb(205,215,224), UP=Color.rgb(57,214,135), DOWN=Color.rgb(255,102,118), WARN=Color.rgb(241,199,95), ACCENT=Color.rgb(104,170,255);
    private DemoStore store; private SharedPreferences uiPrefs;
    private TextView updatedText,listText,analysisSectionTitle,employmentTitle,chappyPrediction,employmentSummary,employmentFactors,employmentLearning,calendarMonthTitle,calendarSelectionText;
    private Button refreshBtn,allBtn,jpBtn,usBtn,riskSortBtn,dateSortBtn,chappySortBtn,toggleControlsBtn,employmentRefreshBtn,employmentDefaultBtn,analysisSourceBtn;
    private LinearLayout filterSortPanel,eventListContainer,analysisCard;
    private GridLayout calendarGrid;
    private ScrollView mainScroll;
    private ExecutorService worker=Executors.newSingleThreadExecutor();
    private OfficialInfoClient.Analysis current; private EmploymentForecastClient.Result employment; private OfficialInfoClient.Item selectedEvent;
    private String filter="all",sortMode="risk";
    private final TimeZone jst=TimeZone.getTimeZone("Asia/Tokyo");
    private final Calendar calendarMonth=Calendar.getInstance(jst,Locale.JAPAN);
    private long selectedCalendarDay=0;
    private GestureDetector swipeDetector; private boolean switching=false;

    @Override protected void onCreate(Bundle b){super.onCreate(b);store=new DemoStore(this);uiPrefs=getSharedPreferences("important_info_ui",MODE_PRIVATE);sortMode=uiPrefs.getString("sortMode","risk");buildUi();setupSwipe();current=store.loadImportantAnalysis();employment=store.loadEmploymentForecast();render();renderEmployment();refresh(false);refreshEmployment(false);}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);mainScroll=sv;sv.setFillViewport(true);sv.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(14),dp(12),dp(28));
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(sv);applySystemBarInsets(sv,root,12,14,12,28);

        root.addView(buildPageNav(4),lp(-1,-2,0,0,0,6));
        root.addView(pageTitle("重要情報・イベント"),lp(-1,dp(38),0,0,0,8));

        calendarMonth.set(Calendar.DAY_OF_MONTH,1);calendarMonth.set(Calendar.HOUR_OF_DAY,0);calendarMonth.set(Calendar.MINUTE,0);calendarMonth.set(Calendar.SECOND,0);calendarMonth.set(Calendar.MILLISECOND,0);
        LinearLayout calendarCard=card();
        LinearLayout calendarHead=new LinearLayout(this);calendarHead.setOrientation(LinearLayout.HORIZONTAL);calendarHead.setGravity(Gravity.CENTER_VERTICAL);
        Button prevMonth=smallButton("‹");Button nextMonth=smallButton("›");
        calendarMonthTitle=txt("",17,TEXT,true);calendarMonthTitle.setGravity(Gravity.CENTER);
        calendarHead.addView(prevMonth,new LinearLayout.LayoutParams(dp(48),dp(38)));
        calendarHead.addView(calendarMonthTitle,new LinearLayout.LayoutParams(0,dp(38),1));
        calendarHead.addView(nextMonth,new LinearLayout.LayoutParams(dp(48),dp(38)));
        calendarCard.addView(calendarHead);
        calendarGrid=new GridLayout(this);calendarGrid.setColumnCount(7);calendarGrid.setRowCount(7);calendarCard.addView(calendarGrid,lp(-1,-2,0,8,0,0));
        calendarSelectionText=txt("日付をタップすると、その日のイベントだけを下に表示します。",11,MUTED,false);calendarSelectionText.setGravity(Gravity.CENTER);calendarCard.addView(calendarSelectionText,lp(-1,-2,0,7,0,0));
        prevMonth.setOnClickListener(v->{calendarMonth.add(Calendar.MONTH,-1);selectedCalendarDay=0;renderCalendar();render();});
        nextMonth.setOnClickListener(v->{calendarMonth.add(Calendar.MONTH,1);selectedCalendarDay=0;renderCalendar();render();});
        root.addView(calendarCard,lp(-1,-2,0,0,0,10));

        LinearLayout alert=card();alert.addView(txt("重要度の目安",16,TEXT,true));
        alert.addView(txt("最重要 85〜100%   高 65〜84%   中 45〜64%   低 0〜44%",13,MUTED,false),lp(-1,-2,0,7,0,0));
        updatedText=txt("公式情報 更新 --",12,MUTED,false);alert.addView(updatedText,lp(-1,-2,0,7,0,0));
        root.addView(alert,lp(-1,-2,0,0,0,10));

        LinearLayout actions=card();
        LinearLayout controlsHeader=new LinearLayout(this);controlsHeader.setOrientation(LinearLayout.HORIZONTAL);controlsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView controlsTitle=txt("表示・並び順",16,TEXT,true);controlsHeader.addView(controlsTitle,new LinearLayout.LayoutParams(0,-2,1));
        toggleControlsBtn=smallButton("開く ▼");controlsHeader.addView(toggleControlsBtn,new LinearLayout.LayoutParams(dp(86),dp(38)));
        actions.addView(controlsHeader);

        filterSortPanel=new LinearLayout(this);filterSortPanel.setOrientation(LinearLayout.VERTICAL);filterSortPanel.setVisibility(View.GONE);
        filterSortPanel.addView(txt("表示",13,MUTED,true),lp(-1,-2,0,8,0,0));
        LinearLayout filters=new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);
        allBtn=smallButton("すべて");jpBtn=smallButton("日本");usBtn=smallButton("米国");
        filters.addView(allBtn,new LinearLayout.LayoutParams(0,dp(42),1));filters.addView(space(5));filters.addView(jpBtn,new LinearLayout.LayoutParams(0,dp(42),1));filters.addView(space(5));filters.addView(usBtn,new LinearLayout.LayoutParams(0,dp(42),1));
        filterSortPanel.addView(filters,lp(-1,-2,0,5,0,0));
        filterSortPanel.addView(txt("並び順",13,MUTED,true),lp(-1,-2,0,9,0,0));
        LinearLayout sorts=new LinearLayout(this);sorts.setOrientation(LinearLayout.HORIZONTAL);
        riskSortBtn=smallButton("警戒順");dateSortBtn=smallButton("日付順");chappySortBtn=smallButton("総合優先順");
        sorts.addView(riskSortBtn,new LinearLayout.LayoutParams(0,dp(42),1));sorts.addView(space(5));sorts.addView(dateSortBtn,new LinearLayout.LayoutParams(0,dp(42),1));sorts.addView(space(5));sorts.addView(chappySortBtn,new LinearLayout.LayoutParams(0,dp(42),1));
        filterSortPanel.addView(sorts,lp(-1,-2,0,5,0,0));
        actions.addView(filterSortPanel);

        refreshBtn=button("公式情報を更新");actions.addView(refreshBtn,lp(-1,dp(46),0,8,0,0));
        root.addView(actions,lp(-1,-2,0,0,0,10));
        toggleControlsBtn.setOnClickListener(v->toggleFilterSortPanel());
        allBtn.setOnClickListener(v->{filter="all";render();});jpBtn.setOnClickListener(v->{filter="jp";render();});usBtn.setOnClickListener(v->{filter="us";render();});
        riskSortBtn.setOnClickListener(v->{setSortMode("risk");});dateSortBtn.setOnClickListener(v->{setSortMode("date");});chappySortBtn.setOnClickListener(v->{setSortMode("chappy");});
        refreshBtn.setOnClickListener(v->refresh(true));

        analysisCard=card();
        analysisSectionTitle=txt("米雇用統計 事前分析",16,TEXT,true);analysisCard.addView(analysisSectionTitle);
        employmentTitle=txt("分析データ待ち",18,WARN,true);analysisCard.addView(employmentTitle,lp(-1,-2,0,8,0,0));
        chappyPrediction=txt("",16,ACCENT,true);chappyPrediction.setLineSpacing(dp(3),1.06f);chappyPrediction.setVisibility(View.GONE);analysisCard.addView(chappyPrediction,lp(-1,-2,0,8,0,0));
        employmentSummary=txt("失業保険・JOLTS・雇用者数・失業率・平均時給をまとめて分析します。",14,TEXT,false);employmentSummary.setLineSpacing(dp(3),1.06f);analysisCard.addView(employmentSummary,lp(-1,-2,0,7,0,0));
        employmentFactors=txt("",13,MUTED,false);employmentFactors.setLineSpacing(dp(3),1.05f);analysisCard.addView(employmentFactors,lp(-1,-2,0,8,0,0));
        employmentLearning=txt("予想履歴を蓄積すると、実績との差から補正を学習します。",13,MUTED,false);employmentLearning.setLineSpacing(dp(3),1.06f);analysisCard.addView(employmentLearning,lp(-1,-2,0,8,0,0));

        LinearLayout analysisButtons=new LinearLayout(this);analysisButtons.setOrientation(LinearLayout.HORIZONTAL);
        employmentRefreshBtn=button("事前分析を更新");analysisButtons.addView(employmentRefreshBtn,new LinearLayout.LayoutParams(0,dp(46),1));
        analysisButtons.addView(space(6));
        employmentDefaultBtn=button("雇用統計に戻す");analysisButtons.addView(employmentDefaultBtn,new LinearLayout.LayoutParams(0,dp(46),1));
        analysisCard.addView(analysisButtons,lp(-1,-2,0,9,0,0));

        analysisSourceBtn=button("選択した公式情報を開く");analysisSourceBtn.setVisibility(View.GONE);analysisCard.addView(analysisSourceBtn,lp(-1,dp(46),0,7,0,0));
        employmentRefreshBtn.setOnClickListener(v->{if(selectedEvent==null)refreshEmployment(true);else refreshSelectedEvent(true);});
        employmentDefaultBtn.setOnClickListener(v->{selectedEvent=null;render();renderEmployment();scrollToAnalysis();});
        analysisSourceBtn.setOnClickListener(v->{if(selectedEvent!=null&&selectedEvent.url!=null&&!selectedEvent.url.trim().isEmpty())try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(selectedEvent.url)));}catch(Exception ignored){}});
        root.addView(analysisCard,lp(-1,-2,0,0,0,10));

        LinearLayout info=card();info.addView(txt("イベント一覧",16,TEXT,true));
        info.addView(txt("月間カレンダーの日付で絞り込みできます。各イベントをタップすると、上の事前分析エリアがその内容に切り替わります。",12,MUTED,false),lp(-1,-2,0,5,0,0));
        listText=txt("取得中…",14,MUTED,false);listText.setLineSpacing(dp(4),1.08f);info.addView(listText,lp(-1,-2,0,8,0,0));
        eventListContainer=new LinearLayout(this);eventListContainer.setOrientation(LinearLayout.VERTICAL);info.addView(eventListContainer,lp(-1,-2,0,4,0,0));
        root.addView(info,lp(-1,-2,0,0,0,10));

        LinearLayout sources=card();sources.addView(txt("監視する公式情報源",16,TEXT,true));
        sources.addView(txt("日本銀行 / 財務省 / 米連邦準備制度（FRB） / 米財務省\n重要度の高い金融政策・為替・物価・雇用・経済見通し関連を優先表示します。",13,MUTED,false),lp(-1,-2,0,7,0,0));
        Button calendars=button("米国の主要経済指標カレンダーを開く");sources.addView(calendars,lp(-1,dp(46),0,9,0,0));
        calendars.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.bls.gov/schedule/news_release/"))));
        root.addView(sources,lp(-1,-2,0,0,0,10));

        TextView note=txt("重要度・方向性は公式発表タイトルを使った自動分類です。方向を断定せず、実際の価格反応とテクニカル分析を併用します。\n課金ガードON：無料・登録不要の許可済み公式接続だけを使い、APIキー・認証付きURL・未承認ホストはコード側で停止します。",12,MUTED,false);root.addView(note);
    }

    private TextView pageTitle(String s){
        TextView t=txt(s,20,TEXT,true);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;
    }

    private LinearLayout buildPageNav(int active){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels={"相場","デモ","成績","検証","情報"};
        Class<?>[] pages={MainActivity.class,DemoTradeActivity.class,DemoTradeActivity.TradeHistoryActivity.class,DemoTradeActivity.ResearchLabActivity.class,ImportantInfoActivity.class};
        for(int i=0;i<labels.length;i++){
            final Class<?> target=pages[i];Button b=navButton(labels[i],i==active);
            if(i!=active)b.setOnClickListener(v->openPage(target));
            row.addView(b,new LinearLayout.LayoutParams(0,dp(34),1));
            if(i<labels.length-1)row.addView(space(2));
        }
        return row;
    }
    private Button navButton(String s,boolean active){
        Button b=new Button(this);b.setText(s);b.setTextSize(10);b.setTextColor(active?TEXT:MUTED);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(active?Color.rgb(28,45,62):PANEL2);g.setCornerRadius(dp(8));g.setStroke(dp(active?2:1),active?ACCENT:Color.rgb(43,58,73));b.setBackground(g);
        return b;
    }

    private void setSortMode(String mode){
        sortMode=mode;
        if(uiPrefs!=null)uiPrefs.edit().putString("sortMode",mode).apply();
        render();
    }

    private void toggleFilterSortPanel(){
        if(filterSortPanel==null)return;
        boolean open=filterSortPanel.getVisibility()==View.VISIBLE;
        filterSortPanel.setVisibility(open?View.GONE:View.VISIBLE);
        if(toggleControlsBtn!=null)toggleControlsBtn.setText(open?"開く ▼":"閉じる ▲");
    }

    private void refreshEmployment(boolean force){
        long now=System.currentTimeMillis(),last=store.employmentForecastCheckedAt();
        if(!force&&employment!=null&&last>0&&now-last<6L*3600_000L){renderEmployment();return;}
        employmentRefreshBtn.setEnabled(false);employmentRefreshBtn.setText("分析中…");
        worker.execute(()->{
            EmploymentForecastClient.Result r=new EmploymentForecastClient().fetch(store.employmentCalibrationK());
            if(r.fetchSucceeded)store.saveEmploymentForecast(r);
            EmploymentForecastClient.Result cached=store.loadEmploymentForecast();
            runOnUiThread(()->{
                if(cached!=null)employment=cached;else if(r.fetchSucceeded)employment=r;
                employmentRefreshBtn.setEnabled(true);
                if(selectedEvent==null){employmentRefreshBtn.setText("雇用統計の事前分析を更新");renderEmployment();}
                if(force)Toast.makeText(this,r.fetchSucceeded?"雇用統計の事前分析を更新しました":"事前分析データの取得に失敗しました",Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void renderEmployment(){
        if(selectedEvent!=null)return;
        analysisSectionTitle.setText("米雇用統計 事前分析");
        employmentDefaultBtn.setVisibility(View.GONE);analysisSourceBtn.setVisibility(View.GONE);
        chappyPrediction.setVisibility(View.GONE);
        employmentRefreshBtn.setText("雇用統計の事前分析を更新");
        if(employment==null){
            employmentTitle.setText("分析データ待ち");employmentTitle.setTextColor(WARN);
            employmentSummary.setText("保存済み分析がありません。事前分析を取得します。");employmentFactors.setText("");
            employmentLearning.setText("予想履歴を蓄積すると、実績との差から補正を学習します。");return;
        }
        String when=employment.nextReleaseMs>0?new SimpleDateFormat("M/d HH:mm",Locale.JAPAN).format(new Date(employment.nextReleaseMs)):"日時未取得";
        String ref=employment.referenceMonth;
        try{java.time.YearMonth ym=java.time.YearMonth.parse(ref);ref=ym.getYear()+"年"+ym.getMonthValue()+"月分";}catch(Exception ignored){}
        employmentTitle.setText("次回 "+when+"（日本時間） / "+ref);
        employmentTitle.setTextColor(employment.signalScore>=30?UP:employment.signalScore<=-30?DOWN:WARN);
        int employmentPredictionScore=employmentChappyPredictionScore(employment);
        chappyPrediction.setVisibility(View.VISIBLE);
        chappyPrediction.setTextColor(chappyPredictionColor(employmentPredictionScore));
        chappyPrediction.setText(chappyPredictionText(employmentPredictionScore));
        String center=Double.isNaN(employment.payrollCenterK)?"--":String.format(Locale.JAPAN,"%+.0f千人",employment.payrollCenterK);
        String range=(Double.isNaN(employment.payrollLowK)||Double.isNaN(employment.payrollHighK))?"--":String.format(Locale.JAPAN,"%+.0f〜%+.0f千人",employment.payrollLowK,employment.payrollHighK);
        String ur=Double.isNaN(employment.unemploymentRate)?"--":String.format(Locale.JAPAN,"%.1f%%",employment.unemploymentRate);
        employmentSummary.setText("【独自推定】非農業部門雇用者数 "+center+"\n推定レンジ "+range+" / 失業率 "+ur+"\n雇用の勢い："+employment.bias+" / 信頼度 "+employment.confidence+"%\n※市場コンセンサスではなく、取得できた先行データからの実験的推定です。");
        StringBuilder b=new StringBuilder("【判断材料】");
        for(int i=0;i<employment.factors.size();i++){b.append("\n・").append(employment.factors.get(i));if(i>=5)break;}
        employmentFactors.setText(b.toString());
        int n=store.employmentResolvedCount();double mae=store.employmentMaeK(),cal=store.employmentCalibrationK();
        String learn="【検証学習】解決済み "+n+"件";
        if(!Double.isNaN(mae))learn+=" / 平均誤差 "+String.format(Locale.JAPAN,"%.0f千人",mae);
        if(n>=3)learn+=" / 次回補正 "+String.format(Locale.JAPAN,"%+.0f千人",cal);
        else learn+=" / 3件以上で予想の偏り補正を開始";
        employmentLearning.setText(learn+"\n予想→実績を保存し、過大・過小予想の傾向を次回分析へ反映します。");
    }

    private void refresh(boolean force){
        long now=System.currentTimeMillis(),last=store.importantInfoCheckedAt();
        if(!force&&current!=null&&last>0&&now-last<15L*60_000L){render();return;}
        refreshBtn.setEnabled(false);refreshBtn.setText("確認中…");
        if(current==null)listText.setText("保存データがないため、公式情報を取得中…");
        else updatedText.setText("保存データを表示中 / 公式情報を確認中…");
        worker.execute(()->{
            OfficialInfoClient.Analysis a=new OfficialInfoClient().fetch();
            boolean changed=false;
            if(a.fetchSucceeded)changed=store.saveImportantAnalysis(a);
            OfficialInfoClient.Analysis cached=store.loadImportantAnalysis();
            final boolean changedFinal=changed;
            runOnUiThread(()->{
                if(cached!=null)current=cached;
                else if(a.fetchSucceeded)current=a;
                refreshBtn.setEnabled(true);refreshBtn.setText("公式情報を更新");
                render();
                if(force){
                    Toast.makeText(this,a.fetchSucceeded?(changedFinal?"新しい公式情報を反映しました":"公式情報に変更はありません"):"公式情報の取得に失敗しました",Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void render(){
        renderCalendar();
        if(current==null){
            updatedText.setText("保存データなし");
            if(listText!=null&&listText.getText().toString().isEmpty())listText.setText("公式情報を取得してください。");
            return;
        }
        long checked=store.importantInfoCheckedAt(),changed=store.importantInfoChangedAt();
        String s="最終確認 "+(checked==0?"--":time(checked));
        if(changed>0)s+=" / データ更新 "+time(changed);
        updatedText.setText(s);
        ArrayList<OfficialInfoClient.Item> rows=new ArrayList<>();
        for(OfficialInfoClient.Item x:current.items){
            if(selectedCalendarDay>0&&!sameJstDay(x.dateMs,selectedCalendarDay))continue;
            if("jp".equals(filter)&&!"日本".equals(x.country))continue;
            if("us".equals(filter)&&!"米国".equals(x.country))continue;
            rows.add(x);
        }
        sortRows(rows);
        eventListContainer.removeAllViews();int shown=0;
        for(OfficialInfoClient.Item x:rows){
            if(shown++>=12)break;
            TextView row=txt(riskMark(x.risk)+" "+riskLabel(x.risk)+" "+x.risk+"%  "+x.source+"\n"+
                    OfficialInfoClient.displayTitle(x)+"\n"+
                    OfficialInfoClient.displayDateJst(x)+"\n"+
                    "分類: "+x.category+"\n"+
                    "タップで事前分析 →",14,TEXT,false);
            row.setLineSpacing(dp(3),1.07f);row.setPadding(dp(10),dp(10),dp(10),dp(10));row.setBackground(isSelectedEvent(x)?makeSelectedEventBg():makeBg(PANEL2,dp(12)));
            row.setClickable(true);row.setFocusable(true);row.setOnClickListener(v->selectEvent(x));
            eventListContainer.addView(row,lp(-1,-2,0,5,0,5));
        }
        listText.setText(shown==0?(selectedCalendarDay>0?"選択した日に表示できるイベントはありません。":"この条件に該当する重要情報はありません。"):"");
        listText.setVisibility(shown==0?View.VISIBLE:View.GONE);
        allBtn.setAlpha("all".equals(filter)?1f:.55f);jpBtn.setAlpha("jp".equals(filter)?1f:.55f);usBtn.setAlpha("us".equals(filter)?1f:.55f);
        riskSortBtn.setAlpha("risk".equals(sortMode)?1f:.55f);dateSortBtn.setAlpha("date".equals(sortMode)?1f:.55f);chappySortBtn.setAlpha("chappy".equals(sortMode)?1f:.55f);
    }

    private void selectEvent(OfficialInfoClient.Item x){
        selectedEvent=x;
        render();
        renderSelectedEventLoading();
        refreshSelectedEvent(false);
        scrollToAnalysis();
    }

    private void renderSelectedEventLoading(){
        if(selectedEvent==null)return;
        analysisSectionTitle.setText("選択イベント 事前分析");
        employmentDefaultBtn.setVisibility(View.VISIBLE);
        analysisSourceBtn.setVisibility(selectedEvent.url!=null&&!selectedEvent.url.trim().isEmpty()?View.VISIBLE:View.GONE);
        employmentRefreshBtn.setText("このイベントを再分析");
        employmentTitle.setText(riskMark(selectedEvent.risk)+" "+riskLabel(selectedEvent.risk)+" "+selectedEvent.risk+"%  "+selectedEvent.source+"\n"+OfficialInfoClient.displayTitle(selectedEvent)+"\n"+OfficialInfoClient.displayDateJst(selectedEvent));
        employmentTitle.setTextColor(riskColor(selectedEvent.risk));
        chappyPrediction.setVisibility(View.VISIBLE);
        chappyPrediction.setTextColor(ACCENT);
        chappyPrediction.setText("チャッピー予想：ドル円の方向を分析中…");
        employmentSummary.setText("関連データと現在のドル円を分析中…");
        employmentFactors.setText("");
        employmentLearning.setText("");
    }

    private void refreshSelectedEvent(boolean manual){
        final OfficialInfoClient.Item x=selectedEvent;if(x==null)return;
        employmentRefreshBtn.setEnabled(false);employmentRefreshBtn.setText("分析中…");
        worker.execute(()->{
            MacroEventAnalysisClient.Result r=new MacroEventAnalysisClient().analyze(x.source,OfficialInfoClient.displayTitle(x),x.category,x.direction,store);
            CompositeSignalEngine.Result cs=compositeForEvent(x,r);
            FreeOfficialDataEngine.Result free=freeOfficialForEvent(x,r);
            runOnUiThread(()->{
                if(selectedEvent!=x)return;
                employmentRefreshBtn.setEnabled(true);employmentRefreshBtn.setText("このイベントを再分析");
                renderSelectedEventResult(x,r,cs,free);
                if(manual)Toast.makeText(this,"事前分析を更新しました",Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void renderSelectedEventResult(OfficialInfoClient.Item x,MacroEventAnalysisClient.Result r,CompositeSignalEngine.Result cs,FreeOfficialDataEngine.Result free){
        analysisSectionTitle.setText("選択イベント 事前分析");
        employmentDefaultBtn.setVisibility(View.VISIBLE);
        analysisSourceBtn.setVisibility(x.url!=null&&!x.url.trim().isEmpty()?View.VISIBLE:View.GONE);
        employmentTitle.setText(riskMark(x.risk)+" "+riskLabel(x.risk)+" "+x.risk+"%  "+x.source+"\n"+OfficialInfoClient.displayTitle(x)+"\n"+OfficialInfoClient.displayDateJst(x));
        employmentTitle.setTextColor(riskColor(x.risk));
        int predictionScore=chappyPredictionScore(cs,free);
        chappyPrediction.setVisibility(View.VISIBLE);
        chappyPrediction.setTextColor(chappyPredictionColor(predictionScore));
        chappyPrediction.setText(chappyPredictionText(predictionScore));
        employmentSummary.setText("【"+r.eventType+"】分析信頼度 "+r.confidence+"%\n"+r.headline+"\n"+r.outlook);
        String composite="";
        if(free!=null&&!free.components.isEmpty())composite+="【無料公式データ複合分析】\n"+free.summary()+"\n"+free.sourceNote+"\n\n";
        if(cs!=null&&cs.used>0)composite+="【補助複合データ】\n"+cs.summary()+"\n\n";
        employmentFactors.setText(composite+"【判断材料】\n"+r.dataSummary);
        String extra="";
        if(r.eventType!=null&&r.eventType.contains("雇用")){
            int n=store.employmentResolvedCount();double mae=store.employmentMaeK(),cal=store.employmentCalibrationK();
            extra="\n\n【予想検証】解決済み "+n+"件";
            if(!Double.isNaN(mae))extra+=" / 平均誤差 "+String.format(Locale.JAPAN,"%.0f千人",mae);
            if(n>=3)extra+=" / 次回補正 "+String.format(Locale.JAPAN,"%+.0f千人",cal);
        }
        employmentLearning.setText("【発表前に見るポイント】\n"+r.watchPoints+"\n\n【ドル円 上方向シナリオ】\n"+r.upScenario+"\n\n【ドル円 下方向シナリオ】\n"+r.downScenario+"\n\n"+r.sourceNote+extra);
    }

    private int chappyPredictionScore(CompositeSignalEngine.Result cs,FreeOfficialDataEngine.Result free){
        double total=0,weight=0;
        String side=store.tradeSide();
        if("up".equals(side)||"down".equals(side)){
            int readiness=store.tradeReadiness(),agreement=store.tradeAgreement();
            int strength=Math.max(20,Math.min(100,(readiness+agreement)/2));
            total+=("up".equals(side)?strength:-strength)*0.55;weight+=0.55;
        }
        if(free!=null&&!free.components.isEmpty()){total+=free.score*0.30;weight+=0.30;}
        if(cs!=null&&cs.used>0){total+=cs.score*0.15;weight+=0.15;}
        if(weight<=0)return 0;
        return (int)Math.max(-100,Math.min(100,Math.round(total/weight)));
    }

    private int employmentChappyPredictionScore(EmploymentForecastClient.Result e){
        if(e==null)return 0;
        double total=e.signalScore*0.65,weight=0.65;
        String side=store.tradeSide();
        if("up".equals(side)||"down".equals(side)){
            int readiness=store.tradeReadiness(),agreement=store.tradeAgreement();
            int strength=Math.max(20,Math.min(100,(readiness+agreement)/2));
            total+=("up".equals(side)?strength:-strength)*0.35;weight+=0.35;
        }
        return (int)Math.max(-100,Math.min(100,Math.round(total/Math.max(.01,weight))));
    }

    private String chappyPredictionText(int score){
        String move;
        if(score>=45)move="上昇寄り（ドル高・円安方向）";
        else if(score>=15)move="やや上昇寄り（ドル高・円安方向）";
        else if(score<=-45)move="下落寄り（ドル安・円高方向）";
        else if(score<=-15)move="やや下落寄り（ドル安・円高方向）";
        else move="方向感は弱い（中立）";
        return "チャッピー予想：ドル円は"+move+"\n予想度 "+Math.abs(score)+"% / 短期テクニカル＋関連データ";
    }

    private int chappyPredictionColor(int score){
        if(score>=15)return UP;
        if(score<=-15)return DOWN;
        return ACCENT;
    }

    private FreeOfficialDataEngine.Result freeOfficialForEvent(OfficialInfoClient.Item x,MacroEventAnalysisClient.Result r){
        try{
            String q=(OfficialInfoClient.displayTitle(x)+" "+x.category+" "+r.eventType).toLowerCase(Locale.US);
            FreeOfficialDataEngine e=new FreeOfficialDataEngine();
            boolean us="米国".equals(x.country);
            boolean employment=q.contains("雇用")||q.contains("employment")||q.contains("失業")||q.contains("jolts")||q.contains("labor");
            boolean inflation=q.contains("物価")||q.contains("inflation")||q.contains("cpi")||q.contains("pce");
            boolean growth=q.contains("gdp")||q.contains("景気")||q.contains("経済見通し")||q.contains("growth");
            if(us&&employment)return e.employment(store);
            if(us&&inflation)return e.inflation(store);
            if(us&&growth)return e.growth(store);
        }catch(Exception ignored){}
        return null;
    }

    private CompositeSignalEngine.Result compositeForEvent(OfficialInfoClient.Item x,MacroEventAnalysisClient.Result r){
        try{
            String q=(OfficialInfoClient.displayTitle(x)+" "+x.category+" "+r.eventType).toLowerCase(Locale.US);
            CompositeSignalEngine e=new CompositeSignalEngine();
            if(q.contains("雇用")||q.contains("employment")||q.contains("失業")||q.contains("jolts"))return e.employment();
            if(q.contains("物価")||q.contains("inflation")||q.contains("cpi")||q.contains("pce"))return e.inflation();
            if(q.contains("gdp")||q.contains("景気")||q.contains("経済見通し")||q.contains("growth"))return e.growth();
            if(q.contains("fomc")||q.contains("金融政策")||q.contains("金利")||q.contains("treasury"))return e.rates();
        }catch(Exception ignored){}
        return null;
    }

    private void renderCalendar(){
        if(calendarGrid==null||calendarMonthTitle==null)return;
        SimpleDateFormat mf=new SimpleDateFormat("yyyy年M月",Locale.JAPAN);mf.setTimeZone(jst);
        calendarMonthTitle.setText(mf.format(calendarMonth.getTime()));
        calendarGrid.removeAllViews();

        String[] weekdays={"日","月","火","水","木","金","土"};
        for(int col=0;col<7;col++){
            TextView w=txt(weekdays[col],13,TEXT,true);w.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams gp=new GridLayout.LayoutParams(GridLayout.spec(0),GridLayout.spec(col,1,1f));gp.width=0;gp.height=dp(28);gp.setMargins(dp(1),0,dp(1),dp(2));calendarGrid.addView(w,gp);
        }

        Calendar first=(Calendar)calendarMonth.clone();first.set(Calendar.DAY_OF_MONTH,1);
        int offset=first.get(Calendar.DAY_OF_WEEK)-Calendar.SUNDAY;
        int days=first.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar today=Calendar.getInstance(jst,Locale.JAPAN);

        for(int cell=0;cell<42;cell++){
            int day=cell-offset+1,row=1+cell/7,col=cell%7;
            TextView v=txt("",11,TEXT,false);v.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);v.setPadding(dp(2),dp(4),dp(2),dp(3));v.setLineSpacing(dp(1),1.02f);
            GridLayout.LayoutParams gp=new GridLayout.LayoutParams(GridLayout.spec(row),GridLayout.spec(col,1,1f));gp.width=0;gp.height=dp(76);gp.setMargins(dp(1),dp(1),dp(1),dp(1));
            if(day>=1&&day<=days){
                Calendar d=(Calendar)first.clone();d.set(Calendar.DAY_OF_MONTH,day);
                long dayMs=d.getTimeInMillis();
                ArrayList<OfficialInfoClient.Item> events=calendarEventsForDay(dayMs);
                events.sort((a,b)->{if(a.risk!=b.risk)return Integer.compare(b.risk,a.risk);return Long.compare(a.dateMs,b.dateMs);});
                SpannableStringBuilder label=new SpannableStringBuilder(String.valueOf(day));
                label.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),0,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                label.setSpan(new RelativeSizeSpan(1.12f),0,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int highest=0,show=Math.min(2,events.size());
                for(int i=0;i<show;i++){
                    OfficialInfoClient.Item x=events.get(i);highest=Math.max(highest,x.risk);
                    int start=label.length();label.append("\n● ");int dot=start+1;
                    label.setSpan(new ForegroundColorSpan(riskColor(x.risk)),dot,dot+1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    int nameStart=label.length();label.append(shortCalendarEventName(x));
                    label.setSpan(new ForegroundColorSpan(TEXT),nameStart,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    label.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),nameStart,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if(events.size()>2){int moreStart=label.length();label.append("\n+").append(String.valueOf(events.size()-2)).append("件");label.setSpan(new ForegroundColorSpan(MUTED),moreStart,label.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);}
                boolean isToday=today.get(Calendar.YEAR)==d.get(Calendar.YEAR)&&today.get(Calendar.MONTH)==d.get(Calendar.MONTH)&&today.get(Calendar.DAY_OF_MONTH)==day;
                boolean selected=selectedCalendarDay>0&&sameJstDay(dayMs,selectedCalendarDay);
                v.setText(label);v.setTextColor(events.isEmpty()?MUTED:TEXT);
                v.setBackground(calendarCellBg(isToday,selected,events.size()>0,highest));
                v.setClickable(true);v.setFocusable(true);
                v.setOnClickListener(xv->{
                    if(selectedCalendarDay>0&&sameJstDay(selectedCalendarDay,dayMs))selectedCalendarDay=0;else selectedCalendarDay=dayMs;
                    renderCalendar();render();
                    if(selectedCalendarDay>0)mainScroll.postDelayed(()->mainScroll.smoothScrollTo(0,Math.max(0,eventListContainer.getTop()-dp(170))),100);
                });
            }else{
                v.setBackground(makeBg(Color.rgb(14,21,29),dp(6)));
            }
            calendarGrid.addView(v,gp);
        }
        if(calendarSelectionText!=null){
            if(selectedCalendarDay>0){
                SimpleDateFormat df=new SimpleDateFormat("M/d（E）",Locale.JAPAN);df.setTimeZone(jst);
                calendarSelectionText.setText(df.format(new Date(selectedCalendarDay))+" のイベントを表示中。同じ日付を再タップで解除。");
                calendarSelectionText.setTextColor(ACCENT);
            }else{
                calendarSelectionText.setText("日付をタップすると、その日のイベントだけを下に表示します。");
                calendarSelectionText.setTextColor(MUTED);
            }
        }
    }

    private ArrayList<OfficialInfoClient.Item> calendarEventsForDay(long dayMs){
        ArrayList<OfficialInfoClient.Item> out=new ArrayList<>();if(current==null)return out;
        for(OfficialInfoClient.Item x:current.items)if(x!=null&&x.dateMs>0&&sameJstDay(x.dateMs,dayMs))out.add(x);
        return out;
    }

    private boolean sameJstDay(long a,long b){
        if(a<=0||b<=0)return false;
        Calendar x=Calendar.getInstance(jst,Locale.JAPAN),y=Calendar.getInstance(jst,Locale.JAPAN);x.setTimeInMillis(a);y.setTimeInMillis(b);
        return x.get(Calendar.YEAR)==y.get(Calendar.YEAR)&&x.get(Calendar.DAY_OF_YEAR)==y.get(Calendar.DAY_OF_YEAR);
    }

    private String shortCalendarEventName(OfficialInfoClient.Item x){
        String title=OfficialInfoClient.displayTitle(x);if(title==null)title="";String q=title.toLowerCase(Locale.US),src=x.source==null?"":x.source;
        if(q.contains("employment situation")||q.contains("雇用統計")||q.contains("非農業部門"))return "雇用統計";
        if(q.contains("consumer price")||q.contains("cpi")||q.contains("消費者物価"))return "米CPI";
        if(q.contains("fomc"))return "FOMC";
        if(q.contains("jolts")||q.contains("求人"))return "JOLTS";
        if(q.contains("gdp")||q.contains("国内総生産"))return "GDP";
        if(q.contains("pce")||q.contains("個人消費支出"))return "PCE";
        if("日銀".equals(src))return "日銀";
        if("財務省".equals(src))return q.contains("為替")||q.contains("外国為替")?"財務為替":"財務省";
        if("FRB".equals(src)||"FRB発言".equals(src))return "FRB";
        if("米財務省".equals(src))return "米財務";
        String cleaned=title.replaceAll("[\\s　]+","").replace("について","").replace("に関する","");
        if(cleaned.length()<=5)return cleaned;
        return cleaned.substring(0,5)+"…";
    }

    private android.graphics.drawable.Drawable calendarCellBg(boolean today,boolean selected,boolean hasEvent,int highestRisk){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
        g.setColor(hasEvent?Color.rgb(28,40,53):PANEL2);g.setCornerRadius(dp(6));
        int stroke=selected?ACCENT:today?WARN:hasEvent?riskColor(highestRisk):Color.rgb(43,58,73);
        g.setStroke(dp(selected||hasEvent?2:1),stroke);return g;
    }

    private boolean isSelectedEvent(OfficialInfoClient.Item x){
        if(selectedEvent==null||x==null)return false;
        return Objects.equals(selectedEvent.source,x.source)&&Objects.equals(OfficialInfoClient.displayTitle(selectedEvent),OfficialInfoClient.displayTitle(x))&&selectedEvent.dateMs==x.dateMs;
    }

    private android.graphics.drawable.Drawable makeSelectedEventBg(){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(PANEL2);g.setCornerRadius(dp(12));g.setStroke(dp(2),ACCENT);return g;
    }

    private int riskColor(int risk){return risk>=85?DOWN:risk>=65?Color.rgb(255,159,67):risk>=45?WARN:MUTED;}

    private void scrollToAnalysis(){
        if(mainScroll==null||analysisCard==null)return;
        mainScroll.postDelayed(()->mainScroll.smoothScrollTo(0,Math.max(0,analysisCard.getTop()-dp(12))),120);
    }

    private String riskLabel(int risk){
        if(risk>=85)return "最重要";
        if(risk>=65)return "高";
        if(risk>=45)return "中";
        return "低";
    }
    private String riskMark(int risk){
        if(risk>=85)return "🔴";
        if(risk>=65)return "🟠";
        if(risk>=45)return "🟡";
        return "⚪";
    }

    private void sortRows(ArrayList<OfficialInfoClient.Item> rows){
        if("date".equals(sortMode)){
            rows.sort((a,b)->{
                long ad=a.dateMs,bd=b.dateMs;
                if(ad==0&&bd==0)return Integer.compare(b.risk,a.risk);
                if(ad==0)return 1;if(bd==0)return -1;
                int d=Long.compare(bd,ad);
                return d!=0?d:Integer.compare(b.risk,a.risk);
            });
            return;
        }
        if("chappy".equals(sortMode)){
            rows.sort((a,b)->{
                int sa=chappyScore(a),sb=chappyScore(b);
                if(sa!=sb)return Integer.compare(sb,sa);
                long ad=a.dateMs,bd=b.dateMs;
                return Long.compare(bd,ad);
            });
            return;
        }
        rows.sort((a,b)->{
            if(a.risk!=b.risk)return Integer.compare(b.risk,a.risk);
            return Long.compare(b.dateMs,a.dateMs);
        });
    }

    private int chappyScore(OfficialInfoClient.Item x){
        int score=x.risk*10;
        String c=x.category==null?"":x.category;
        if(c.contains("金融政策・為替"))score+=120;
        else if(c.contains("要人発言・経済指標"))score+=80;
        else if(c.contains("金利・債券"))score+=45;
        if("日銀".equals(x.source)||"財務省".equals(x.source)||"FRB".equals(x.source)||"FRB発言".equals(x.source)||"米財務省".equals(x.source))score+=35;
        if(x.dateMs>0){
            long age=Math.max(0,System.currentTimeMillis()-x.dateMs);
            if(age<=6L*3600_000L)score+=100;
            else if(age<=24L*3600_000L)score+=70;
            else if(age<=3L*24*3600_000L)score+=40;
            else if(age<=7L*24*3600_000L)score+=20;
        }
        return score;
    }

    private void setupSwipe(){
        swipeDetector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onFling(MotionEvent e1,MotionEvent e2,float vx,float vy){
                if(e1==null||e2==null||switching)return false;float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();
                if(Math.abs(dx)<dp(80)||Math.abs(dx)<Math.abs(dy)*1.25f)return false;
                if(dx<0&&vx<-350){openPage(MainActivity.class);return true;}
                if(dx>0&&vx>350){openPage(DemoTradeActivity.ResearchLabActivity.class);return true;}
                return false;
            }
        });
    }
    @Override public boolean dispatchTouchEvent(MotionEvent ev){if(swipeDetector!=null)swipeDetector.onTouchEvent(ev);return super.dispatchTouchEvent(ev);}

    private void openPage(Class<?> cls){
        if(switching)return;
        switching=true;
        Intent i=new Intent(this,cls);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
        overridePendingTransition(android.R.anim.slide_in_left,android.R.anim.slide_out_right);
        new Handler(Looper.getMainLooper()).postDelayed(()->switching=false,450);
    }

    private String time(long t){return new SimpleDateFormat("M/d HH:mm:ss",Locale.JAPAN).format(new Date(t));}
    private void applySystemBarInsets(View src,View target,int l,int t,int r,int b){final int L=dp(l),T=dp(t),R=dp(r),B=dp(b);target.setPadding(L,T,R,B);src.setOnApplyWindowInsetsListener((v,i)->{int x1=0,y1=0,x2=0,y2=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets z=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());x1=z.left;y1=z.top;x2=z.right;y2=z.bottom;}else{x1=i.getSystemWindowInsetLeft();y1=i.getSystemWindowInsetTop();x2=i.getSystemWindowInsetRight();y2=i.getSystemWindowInsetBottom();}target.setPadding(L+x1,T+y1,R+x2,B+y2);return i;});src.requestApplyInsets();}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(12),dp(12),dp(12));c.setBackground(makeBg(PANEL,dp(16)));return c;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(13);b.setAllCaps(false);b.setBackground(makeBg(PANEL2,dp(12)));return b;}
    private Button smallButton(String s){Button b=button(s);b.setTextSize(12);return b;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private View space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(dp(d),1));return s;}
    private android.graphics.drawable.Drawable makeBg(int color,int radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(dp(1),Color.rgb(43,58,73));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
