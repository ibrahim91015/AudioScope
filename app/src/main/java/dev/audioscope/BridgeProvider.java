package dev.audioscope;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Binder delivery only. Exported entry point accepts shell/root, never other apps. */
public class BridgeProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  public Bundle call(String method, String arg, Bundle extras) {
    int uid = Binder.getCallingUid();
    if (uid != 2000 && uid != 0) throw new SecurityException("Shell-only provider");
    Bundle result = new Bundle();
    if ("deliver".equals(method) && extras != null) {
      IBinder b = extras.getBinder("bridge");
      if (b != null) {
        ScopeApp.attach(b, "Embedded ADB • offline daemon");
        result.putBoolean("ok", true);
      }
    }
    return result;
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    return null;
  }

  public String getType(Uri u) {
    return null;
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
