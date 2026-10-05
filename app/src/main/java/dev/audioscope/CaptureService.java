package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class CaptureService extends Service {
    public static volatile CaptureService instance;
    public static final Map<String,Track> tracks=new ConcurrentHashMap<>();
    private static final List<Track> allTracks=new CopyOnWriteArrayList<>();
    public static volatile boolean paused,stopping;
    public static volatile long startedNs,pausedNs,pauseAt;
    public static volatile File session;
    public static volatile String sessionName="Ready to capture";
    public static volatile String exportStatus="";
    private MediaProjection projection;
    private PowerManager.WakeLock lock;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final JSONArray markers=new JSONArray();
    private int fallbackStage;
    public static boolean active(){return instance!=null&&session!=null&&!stopping;}
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();instance=this;NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("capture","Active recording",NotificationManager.IMPORTANCE_LOW));}
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction();
        if("STOP".equals(action)){stopSession();return START_NOT_STICKY;}
        if("PAUSE".equals(action)){togglePause();return START_NOT_STICKY;}
        if("MARK".equals(action)){mark("Notification bookmark");return START_NOT_STICKY;}
        if(session!=null)return START_NOT_STICKY;
        try{
            boolean project=intent.hasExtra("projection");int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|(project?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:0);startForeground(42,notification(),type);
            if(project){Intent data=intent.getParcelableExtra("projection");projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,data);projection.registerCallback(new MediaProjection.Callback(){public void onStop(){ScopeApp.log("WARN","Android revoked playback-capture consent");for(Track t:tracks.values())if(t.publicPlayback)t.stop();}},timer);}
            tracks.clear();allTracks.clear();markers.put(new JSONObject().put("event","session_start").put("atMs",0));paused=false;stopping=false;pausedNs=0;fallbackStage=0;startedNs=System.nanoTime();
            sessionName=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());session=new File(ScopeApp.sessions(),sessionName);if(!session.mkdirs())throw new IOException("Cannot create session directory");
            if(ScopeApp.prefs().getBoolean("wakelock",true)){lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AudioScope:capture");lock.acquire(12*60*60*1000L);}
            writeManifest(false);String[] selected=intent.getStringArrayExtra("sources");if(selected==null||selected.length==0)throw new IllegalArgumentException("Select a source");for(String s:selected)startTrack(s);
            ScopeApp.log("INFO","Session started: "+sessionName);timer.post(tick);
        }catch(Throwable e){ScopeApp.log("ERROR","Session startup: "+ShellBridge.root(e));stopSession();}
        return START_NOT_STICKY;
    }
    private final Runnable tick=new Runnable(){public void run(){if(!active())return;try{writeManifest(false);getSystemService(NotificationManager.class).notify(42,notification());fallback();long max=ScopeApp.prefs().getInt("maxMinutes",0);if(max>0&&elapsedMs()>max*60000L){ScopeApp.log("INFO","Session duration limit reached");stopSession();return;}if(ScopeApp.app.getExternalFilesDir(null).getUsableSpace()<100*1024*1024L){ScopeApp.log("ERROR","Storage below 100 MiB; stopping safely");stopSession();return;}}catch(Throwable e){ScopeApp.log("WARN","Checkpoint: "+e);}timer.postDelayed(this,1000);}};
    private void fallback(){if(!ScopeApp.prefs().getBoolean("fallback",false)||ScopeApp.bridge==null||paused)return;int seconds=ScopeApp.prefs().getInt("silenceSeconds",5);if(elapsedMs()<(seconds+fallbackStage*seconds)*1000L)return;
        Track call=tracks.get("voice_call");if(call==null||call.everAudible)return;
        if(fallbackStage==0){fallbackStage=1;ScopeApp.log("WARN","VOICE_CALL has no measured signal; trying uplink + downlink alongside it");startTrack("uplink");startTrack("downlink");}
        else if(fallbackStage==1){Track u=tracks.get("uplink"),d=tracks.get("downlink");if(u!=null&&u.everAudible&&d!=null&&d.everAudible)return;fallbackStage=2;ScopeApp.log("WARN","Trying communication playback + mic; existing tracks retained");startTrack("voice_playback");startTrack("mic");}
    }
    public synchronized void startTrack(String id){Track previous=tracks.get(id);if(previous!=null&&(previous.running||previous.done.getCount()!=0))return;Track t=new Track(Source.get(id),session);tracks.put(id,t);allTracks.add(t);ScopeApp.IO.execute(t::run);}
    public static long elapsedMs(){if(startedNs==0)return 0;return Math.max(0,(System.nanoTime()-startedNs-pausedNs-(paused?System.nanoTime()-pauseAt:0))/1000000);}
    public void togglePause(){if(!active())return;if(paused){pausedNs+=System.nanoTime()-pauseAt;paused=false;}else{pauseAt=System.nanoTime();paused=true;}ScopeApp.log("INFO",paused?"Session paused; capture drained, audio discarded":"Session resumed");getSystemService(NotificationManager.class).notify(42,notification());}
    public synchronized void mark(String title){try{markers.put(new JSONObject().put("atMs",elapsedMs()).put("label",title));ScopeApp.log("INFO","Bookmark: "+title);}catch(Exception ignored){}}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,"capture").setSmallIcon(R.drawable.ic_scope).setContentTitle(paused?"AudioScope • paused":"AudioScope • recording").setContentText(tracks.values().stream().filter(t->t.running).count()+" sources • "+formatTime(elapsedMs())).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        b.addAction(new Notification.Action.Builder(null,paused?"Resume":"Pause",action("PAUSE",1)).build());b.addAction(new Notification.Action.Builder(null,"Bookmark",action("MARK",2)).build());b.addAction(new Notification.Action.Builder(null,"Stop",action("STOP",3)).build());return b.build();
    }
    private PendingIntent action(String a,int id){return PendingIntent.getService(this,id,new Intent(this,CaptureService.class).setAction(a),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    public static String formatTime(long ms){long s=ms/1000;return String.format(Locale.US,"%02d:%02d:%02d",s/3600,s/60%60,s%60);}
    public void stopSession(){if(stopping)return;stopping=true;timer.removeCallbacksAndMessages(null);ScopeApp.IO.execute(()->{
        File finished=session;for(Track t:allTracks)t.stop();for(Track t:allTracks)try{t.done.await(6,TimeUnit.SECONDS);}catch(Exception ignored){}try{writeManifest(true);if(finished!=null)try(FileWriter w=new FileWriter(new File(finished,"events.log"))){w.write(ScopeApp.logText(""));}}catch(Throwable e){ScopeApp.log("ERROR","Finalize: "+e);}
        if(projection!=null){projection.stop();projection=null;}if(lock!=null&&lock.isHeld())lock.release();session=null;startedNs=0;ScopeApp.log("INFO","Recording stopped; files finalized");timer.post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
        if(finished!=null){exportStatus="Exporting "+finished.getName();try{Exports.finish(finished);exportStatus="Exports ready • "+finished.getName();}catch(Throwable e){exportStatus="Export failed: "+ShellBridge.root(e);ScopeApp.log("ERROR",exportStatus);}}
    });}
    private synchronized void writeManifest(boolean complete)throws Exception{
        if(session==null)return;JSONObject j=new JSONObject();j.put("app","AudioScope");j.put("version",BuildConfig.VERSION_NAME);j.put("device",Build.MANUFACTURER+" "+Build.MODEL);j.put("android",Build.VERSION.RELEASE);j.put("backend",ScopeApp.backend);j.put("session",sessionName);j.put("completed",complete);j.put("elapsedMs",elapsedMs());j.put("clock","CLOCK_MONOTONIC; PCM16 little-endian; timestamps adjusted for global pauses");j.put("markers",markers);JSONArray ts=new JSONArray();for(Track t:allTracks)ts.put(t.json());j.put("tracks",ts);j.put("routeLeft",ScopeApp.prefs().getString("routeLeft","mic"));j.put("routeRight",ScopeApp.prefs().getString("routeRight","voice_playback"));j.put("stereo",ScopeApp.prefs().getBoolean("stereo",false));j.put("mix",ScopeApp.prefs().getBoolean("mix",false));j.put("mka",ScopeApp.prefs().getBoolean("mka",false));j.put("codec",ScopeApp.prefs().getString("codec","WAV"));File tmp=new File(session,"session.json.tmp");try(FileWriter w=new FileWriter(tmp)){w.write(j.toString(2));}if(!tmp.renameTo(new File(session,"session.json")))throw new IOException("Manifest replace failed");
    }
    public void onDestroy(){instance=null;super.onDestroy();}
    public static final class Chunk {final byte[] data;final long time,frame;Chunk(byte[]d,long t,long f){data=d;time=t;frame=f;}}
    public static final class Track {
        public final Source source;public volatile String state="STARTING",error="",device="Unknown";public volatile boolean running=true,everAudible,publicPlayback;public volatile double rms,peak,db=-120,nonzero;public volatile long frames,dropped,clipped,silentMs,firstNs,lastNs;public volatile int rate,channels;public final float[] history=new float[160];public volatile int histPos;public volatile double gain;public volatile boolean muted;
        private final File folder;private final ArrayBlockingQueue<Chunk> queue=new ArrayBlockingQueue<>(150);public final CountDownLatch done=new CountDownLatch(1);private AudioRecord local;private ParcelFileDescriptor pipe;private volatile boolean reading=true;private WavFile wav;private Thread consumer;private long lastLive=System.nanoTime();
        Track(Source s,File f){source=s;folder=f;rate=ScopeApp.prefs().getInt("rate",48000);channels=ScopeApp.prefs().getInt("channels",1);gain=ScopeApp.prefs().getFloat("gain_"+s.id,1);muted=ScopeApp.prefs().getBoolean("mute_"+s.id,false);}
        void run(){
            try{
                ICaptureBridge bridge=ScopeApp.bridge;
                if(bridge!=null){pipe=bridge.open(source.id,rate,channels,ScopeApp.prefs().getInt("uidFilter",-1));try(DataInputStream in=new DataInputStream(new BufferedInputStream(new ParcelFileDescriptor.AutoCloseInputStream(pipe),32768))){if(in.readInt()!=0x41534350)throw new IOException("Bad capture stream");rate=in.readInt();channels=in.readInt();beginConsumer();while(running){long time=in.readLong(),frame=in.readLong();int n=in.readInt();if(n<=0||n>1048576||n%(channels*2)!=0)throw new IOException("Invalid PCM packet length");byte[] b=new byte[n];in.readFully(b);accept(new Chunk(b,time,frame));}}}
                else{
                    int mask=channels==2?AudioFormat.CHANNEL_IN_STEREO:AudioFormat.CHANNEL_IN_MONO,min=AudioRecord.getMinBufferSize(rate,mask,2);if(min<0)throw new IOException("Unsupported sample format");AudioRecord.Builder b=new AudioRecord.Builder().setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build()).setBufferSizeInBytes(Math.max(min*4,rate*channels/5));
                    if(source.playback()){
                        if(source.usage!=0&&source.usage!=1&&source.usage!=14)throw new SecurityException("This source needs the shell helper. Connect in Settings.");
                        if(instance.projection==null)throw new SecurityException("Playback-capture consent required");publicPlayback=true;AudioPlaybackCaptureConfiguration.Builder config=new AudioPlaybackCaptureConfiguration.Builder(instance.projection).addMatchingUsage(source.usage);int uid=ScopeApp.prefs().getInt("uidFilter",-1);if(uid>=0)config.addMatchingUid(uid);b.setAudioPlaybackCaptureConfig(config.build());
                    }else{if(source.privileged())throw new SecurityException("Telephony and submix need the shell helper");b.setAudioSource(source.input);}
                    if(ScopeApp.app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission was revoked");local=b.build();if(local.getState()!=1)throw new IOException("AudioRecord initialization failed");local.startRecording();if(local.getRecordingState()!=3)throw new IOException("AudioRecord not recording");rate=local.getSampleRate();channels=local.getChannelCount();beginConsumer();long frame=0;byte[] bytes=new byte[rate*channels*2/50];
                    while(running){int n=local.read(bytes,0,bytes.length);if(n<0)throw new IOException("Read error "+n);if(n==0)continue;AudioDeviceInfo route=local.getRoutedDevice();if(route!=null)device=route.getProductName()+" • type "+route.getType();AudioTimestamp ts=new AudioTimestamp();long time=System.nanoTime()-n*1000000000L/(rate*channels*2);if(local.getTimestamp(ts,AudioTimestamp.TIMEBASE_MONOTONIC)==0)time=ts.nanoTime+(frame-ts.framePosition)*1000000000L/rate;accept(new Chunk(Arrays.copyOf(bytes,n),time,frame));frame+=n/(channels*2);}
                }
            }catch(Throwable e){if(running){error=ShellBridge.root(e);state=e instanceof SecurityException?"BLOCKED":"FAILED";ScopeApp.log("ERROR",source.id+" • "+error);}}
            finally{running=false;reading=false;if(local!=null){try{local.stop();}catch(Exception ignored){}local.release();}if(pipe!=null)try{pipe.close();}catch(Exception ignored){}if(consumer!=null)try{consumer.join(5000);}catch(Exception ignored){}if(error.isEmpty())state="STOPPED";ScopeApp.log("INFO",source.id+" stopped • "+frames+" frames • "+dropped+" dropped chunks");done.countDown();}
        }
        private void beginConsumer()throws IOException{
            File path=new File(folder,source.id+".wav");if(path.exists()){int suffix=2;while(new File(folder,source.id+"_"+suffix+".wav").exists())suffix++;path=new File(folder,source.id+"_"+suffix+".wav");}wavName=path.getName();wav=new WavFile(path,rate,channels);state="WAITING FOR SIGNAL";ScopeApp.log("INFO",source.id+" started • "+rate+" Hz • "+channels+" ch");consumer=new Thread(this::consume,"write-"+source.id);consumer.start();
        }
        private String wavName="";
        private void accept(Chunk c){if(!queue.offer(c))dropped++;}
        private void consume(){long expected=-1,checkpoint=System.nanoTime();byte[] zeros=new byte[8192];try(FileWriter stamps=new FileWriter(new File(folder,wavName+".timestamps.jsonl"));OutputStream raw=ScopeApp.prefs().getBoolean("raw",false)?new FileOutputStream(new File(folder,wavName.replace(".wav",".pcm"))):OutputStream.nullOutputStream()){
            while(reading||!queue.isEmpty()){
                Chunk c=queue.poll(100,TimeUnit.MILLISECONDS);if(c==null)continue;int count=c.data.length/(channels*2);if(paused){expected=c.frame+count;continue;}
                PcmStats stats=new PcmStats(c.data,c.data.length);rms=stats.rms;peak=stats.peak;db=stats.db;nonzero=stats.nonzero;clipped+=stats.clipped;double threshold=ScopeApp.prefs().getInt("silenceDb",-60);long now=System.nanoTime();if(db>threshold){everAudible=true;lastLive=now;silentMs=0;state="LIVE";}else{silentMs=(now-lastLive)/1000000;state=silentMs>=ScopeApp.prefs().getInt("silenceSeconds",5)*1000L?"SILENT":"LOW SIGNAL";}history[histPos++%history.length]=(float)stats.peak;
                if(expected>=0&&c.frame>expected){long gap=(c.frame-expected)*channels*2;while(gap>0){int n=(int)Math.min(gap,zeros.length);wav.write(zeros,n);raw.write(zeros,0,n);frames+=n/(channels*2);gap-=n;}dropped++;}expected=c.frame+count;
                long adjusted=c.time-pausedNs;if(firstNs==0)firstNs=adjusted;lastNs=adjusted+count*1000000000L/rate;stamps.write("{\"timeNs\":"+adjusted+",\"frame\":"+frames+",\"sourceFrame\":"+c.frame+",\"count\":"+count+"}\n");wav.write(c.data,c.data.length);raw.write(c.data);frames+=count;
                if(now-checkpoint>5_000_000_000L){wav.checkpoint();stamps.flush();checkpoint=now;}
            }
        }catch(Throwable e){error=ShellBridge.root(e);state="FAILED";running=false;ScopeApp.log("ERROR",source.id+" writer: "+error);}finally{try{wav.close();}catch(Exception e){error=e.toString();state="FAILED";}}}
        public void stop(){running=false;if(local!=null)try{local.stop();}catch(Exception ignored){}if(pipe!=null)try{pipe.close();}catch(Exception ignored){}ICaptureBridge b=ScopeApp.bridge;if(b!=null)try{b.close(source.id);}catch(Exception ignored){}}
        JSONObject json()throws JSONException{JSONObject j=new JSONObject();j.put("id",source.id);j.put("title",source.title);j.put("file",wavName);j.put("sampleRate",rate);j.put("channels",channels);j.put("frames",frames);j.put("firstNs",firstNs);j.put("lastNs",lastNs);j.put("offsetNs",Math.max(0,firstNs-startedNs));j.put("state",state);j.put("everAudible",everAudible);j.put("dropped",dropped);j.put("clipped",clipped);j.put("error",error);j.put("device",device);j.put("gain",gain);j.put("muted",muted);return j;}
    }
}
