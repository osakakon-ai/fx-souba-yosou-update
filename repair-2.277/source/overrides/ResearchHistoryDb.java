package com.konchan.chappyfx;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.util.*;

final class ResearchHistoryDb extends SQLiteOpenHelper {
    private static final String DB_NAME="fx_research_history.db";
    private static final int DB_VERSION=1;

    static final class Summary {
        int total,today,winsToday,ambiguous;
        double pipsToday;
    }

    ResearchHistoryDb(Context ctx){super(ctx,DB_NAME,null,DB_VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE trades ("+
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "method_key TEXT NOT NULL, tf TEXT NOT NULL, components TEXT NOT NULL, exit_mode TEXT NOT NULL,"+
                "direction INTEGER NOT NULL, entry_time INTEGER NOT NULL, exit_time INTEGER NOT NULL,"+
                "entry_price REAL NOT NULL, exit_price REAL NOT NULL, pnl_pips REAL NOT NULL, win INTEGER NOT NULL,"+
                "regime TEXT, reason TEXT, spread_jpy REAL NOT NULL, slippage_jpy REAL NOT NULL, ambiguous INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_trades_exit_time ON trades(exit_time)");
        db.execSQL("CREATE INDEX idx_trades_method ON trades(method_key)");
        db.execSQL("CREATE INDEX idx_trades_regime ON trades(regime)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}

    void insertTrade(String key,String tf,String components,String exitMode,int direction,long entryTime,long exitTime,
                     double entry,double exit,double pips,boolean win,String regime,String reason,
                     double spread,double slippage,boolean ambiguous){
        ContentValues v=new ContentValues();
        v.put("method_key",key);v.put("tf",tf);v.put("components",components);v.put("exit_mode",exitMode);
        v.put("direction",direction);v.put("entry_time",entryTime);v.put("exit_time",exitTime);
        v.put("entry_price",entry);v.put("exit_price",exit);v.put("pnl_pips",pips);v.put("win",win?1:0);
        v.put("regime",regime);v.put("reason",reason);v.put("spread_jpy",spread);v.put("slippage_jpy",slippage);v.put("ambiguous",ambiguous?1:0);
        getWritableDatabase().insert("trades",null,v);
    }

    Summary summary(){
        Summary s=new Summary();SQLiteDatabase db=getReadableDatabase();
        Calendar cal=Calendar.getInstance();cal.set(Calendar.HOUR_OF_DAY,0);cal.set(Calendar.MINUTE,0);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);
        long start=cal.getTimeInMillis();
        try(Cursor c=db.rawQuery("SELECT COUNT(*), COALESCE(SUM(ambiguous),0) FROM trades",null)){
            if(c.moveToFirst()){s.total=c.getInt(0);s.ambiguous=c.getInt(1);}
        }
        try(Cursor c=db.rawQuery("SELECT COUNT(*), COALESCE(SUM(win),0), COALESCE(SUM(pnl_pips),0) FROM trades WHERE exit_time>=?",new String[]{String.valueOf(start)})){
            if(c.moveToFirst()){s.today=c.getInt(0);s.winsToday=c.getInt(1);s.pipsToday=c.getDouble(2);}
        }
        return s;
    }
}
