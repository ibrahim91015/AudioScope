/* Binder delivery adapted from CallVault BinderDelivery / RecorderServer.
 * Copyright (C) 2026 The CallVault Authors. GPL-3.0-or-later + LICENSE Section 7.
 * AudioScope: independent manual sources and timestamped PCM packet transport. */
package dev.audioscope;

import android.content.*;
import android.media.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public class ShellBridge extends ICaptureBridge.Stub {
    private final Map<String,PlaybackPolicy> policies=new HashMap<>();
    private final Map<String,Pump> pumps=new HashMap<>();
    private final int allowedUid;
    public ShellBridge(){allowedUid=resolveAppUid();}
    public ShellBridge(Context c){allowedUid=c.getApplicationInfo().uid;}
    private ShellBridge(int uid){allowedUid=uid;}
    private static int resolveAppUid(){try{Object b=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"package");Object pm=Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,b);for(Method m:pm.getClass().getMethods())if(m.getName().equals("getPackageUid")&&m.getParameterCount()==3)return (Integer)m.invoke(pm,"dev.audioscope",0L,0);throw new IllegalStateException("Package UID unavailable");}catch(Throwable e){throw new SecurityException("Cannot resolve authorized app UID",e);}}
    private void check(){int u=Binder.getCallingUid();if(allowedUid>=0&&u!=allowedUid&&u!=android.os.Process.myUid())throw new SecurityException("Untrusted bridge caller");}
    public synchronized String arm(String id,int rate,int channels,int uid){check();try{Source s=Source.get(id);if(!s.playback())return "Input sources open on Record";if(!policies.containsKey(id))policies.put(id,new PlaybackPolicy(s.usage,rate,channels,uid));return "ARMED • "+id;}catch(Throwable e){return "FAILED • "+root(e);}}
    @android.annotation.SuppressLint("MissingPermission") // Runs as shell/root; Android enforces privilege and all failures are reported.
    public synchronized ParcelFileDescriptor open(String id,int rate,int channels,int uid){
        check();if(pumps.containsKey(id)){if(pumps.get(id).active)throw new IllegalStateException("Source already recording");pumps.remove(id);}
        long identity=Binder.clearCallingIdentity();
        try{
            Source s=Source.get(id);AudioRecord record;
            if(s.playback()){String result=arm(id,rate,channels,uid);if(result.startsWith("FAILED"))throw new IllegalStateException(result);record=policies.get(id).sink();}
            else{int mask=channels==2?AudioFormat.CHANNEL_IN_STEREO:AudioFormat.CHANNEL_IN_MONO;int min=AudioRecord.getMinBufferSize(rate,mask,2);if(min<0)throw new IllegalArgumentException("Unsupported format");record=new AudioRecord(s.input,rate,mask,2,Math.max(min*4,rate*channels/5));}
            if(record==null||record.getState()!=AudioRecord.STATE_INITIALIZED){if(record!=null)record.release();throw new IllegalStateException("AudioRecord did not initialize");}
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();Pump p=new Pump(record,pipe[1]);pumps.put(id,p);p.start();return pipe[0];
        }catch(Throwable e){throw new IllegalStateException(root(e));}finally{Binder.restoreCallingIdentity(identity);}
    }
    public synchronized void close(String id){check();Pump p=pumps.remove(id);if(p!=null)p.stop();}
    public synchronized void disarm(){check();for(PlaybackPolicy p:policies.values())p.close();policies.clear();}
    public synchronized void shutdown(){check();for(Pump p:pumps.values())p.stop();pumps.clear();disarm();android.os.Process.killProcess(android.os.Process.myPid());}
    public void destroy(){shutdown();}
    public synchronized String inspect(){check();long identity=Binder.clearCallingIdentity();try{
        JSONObject j=new JSONObject();j.put("uid",android.os.Process.myUid());j.put("pid",android.os.Process.myPid());j.put("armed",new JSONArray(policies.keySet()));JSONObject running=new JSONObject();for(Map.Entry<String,Pump> e:pumps.entrySet())running.put(e.getKey(),e.getValue().describe());j.put("sources",running);
        JSONObject perms=new JSONObject();Class<?> sm=Class.forName("android.os.ServiceManager");Object binder=sm.getMethod("getService",String.class).invoke(null,"package");Object pm=Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        for(String p:new String[]{"RECORD_AUDIO","CAPTURE_AUDIO_OUTPUT","CAPTURE_MEDIA_OUTPUT","CAPTURE_VOICE_COMMUNICATION_OUTPUT","MODIFY_AUDIO_ROUTING"})try{perms.put(p,(Integer)pm.getClass().getMethod("checkUidPermission",String.class,int.class).invoke(pm,"android.permission."+p,android.os.Process.myUid())==0);}catch(Exception e){perms.put(p,"unknown: "+root(e));}j.put("permissions",perms);
        j.put("audio_dump",dumpAudio());return j.toString(2);
    }catch(Throwable e){return "Inspection failed: "+root(e);}finally{Binder.restoreCallingIdentity(identity);}}
    private String dumpAudio(){try{java.lang.Process p=new ProcessBuilder("dumpsys","audio").redirectErrorStream(true).start();ByteArrayOutputStream out=new ByteArrayOutputStream();Thread t=new Thread(()->{try{byte[] b=new byte[4096];InputStream in=p.getInputStream();int n;while((n=in.read(b))>0){if(out.size()+n>192000)break;out.write(b,0,n);}}catch(Exception ignored){}});t.start();if(!p.waitFor(3,TimeUnit.SECONDS))p.destroy();t.join(1000);return out.toString("UTF-8");}catch(Exception e){return e.toString();}}
    static String root(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();return e.getClass().getSimpleName()+": "+e.getMessage();}
    private static final class Packet {final byte[] data;final long time,frame;Packet(byte[] d,long t,long f){data=d;time=t;frame=f;}}
    private static final class Pump {
        final AudioRecord record;final ParcelFileDescriptor fd;final ArrayBlockingQueue<Packet> queue=new ArrayBlockingQueue<>(100);volatile boolean active=true;volatile long dropped,frames;volatile String error="";Thread reader,writer;
        Pump(AudioRecord r,ParcelFileDescriptor f){record=r;fd=f;}
        void start(){record.startRecording();if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Not recording");
            reader=new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);byte[] b=new byte[Math.max(4096,record.getSampleRate()*record.getChannelCount()*2/50)];try{while(active){int n=record.read(b,0,b.length);if(n<0)throw new IOException("AudioRecord.read="+n);if(n==0)continue;long first=frames;frames+=n/(record.getChannelCount()*2);AudioTimestamp ts=new AudioTimestamp();long time=System.nanoTime()-n*1000000000L/(record.getSampleRate()*record.getChannelCount()*2);if(record.getTimestamp(ts,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS)time=ts.nanoTime+(first-ts.framePosition)*1000000000L/record.getSampleRate();Packet p=new Packet(Arrays.copyOf(b,n),time,first);if(!queue.offer(p))dropped++;}}catch(Throwable e){error=root(e);}finally{active=false;}},"capture-reader");
            writer=new Thread(()->{try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(fd),32768))){out.writeInt(0x41534350);out.writeInt(record.getSampleRate());out.writeInt(record.getChannelCount());out.flush();while(active||!queue.isEmpty()){Packet p=queue.poll(100,TimeUnit.MILLISECONDS);if(p==null)continue;out.writeLong(p.time);out.writeLong(p.frame);out.writeInt(p.data.length);out.write(p.data);out.flush();}}catch(Throwable e){error=root(e);}finally{active=false;try{record.stop();}catch(Exception ignored){}record.release();}},"capture-pipe");reader.start();writer.start();
        }
        void stop(){active=false;try{record.stop();}catch(Exception ignored){}try{reader.join(1500);writer.join(2000);if(writer.isAlive())fd.close();}catch(Exception ignored){}}
        JSONObject describe()throws JSONException{JSONObject j=new JSONObject();j.put("frames",frames);j.put("queue_dropped",dropped);j.put("error",error);j.put("active",active);return j;}
    }
    public static void main(String[] args){try{
        if(args.length!=1)throw new IllegalArgumentException("Expected application UID");Looper.prepareMainLooper();ShellBridge bridge=new ShellBridge(Integer.parseInt(args[0]));
        deliver(bridge);new Handler().postDelayed(new Runnable(){public void run(){try{deliver(bridge);}catch(Throwable ignored){}new Handler().postDelayed(this,15000);}},15000);Looper.loop();
    }catch(Throwable e){android.util.Log.e("AudioScopeDaemon",root(e),e);}}
    private static void deliver(IBinder b)throws Exception{
        String authority="dev.audioscope.bridge";Object am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);Method get=null;for(Method m:am.getClass().getMethods())if(m.getName().equals("getContentProviderExternal")&&m.getParameterCount()==4)get=m;
        if(get==null)throw new NoSuchMethodException("getContentProviderExternal");Object holder=get.invoke(am,authority,0,null,authority);if(holder==null)throw new IllegalStateException("Provider unavailable");
        try{Object provider=holder.getClass().getField("provider").get(holder);Bundle extra=new Bundle();extra.putBinder("bridge",b);for(Method m:provider.getClass().getMethods())if(m.getName().equals("call")&&m.getParameterCount()==5&&m.getParameterTypes()[0].getName().equals("android.content.AttributionSource")){
            Class<?> cls=Class.forName("android.content.AttributionSource$Builder");Object builder=cls.getConstructor(int.class).newInstance(android.os.Process.myUid());cls.getMethod("setPackageName",String.class).invoke(builder,"com.android.shell");Object attribution=cls.getMethod("build").invoke(builder);m.invoke(provider,attribution,authority,"deliver",null,extra);return;
        }throw new NoSuchMethodException("IContentProvider.call");}finally{for(Method m:am.getClass().getMethods())if(m.getName().equals("removeContentProviderExternal")&&m.getParameterCount()==2)m.invoke(am,authority,null);}
    }
}
