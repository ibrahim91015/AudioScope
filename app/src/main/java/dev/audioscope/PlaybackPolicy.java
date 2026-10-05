/* Adapted from CallVault VoipAudioPolicy, Copyright (C) 2026 The CallVault Authors.
 * AudioScope modifications: arbitrary usages, UID filters, configurable format.
 * GPL-3.0-or-later with the Section 7 terms in LICENSE. */
package dev.audioscope;

import android.media.*;
import android.content.Context;

final class PlaybackPolicy {
    final Object policy,mix;
    PlaybackPolicy(int usage,int rate,int channels,int uid)throws Exception{
        Class<?> rule=Class.forName("android.media.audiopolicy.AudioMixingRule"),rb=Class.forName(rule.getName()+"$Builder");Object builder=rb.getConstructor().newInstance();
        rb.getMethod("setTargetMixRole",int.class).invoke(builder,0);
        rb.getMethod("addMixRule",int.class,Object.class).invoke(builder,1,new AudioAttributes.Builder().setUsage(usage).build());
        if(uid>=0)rb.getMethod("addMixRule",int.class,Object.class).invoke(builder,4,Integer.valueOf(uid));
        if(usage==2)try{rb.getMethod("voiceCommunicationCaptureAllowed",boolean.class).invoke(builder,true);}catch(NoSuchMethodException ignored){}
        Object builtRule=rb.getMethod("build").invoke(builder);
        Class<?> mc=Class.forName("android.media.audiopolicy.AudioMix"),mb=Class.forName(mc.getName()+"$Builder");java.lang.reflect.Constructor<?> ctor=mb.getDeclaredConstructor(rule);ctor.setAccessible(true);Object mixBuilder=ctor.newInstance(builtRule);
        mb.getMethod("setFormat",AudioFormat.class).invoke(mixBuilder,new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(channels==2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO).build());
        mb.getMethod("setRouteFlags",int.class).invoke(mixBuilder,3);mix=mb.getMethod("build").invoke(mixBuilder);
        Class<?> pc=Class.forName("android.media.audiopolicy.AudioPolicy"),pb=Class.forName(pc.getName()+"$Builder");Object pBuilder=pb.getConstructor(Context.class).newInstance(new Object[]{null});
        pb.getMethod("addMix",mc).invoke(pBuilder,mix);policy=pb.getMethod("build").invoke(pBuilder);
        java.lang.reflect.Method register=AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic",pc);register.setAccessible(true);int rc=(Integer)register.invoke(null,policy);if(rc!=0)throw new SecurityException("AudioPolicy registration rejected: "+rc);
    }
    AudioRecord sink()throws Exception{return (AudioRecord)policy.getClass().getMethod("createAudioRecordSink",mix.getClass()).invoke(policy,mix);}
    void close(){try{java.lang.reflect.Method m=AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic",policy.getClass());m.setAccessible(true);m.invoke(null,policy);}catch(Exception ignored){}}
}
