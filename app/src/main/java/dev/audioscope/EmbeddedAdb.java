/* Identity persistence and detached launch adapted from CallVault.
 * Copyright (C) 2026 The CallVault Authors. GPL-3.0-or-later + LICENSE Section 7. */
package dev.audioscope;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.*;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

public final class EmbeddedAdb extends AbsAdbConnectionManager {
    private final PrivateKey key;private final Certificate cert;
    private static EmbeddedAdb instance;
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor();
    @SuppressWarnings("deprecation") private EmbeddedAdb(Context c)throws Exception{
        setApi(Build.VERSION.SDK_INT);setTimeout(12,TimeUnit.SECONDS);File k=new File(c.getFilesDir(),"adb-private.der"),crt=new File(c.getFilesDir(),"adb-cert.der");
        if(k.exists()&&crt.exists()){key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(k)));try(InputStream in=new FileInputStream(crt)){cert=CertificateFactory.getInstance("X.509").generateCertificate(in);}}
        else{KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair pair=gen.generateKeyPair();key=pair.getPrivate();X509V3CertificateGenerator cg=new X509V3CertificateGenerator();cg.setSerialNumber(new BigInteger(120,new SecureRandom()));X509Name name=new X509Name("CN=AudioScope");cg.setIssuerDN(name);cg.setSubjectDN(name);cg.setNotBefore(new Date(System.currentTimeMillis()-86400000));cg.setNotAfter(new Date(System.currentTimeMillis()+10L*365*86400000));cg.setPublicKey(pair.getPublic());cg.setSignatureAlgorithm("SHA256withRSA");cert=cg.generate(key);write(k,key.getEncoded());write(crt,cert.getEncoded());}
    }
    public PrivateKey getPrivateKey(){return key;}public Certificate getCertificate(){return cert;}public String getDeviceName(){return "AudioScope";}
    public static synchronized EmbeddedAdb get()throws Exception{if(instance==null)instance=new EmbeddedAdb(ScopeApp.app);return instance;}
    private static byte[] read(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return in.readAllBytes();}}
    private static void write(File f,byte[] b)throws IOException{try(FileOutputStream out=new FileOutputStream(f)){out.write(b);out.getFD().sync();}}
    /** libadb stream-open otherwise has no timeout. Interrupt only the opening thread. */
    private AdbStream boundedOpen(String destination)throws Exception{Thread caller=Thread.currentThread();ScheduledFuture<?> f=TIMER.schedule(caller::interrupt,6,TimeUnit.SECONDS);try{return openStream(destination);}finally{f.cancel(false);Thread.interrupted();}}
    public static void pairDevice(int port,String code){ScopeApp.IO.execute(()->{try{if(port<1||port>65535||!code.matches("[0-9]{6}"))throw new IllegalArgumentException("Use the pairing port and six-digit code shown by Android");boolean ok=get().pair("127.0.0.1",port,code);ScopeApp.log(ok?"INFO":"ERROR",ok?"ADB paired; enter the connection port from the Wireless debugging main page":"Pairing rejected");}catch(Throwable e){ScopeApp.log("ERROR","ADB pairing: "+ShellBridge.root(e));}});}
    public static void launch(int port){ScopeApp.IO.execute(()->{try{EmbeddedAdb adb=get();if(!adb.connect("127.0.0.1",port))throw new IOException("ADB connection refused; check port and pairing");String path=ScopeApp.app.getApplicationInfo().sourceDir;if(!path.matches("/[a-zA-Z0-9_./=+~-]+"))throw new SecurityException("Unexpected APK path");String command="setsid sh -c 'CLASSPATH="+path+" exec app_process / dev.audioscope.ShellBridge "+ScopeApp.app.getApplicationInfo().uid+"' >/dev/null 2>&1 </dev/null & sleep 3";
        try(AdbStream stream=adb.boundedOpen("shell:"+command)){InputStream in=stream.openInputStream();while(in.read()!=-1){}}
        ScopeApp.log("INFO","Detached daemon launch sent • waiting for Binder");ScopeApp.MAIN.postDelayed(()->ScopeApp.log(ScopeApp.bridge!=null?"INFO":"ERROR",ScopeApp.bridge!=null?"Offline daemon ready; Wi-Fi is no longer needed for recording":"No daemon Binder received; inspect logcat AudioScopeDaemon"),2000);
    }catch(Throwable e){ScopeApp.log("ERROR","ADB launch: "+ShellBridge.root(e));}});}
    public static void enableOfflineRestart(){ScopeApp.IO.execute(()->{try{if(CaptureService.active())throw new IllegalStateException("Stop recording before changing ADB transport");EmbeddedAdb adb=get();if(!adb.isConnected())throw new IOException("Connect using the Wireless debugging port first");try{AdbStream s=adb.boundedOpen("tcpip:5555");s.close();}catch(Exception ignored){}Thread.sleep(2500);try{adb.disconnect();}catch(Exception ignored){}if(!adb.connect("127.0.0.1",5555))throw new IOException("Offline ADB listener did not become reachable");ScopeApp.prefs().edit().putBoolean("offlineRestart",true).apply();ScopeApp.log("INFO","Off-Wi-Fi restart armed on port 5555 until reboot");}catch(Throwable e){ScopeApp.log("ERROR","Offline restart: "+ShellBridge.root(e));}});}
    public static void disableOfflineRestart(){ScopeApp.IO.execute(()->{try{if(CaptureService.active())throw new IllegalStateException("Stop recording first");EmbeddedAdb adb=get();if(!adb.isConnected())adb.connect("127.0.0.1",5555);try{AdbStream s=adb.boundedOpen("usb:");s.close();}catch(Exception ignored){}ScopeApp.prefs().edit().putBoolean("offlineRestart",false).apply();ScopeApp.log("INFO","Requested ADB TCP listener shutdown");}catch(Throwable e){ScopeApp.log("ERROR",ShellBridge.root(e));}});}
}
