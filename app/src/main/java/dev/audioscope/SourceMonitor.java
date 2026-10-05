package dev.audioscope;

import java.util.*;
import java.util.concurrent.*;

/** Foreground-only metering. Monitoring never creates a recording file. */
public final class SourceMonitor {
    private final Map<String,CaptureService.Track> meters=new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private final java.util.concurrent.atomic.AtomicBoolean updating=new java.util.concurrent.atomic.AtomicBoolean();
    private android.os.IBinder backend;
    public void enable(){enabled=true;}
    public void disable(){enabled=false;ScopeApp.IO.execute(this::closeAll);}
    public void refresh(){if(updating.compareAndSet(false,true))ScopeApp.IO.execute(()->{try{reconcile();}finally{updating.set(false);}});}
    public CaptureService.Track get(String id){return meters.get(id);}
    public synchronized void reconcile(){
        if(!enabled)return;
        // Finalization closes pipes sequentially. Do not reopen their source IDs
        // until all recording readers have finished, even though active() is false.
        if(CaptureService.instance!=null&&CaptureService.stopping)return;
        android.os.IBinder current=ScopeApp.bridge==null?null:ScopeApp.bridge.asBinder();
        if(current!=backend){closeAll();backend=current;}
        for(Source source:Source.ALL){
            CaptureService.Track recording=CaptureService.tracks.get(source.id);
            if(CaptureService.active()&&recording!=null&&(recording.running||recording.done.getCount()!=0))continue;
            if(!meters.containsKey(source.id)){
                CaptureService.Track meter=new CaptureService.Track(source,null);
                meters.put(source.id,meter);ScopeApp.IO.execute(meter::run);
            }
        }
    }
    public void stop(){enabled=false;closeAll();}
    private synchronized void closeAll(){for(CaptureService.Track meter:meters.values())meter.stop();
        for(CaptureService.Track meter:meters.values())try{meter.done.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        meters.clear();
    }
    public synchronized void release(String id){
        CaptureService.Track meter=meters.remove(id);if(meter==null)return;meter.stop();
        try{meter.done.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
