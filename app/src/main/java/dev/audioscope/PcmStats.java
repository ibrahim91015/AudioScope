package dev.audioscope;

/** Signal health measures PCM, independently of API startup success. */
public final class PcmStats {
    public final double rms, peak, nonzero, db;
    public final int clipped;
    public PcmStats(byte[] pcm,int length){
        double sum=0,p=0;int nz=0,clip=0,n=length/2;
        for(int i=0;i<n;i++){int s=(short)((pcm[i*2]&255)|(pcm[i*2+1]<<8));double v=s/32768.0;sum+=v*v;p=Math.max(p,Math.abs(v));if(s!=0)nz++;if(Math.abs(s)>=32760)clip++;}
        rms=n==0?0:Math.sqrt(sum/n);peak=p;nonzero=n==0?0:(double)nz/n;clipped=clip;db=20*Math.log10(Math.max(rms,0.000001));
    }
    public static short clamp(double v){return (short)Math.max(-32768,Math.min(32767,Math.round(v)));}
}
