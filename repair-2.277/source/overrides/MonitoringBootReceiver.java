package com.konchan.chappyfx;

import android.content.*;
import android.os.Build;

public class MonitoringBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action=intent==null?"":intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        Intent service=new Intent(context,MonitoringService.class);
        service.setAction(MonitoringService.ACTION_START);
        try{
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service);
            else context.startService(service);
        }catch(Exception ignored){}
    }
}
