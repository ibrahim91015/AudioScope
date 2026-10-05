package dev.audioscope;
import android.os.ParcelFileDescriptor;
interface ICaptureBridge {
    String arm(String source, int rate, int channels, int uidFilter);
    ParcelFileDescriptor open(String source, int rate, int channels, int uidFilter);
    void close(String source);
    void disarm();
    String inspect();
    void shutdown();
    void destroy();
    int apiVersion();
    String systemSetup(String action, String value);
}
