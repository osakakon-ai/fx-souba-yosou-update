package jp.osakakon.traininglog;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
  final int BG=Color.rgb(11,15,20), CARD=Color.rgb(24,31,40), TEXT=Color.rgb(238,244,248), SUB=Color.rgb(160,174,188), ACC=Color.rgb(87,214,141);
  LinearLayout root, body; SharedPreferences sp; String selectedDate;
  ArrayList<Exercise> workout=new ArrayList<>(); int exIndex=0,setIndex=1; CountDownTimer timer;
  SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.JAPAN);

  static class Exercise {
    String name; double kg; int reps,sets,rest;
    Exercise(String n,double k,int r,int s,int t){name=n;kg=k;reps=r;sets=s;rest=t;}
    String enc(){return name.replace("|"," ")+"|"+kg+"|"+reps+"|"+sets+"|"+rest;}
    static Exercise dec(String x){try{String[]p=x.split("\\|");return new Exercise(p[0],Double.parseDouble(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]));}catch(Exception e){return null;}}
    String detail(){return name+"  "+(kg>0?trim(kg)+"kg × ":"")+reps+"回 × "+sets+"セット";}
    static String trim(double v){return v==(long)v?Long.toString((long)v):Double.toString(v);}
  }

  @Override public void onCreate(Bundle b){super.onCreate(b);sp=getSharedPreferences("training",MODE_PRIVATE);selectedDate=fmt.format(new Date());showMain();}
  @Override public void onDestroy(){if(timer!=null)timer.cancel();super.onDestroy();}

  void base(String title){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets s=i.getInsets(WindowInsets.Type.statusBars());v.setPadding(0,s.top,0,0);return i;});
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(16,8,10,6);
    top.addView(tv(title,22,TEXT),new LinearLayout.LayoutParams(0,-2,1));
    Button upd=btn("↻ 更新");upd.setOnClickListener(v->{
      Intent in=new Intent(Intent.ACTION_VIEW,Uri.parse("https://raw.githubusercontent.com/osakakon-ai/fx-souba-yosou-update/main/training/Training-App.apk"));
      startActivity(in);
    });top.addView(upd);root.addView(top);
    body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(14,4,14,28);
    ScrollView sv=new ScrollView(this);sv.addView(body);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  }
  TextView tv(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setPadding(6,7,6,7);return v;}
  Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setBackgroundTintList(ColorStateList.valueOf(CARD));return b;}
  LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(14,10,14,10);c.setBackgroundColor(CARD);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,6,0,6);c.setLayoutParams(p);return c;}
  EditText field(String hint,String val,boolean num){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(SUB);e.setTextColor(TEXT);e.setText(val);if(num)e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);return e;}

  void showMain(){
    base("筋トレログ");
    CalendarView cal=new CalendarView(this);cal.setFirstDayOfWeek(Calendar.MONDAY);cal.setDate(System.currentTimeMillis());
    cal.setOnDateChangeListener((v,y,m,d)->{selectedDate=String.format(Locale.JAPAN,"%04d-%02d-%02d",y,m+1,d);renderRecord();});body.addView(cal);
    LinearLayout a=new LinearLayout(this);
    Button menu=btn("メニュー登録"),start=btn("筋トレ開始");a.addView(menu,new LinearLayout.LayoutParams(0,-2,1));a.addView(start,new LinearLayout.LayoutParams(0,-2,1));body.addView(a);
    menu.setOnClickListener(v->menuDialog());start.setOnClickListener(v->chooseExercises());
    body.addView(tv("選択日の記録",18,TEXT));
    LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setTag("record");body.addView(box);renderRecord();
  }
  void renderRecord(){
    View v=body.findViewWithTag("record");if(!(v instanceof LinearLayout))return;LinearLayout box=(LinearLayout)v;box.removeAllViews();
    String rec=sp.getString("record_"+selectedDate,"");LinearLayout c=card();c.addView(tv(selectedDate,18,TEXT));
    if(rec.isEmpty())c.addView(tv("筋トレ記録なし",15,SUB));else for(String s:rec.split("\n"))c.addView(tv("✓ "+s,16,TEXT));box.addView(c);
    if(!rec.isEmpty()){Button share=btn("カレンダー＋今日の内容を共有");share.setOnClickListener(x->shareScreen());box.addView(share);}
  }

  ArrayList<Exercise> load(){ArrayList<Exercise>a=new ArrayList<>();String s=sp.getString("exercises","");
    if(s.isEmpty()){a.add(new Exercise("スクワット",20,10,3,60));a.add(new Exercise("腕立て伏せ",0,12,3,45));a.add(new Exercise("ダンベルカール",8,10,3,60));save(a);return a;}
    for(String line:s.split("\n")){Exercise e=Exercise.dec(line);if(e!=null)a.add(e);}return a;}
  void save(ArrayList<Exercise>a){StringBuilder s=new StringBuilder();for(Exercise e:a){if(s.length()>0)s.append("\n");s.append(e.enc());}sp.edit().putString("exercises",s.toString()).apply();}

  void menuDialog(){
    ArrayList<Exercise>a=load();String[] names=new String[a.size()+1];names[0]="＋ 新しい種目を登録";for(int i=0;i<a.size();i++)names[i+1]=a.get(i).detail();
    new AlertDialog.Builder(this).setTitle("筋トレメニュー").setItems(names,(d,w)->{if(w==0)addExercise();else editExercise(w-1);}).show();
  }
  void addExercise(){editForm(null,-1);}
  void editExercise(int idx){ArrayList<Exercise>a=load();if(idx>=0&&idx<a.size())editForm(a.get(idx),idx);}
  void editForm(Exercise old,int idx){
    LinearLayout f=new LinearLayout(this);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(22,4,22,0);
    EditText n=field("種目名",old==null?"":old.name,false),kg=field("重量 kg",old==null?"0":Exercise.trim(old.kg),true),r=field("回数",old==null?"10":""+old.reps,true),s=field("セット数",old==null?"3":""+old.sets,true),t=field("休憩 秒",old==null?"60":""+old.rest,true);
    f.addView(n);f.addView(kg);f.addView(r);f.addView(s);f.addView(t);
    AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(idx<0?"メニュー登録":"メニュー編集").setView(f).setPositiveButton("保存",(d,w)->{
      try{if(n.getText().toString().trim().isEmpty())return;ArrayList<Exercise>a=load();Exercise e=new Exercise(n.getText().toString().trim(),Double.parseDouble(kg.getText().toString()),Integer.parseInt(r.getText().toString()),Integer.parseInt(s.getText().toString()),Integer.parseInt(t.getText().toString()));if(idx<0)a.add(e);else a.set(idx,e);save(a);}catch(Exception ex){Toast.makeText(this,"入力値を確認してください",Toast.LENGTH_SHORT).show();}
    }).setNegativeButton("キャンセル",null);
    if(idx>=0)b.setNeutralButton("削除",(d,w)->{ArrayList<Exercise>a=load();a.remove(idx);save(a);});b.show();
  }

  void chooseExercises(){
    ArrayList<Exercise>a=load();String[] names=new String[a.size()];boolean[] ch=new boolean[a.size()];for(int i=0;i<a.size();i++)names[i]=a.get(i).detail();
    new AlertDialog.Builder(this).setTitle("今日の筋トレを選択").setMultiChoiceItems(names,ch,(d,i,c)->ch[i]=c).setPositiveButton("順番設定",(d,w)->{
      workout.clear();for(int i=0;i<a.size();i++)if(ch[i])workout.add(a.get(i));if(workout.isEmpty()){Toast.makeText(this,"1つ以上選択してください",Toast.LENGTH_SHORT).show();return;}showOrder();
    }).setNegativeButton("キャンセル",null).show();
  }
  void showOrder(){
    base("順番を変更");body.addView(tv("↑ ↓ で自由に並べ替えできます",15,SUB));
    for(int i=0;i<workout.size();i++){final int x=i;LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.addView(tv((i+1)+". "+workout.get(i).detail(),15,TEXT),new LinearLayout.LayoutParams(0,-2,1));Button u=btn("↑"),d=btn("↓");u.setOnClickListener(v->{if(x>0){Collections.swap(workout,x,x-1);showOrder();}});d.setOnClickListener(v->{if(x<workout.size()-1){Collections.swap(workout,x,x+1);showOrder();}});row.addView(u);row.addView(d);body.addView(row);}
    Button go=btn("筋トレ開始");go.setOnClickListener(v->{exIndex=0;setIndex=1;showWorkout();});body.addView(go);
  }
  void showWorkout(){
    Exercise e=workout.get(exIndex);base("筋トレ中");LinearLayout c=card();c.addView(tv(e.name,22,TEXT));c.addView(tv((e.kg>0?Exercise.trim(e.kg)+"kg　":"")+e.reps+"回",30,ACC));c.addView(tv(setIndex+" / "+e.sets+" セット",19,TEXT));body.addView(c);
    Button done=btn("このセット完了");done.setOnClickListener(v->completeSet());body.addView(done);body.addView(tv("今日のメニュー",18,TEXT));
    for(int i=0;i<workout.size();i++){int col=i==exIndex?ACC:TEXT;String m=i<exIndex?"✓ ":i==exIndex?"▶ ":"";body.addView(tv(m+(i+1)+". "+workout.get(i).detail(),15,col));}
  }
  void completeSet(){Exercise e=workout.get(exIndex);if(setIndex<e.sets)rest(e.rest,()->{setIndex++;showWorkout();});else if(exIndex<workout.size()-1)rest(e.rest,()->{exIndex++;setIndex=1;showWorkout();});else finish();}
  void rest(int seconds,Runnable next){
    base("インターバル");final int[] rem={Math.max(0,seconds)};TextView clock=tv(rem[0]+" 秒",48,ACC);clock.setGravity(Gravity.CENTER);body.addView(clock);
    EditText custom=field("秒数を自由入力",""+rem[0],true);body.addView(custom);LinearLayout a=new LinearLayout(this);Button minus=btn("-15秒"),apply=btn("適用"),plus=btn("+15秒"),skip=btn("スキップ");a.addView(minus);a.addView(apply);a.addView(plus);body.addView(a);body.addView(skip);
    Runnable launch=()->{if(timer!=null)timer.cancel();clock.setText(rem[0]+" 秒");timer=new CountDownTimer(rem[0]*1000L,1000){public void onTick(long m){clock.setText((int)Math.ceil(m/1000.0)+" 秒");}public void onFinish(){next.run();}};timer.start();};
    minus.setOnClickListener(v->{rem[0]=Math.max(0,rem[0]-15);custom.setText(""+rem[0]);launch.run();});plus.setOnClickListener(v->{rem[0]+=15;custom.setText(""+rem[0]);launch.run();});apply.setOnClickListener(v->{try{rem[0]=Math.max(0,Integer.parseInt(custom.getText().toString()));launch.run();}catch(Exception e){}});skip.setOnClickListener(v->{if(timer!=null)timer.cancel();next.run();});launch.run();
  }
  void finish(){String day=fmt.format(new Date());StringBuilder s=new StringBuilder();for(Exercise e:workout){if(s.length()>0)s.append("\n");s.append(e.detail());}sp.edit().putString("record_"+day,s.toString()).apply();selectedDate=day;showMain();Toast.makeText(this,"筋トレ完了",Toast.LENGTH_LONG).show();}
  void shareScreen(){
    try{
      Bitmap bm=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bm);root.draw(c);
      ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"training_"+selectedDate+".png");v.put(MediaStore.Images.Media.MIME_TYPE,"image/png");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TrainingLog");
      Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);OutputStream os=getContentResolver().openOutputStream(uri);bm.compress(Bitmap.CompressFormat.PNG,100,os);os.close();
      Intent send=new Intent(Intent.ACTION_SEND);send.setType("image/png");send.putExtra(Intent.EXTRA_STREAM,uri);send.putExtra(Intent.EXTRA_TEXT,"今日の筋トレ記録");send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Xなどに共有"));
    }catch(Exception e){Toast.makeText(this,"共有画像を作成できませんでした",Toast.LENGTH_LONG).show();}
  }
}
