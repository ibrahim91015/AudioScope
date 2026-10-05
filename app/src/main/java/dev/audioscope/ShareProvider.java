package dev.audioscope;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only, URI-granted exports. No external paths or private identity files. */
public class ShareProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  public static Uri uri(File f) {
    String root = ScopeApp.app.getExternalFilesDir(null).getAbsolutePath();
    return new Uri.Builder()
        .scheme("content")
        .authority("dev.audioscope.files")
        .appendPath(f.getAbsolutePath().substring(root.length() + 1))
        .build();
  }

  private File file(Uri u) throws FileNotFoundException {
    try {
      File root = getContext().getExternalFilesDir(null).getCanonicalFile();
      File f = new File(root, u.getLastPathSegment()).getCanonicalFile();
      if (!f.getPath().startsWith(root.getPath() + File.separator) || !f.isFile())
        throw new IOException("Invalid export");
      return f;
    } catch (IOException e) {
      throw new FileNotFoundException(e.getMessage());
    }
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new SecurityException("Read-only exports");
    return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    try {
      File f = file(u);
      MatrixCursor c =
          new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
      c.addRow(new Object[] {f.getName(), f.length()});
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  public String getType(Uri u) {
    String n = u.getLastPathSegment();
    return n.endsWith("wav")
        ? "audio/wav"
        : n.endsWith("m4a")
            ? "audio/mp4"
            : n.endsWith("webm")
                ? "audio/webm"
                : n.endsWith("mka")
                    ? "audio/x-matroska"
                    : n.endsWith("zip") ? "application/zip" : "text/plain";
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
