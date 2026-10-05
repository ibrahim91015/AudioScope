package dev.audioscope;

import android.media.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

public final class Exports {
    public static void finish(File folder)throws Exception{
        JSONObject manifest=new JSONObject(read(new File(folder,"session.json")));JSONArray tracks=manifest.getJSONArray("tracks");List<PcmRouter.Input> inputs=new ArrayList<>();List<MkaWriter.Track> mka=new ArrayList<>();
        try{for(int i=0;i<tracks.length();i++){JSONObject t=tracks.getJSONObject(i);File f=new File(folder,t.optString("file"));if(!f.isFile()||f.length()<=44)continue;long offset=t.optLong("offsetNs"),span=t.optLong("lastNs")-t.optLong("firstNs");double gain=t.optBoolean("muted")?0:t.optDouble("gain",1);inputs.add(new PcmRouter.Input(t.getString("id"),f,offset,span,gain));mka.add(new MkaWriter.Track(t.getString("title"),f,t.getInt("sampleRate"),t.getInt("channels"),offset));}
            if(inputs.isEmpty())return;int rate=48000;
            if(manifest.optBoolean("stereo"))PcmRouter.export(new File(folder,"stereo.wav"),inputs,rate,manifest.optString("routeLeft"),manifest.optString("routeRight"),false);
            if(manifest.optBoolean("mix"))PcmRouter.export(new File(folder,"mix.wav"),inputs,rate,"","",true);
            if(manifest.optBoolean("mka"))MkaWriter.export(new File(folder,"multitrack.mka"),mka);
            int bitrate=manifest.optInt("bitrate",128000);for(int i=0;i<tracks.length();i++){JSONObject t=tracks.getJSONObject(i);String codec=t.optString("codec",manifest.optString("codec","WAV"));File f=new File(folder,t.optString("file"));if(f.isFile()&&(codec.equals("AAC")||codec.equals("Opus")))try{encode(f,codec,bitrate);}catch(Throwable e){ScopeApp.log("ERROR","Codec export "+f.getName()+": "+ShellBridge.root(e));}}String codec=manifest.optString("codec","WAV");if(codec.equals("AAC")||codec.equals("Opus"))for(String name:new String[]{"stereo.wav","mix.wav"}){File f=new File(folder,name);if(f.isFile())try{encode(f,codec,bitrate);}catch(Throwable e){ScopeApp.log("ERROR","Codec export "+name+": "+ShellBridge.root(e));}}
        }finally{for(PcmRouter.Input in:inputs)in.close();}
    }
    public static String read(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return new String(in.readAllBytes(),"UTF-8");}}
    public static File zip(File folder)throws IOException{File destination=new File(ScopeApp.app.getExternalFilesDir(null),folder.getName()+".zip");File[] files=folder.listFiles();try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destination)))){if(files!=null)for(File f:files)if(f.isFile()&&!f.getName().endsWith(".tmp")){out.putNextEntry(new ZipEntry(folder.getName()+"/"+f.getName()));try(InputStream in=new FileInputStream(f)){in.transferTo(out);}out.closeEntry();}}return destination;}
    public static void encode(File wav,String codec)throws Exception{
        encode(wav,codec,ScopeApp.prefs().getInt("bitrate",128000));
    }
    public static void encode(File wav,String codec,int bitrate)throws Exception{
        boolean opus=codec.equals("Opus");String mime=opus?"audio/opus":"audio/mp4a-latm";File target=new File(wav.getParentFile(),wav.getName().replace(".wav",opus?".webm":".m4a"));MediaCodec encoder=null;MediaMuxer muxer=null;boolean started=false,ok=false;
        try(WavFile.Reader info=new WavFile.Reader(wav);InputStream in=new BufferedInputStream(new FileInputStream(wav))){byte[] header=new byte[44];new DataInputStream(in).readFully(header);MediaFormat format=MediaFormat.createAudioFormat(mime,info.rate,info.channels);format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);if(!opus)format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);encoder=MediaCodec.createEncoderByType(mime);encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();muxer=new MediaMuxer(target.getPath(),opus?MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM:MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();boolean inputDone=false,outputDone=false;long frames=0;int index=-1;long lastProgress=System.nanoTime();
            while(!outputDone){if(System.nanoTime()-lastProgress>15_000_000_000L)throw new IOException("Encoder stalled");if(!inputDone){int slot=encoder.dequeueInputBuffer(10000);if(slot>=0){ByteBuffer buf=encoder.getInputBuffer(slot);byte[] data=new byte[Math.min(buf.capacity(),8192)];int n=in.read(data);if(n<0){n=0;inputDone=true;}buf.clear();buf.put(data,0,n);encoder.queueInputBuffer(slot,0,n,frames*1000000L/info.rate,inputDone?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);frames+=n/(2*info.channels);lastProgress=System.nanoTime();}}
                int slot=encoder.dequeueOutputBuffer(bi,10000);if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){index=muxer.addTrack(encoder.getOutputFormat());muxer.start();started=true;}else if(slot>=0){ByteBuffer b=encoder.getOutputBuffer(slot);if(bi.size>0&&(bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){if(!started)throw new IOException("Missing codec format");b.position(bi.offset);b.limit(bi.offset+bi.size);muxer.writeSampleData(index,b,bi);}outputDone=(bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(slot,false);lastProgress=System.nanoTime();}}
            muxer.stop();started=false;ok=true;
        }finally{if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}if(!ok)target.delete();}
    }
}
