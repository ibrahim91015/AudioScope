package dev.audioscope;

import android.media.AudioAttributes;
import android.media.MediaRecorder;
import java.util.*;

public final class Source {
    public final String id, title, detail, group;
    public final int input, usage, color;
    public Source(String id,String title,String detail,String group,int input,int usage,int color) {
        this.id=id;this.title=title;this.detail=detail;this.group=group;this.input=input;this.usage=usage;this.color=color;
    }
    public boolean playback(){return usage>=0;}
    public boolean privileged(){return playback() || input==2 || input==3 || input==4 || input==8;}
    public static final List<Source> ALL=Collections.unmodifiableList(Arrays.asList(
        new Source("mic","Microphone","Physical input • concurrent capture test","INPUT",1,-1,0xff76e2c3),
        new Source("unprocessed","Unprocessed mic","Raw input when the device supports it","INPUT",9,-1,0xff90d6ee),
        new Source("recognition","Voice recognition","Recognition input preset","INPUT",6,-1,0xffb6b9fc),
        new Source("communication_input","Communication mic","Echo cancellation / voice processing preset","INPUT",7,-1,0xffedc387),
        new Source("camcorder","Camcorder mic","Video recording input preset","INPUT",5,-1,0xffaccfa3),
        new Source("voice_call","Carrier • both sides","VOICE_CALL • shell privilege required","TELEPHONY",4,-1,0xffb6b9fc),
        new Source("uplink","Carrier • uplink","Local party • VOICE_UPLINK","TELEPHONY",2,-1,0xff76e2c3),
        new Source("downlink","Carrier • downlink","Remote party • VOICE_DOWNLINK","TELEPHONY",3,-1,0xff90d6ee),
        new Source("media","Media playback","Loopback with normal playback preserved","PLAYBACK",-1,1,0xff90d6ee),
        new Source("game","Game playback","USAGE_GAME • independent playback bus","PLAYBACK",-1,14,0xffedc387),
        new Source("voice_playback","VoIP / Teams playback","USAGE_VOICE_COMMUNICATION • arm before joining","PLAYBACK",-1,2,0xff76e2c3),
        new Source("navigation","Navigation playback","USAGE_ASSISTANCE_NAVIGATION_GUIDANCE","PLAYBACK",-1,12,0xffb6b9fc),
        new Source("assistant","Assistant playback","USAGE_ASSISTANT","PLAYBACK",-1,16,0xffedc387),
        new Source("alarm","Alarms","USAGE_ALARM","PLAYBACK",-1,4,0xff90d6ee),
        new Source("notification","Notifications","USAGE_NOTIFICATION","PLAYBACK",-1,5,0xffaccfa3),
        new Source("unknown","Other eligible playback","USAGE_UNKNOWN","PLAYBACK",-1,0,0xffb6b9fc),
        new Source("remote_submix","Remote submix","Experimental • may redirect / silence device output","ADVANCED",8,-1,0xfffa998e)
    ));
    public static Source get(String id){for(Source s:ALL)if(s.id.equals(id))return s;throw new IllegalArgumentException("Unknown source: "+id);}
}
