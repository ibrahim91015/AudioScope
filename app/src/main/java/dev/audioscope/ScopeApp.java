package dev.audioscope;

import android.app.Application;
import android.content.*;
import android.os.*;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class ScopeApp extends Application {
    public static ScopeApp app;
    public static volatile ICaptureBridge bridge;
    public static volatile String backend="Not connected";
    public static final ExecutorService IO=Executors.newCachedThreadPool();
    private static final ArrayDeque<String> logs=new ArrayDeque<>();
    public static final Handler MAIN=new Handler(Looper.getMainLooper());
    private Shizuku.UserServiceArgs args;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){attach(b,"Shizuku / Shevery");}
        public void onServiceDisconnected(ComponentName n){bridge=null;backend="Disconnected";log("WARN","Privileged helper disconnected");}
    };
    public void onCreate(){super.onCreate();app=this;log("INFO","AudioScope "+BuildConfig.VERSION_NAME+" • "+Build.MANUFACTURER+" "+Build.MODEL+" • Android "+Build.VERSION.RELEASE);Shizuku.addRequestPermissionResultListener((r,g)->{if(g==PackageManager.PERMISSION_GRANTED)bindShizuku();else log("WARN","Shizuku permission declined");});}
    public static void attach(IBinder b,String label){
        if(bridge!=null&&bridge.asBinder().equals(b))return;
        bridge=ICaptureBridge.Stub.asInterface(b);backend=label;try{b.linkToDeath(()->{bridge=null;backend="Disconnected";log("ERROR","Shell daemon died; recording pipes will close");},0);}catch(Exception e){log("ERROR",e.toString());}log("INFO","Connected via "+label);
    }
    public void connectShizuku(){try{if(!Shizuku.pingBinder()){log("WARN","Start Shizuku or Shevery first");return;}if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)Shizuku.requestPermission(12);else bindShizuku();}catch(Throwable e){log("ERROR","Shizuku: "+e);}}
    private void bindShizuku(){args=new Shizuku.UserServiceArgs(new ComponentName(this,ShellBridge.class)).daemon(true).processNameSuffix("capture").debuggable(false).version(1);Shizuku.bindUserService(args,connection);}
    public static File sessions(){File d=new File(app.getExternalFilesDir(null),"sessions");d.mkdirs();return d;}
    public static android.content.SharedPreferences prefs(){return app.getSharedPreferences("settings",0);}
    public static synchronized void log(String level,String message){
        String s=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+"  "+level+"  "+message;logs.add(s);while(logs.size()>1500)logs.remove();android.util.Log.i("AudioScope",s);
        if(app!=null){File file=new File(app.getFilesDir(),"events.log");if(file.length()>2*1024*1024){File old=new File(app.getFilesDir(),"events.previous.log");old.delete();file.renameTo(old);}try(FileWriter w=new FileWriter(file,true)){w.write(s+"\n");}catch(IOException ignored){}}
    }
    public static synchronized String logText(String filter){StringBuilder b=new StringBuilder();for(String s:logs)if(filter.isEmpty()||s.toLowerCase(Locale.US).contains(filter.toLowerCase(Locale.US)))b.append(s).append('\n');return b.toString();}
}
