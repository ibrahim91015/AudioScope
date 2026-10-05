package dev.audioscope;

import android.content.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.storage.*;
import android.provider.DocumentsContract;
import java.io.*;

/** SAF grants select a destination; originals and encoding remain privately staged. */
public final class StorageFolders {
  public static String label() {
    return ScopeApp.prefs().getString("saveFolderName", "Recordings/AudioScope");
  }

  public static String label(String tree, String fallback) {
    return tree.isEmpty() ? "Recordings/AudioScope" : fallback;
  }

  public static void remember(Uri tree, int flags) throws Exception {
    ContentResolver resolver = ScopeApp.app.getContentResolver();
    resolver.takePersistableUriPermission(
        tree,
        flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
    Uri folder =
        DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
    String name = "Selected folder";
    try (android.database.Cursor c =
        resolver.query(
            folder,
            new String[] {
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_FLAGS
            },
            null,
            null,
            null)) {
      if (c == null || !c.moveToFirst()) throw new IOException("Folder is unavailable");
      name = c.getString(0);
      if ((c.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0)
        throw new IOException("Folder does not support creating files");
    }
    String prior = ScopeApp.prefs().getString("saveTree", "");
    ScopeApp.prefs()
        .edit()
        .putString("saveTree", tree.toString())
        .putString("saveFolderName", name)
        .apply();
    // Retain prior grants so existing session copies can still be renamed or opened.
  }

  public static void reset() {
    String prior = ScopeApp.prefs().getString("saveTree", "");
    ScopeApp.prefs().edit().remove("saveTree").remove("saveFolderName").apply();
    // Existing recordings keep their folder grants.
  }

  private static void release(Uri tree) {
    try {
      ScopeApp.app
          .getContentResolver()
          .releasePersistableUriPermission(
              tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Exception ignored) {
    }
  }

  public static Uri copy(String treeText, String name, String mime, InputStream bytes)
      throws Exception {
    Uri tree = Uri.parse(treeText);
    ContentResolver resolver = ScopeApp.app.getContentResolver();
    Uri folder =
        DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
    Uri result = DocumentsContract.createDocument(resolver, folder, mime, name + ".tmp");
    if (result == null) throw new IOException("Chosen folder did not create a file");
    boolean canRename = false;
    try (android.database.Cursor c =
        resolver.query(
            result, new String[] {DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
      canRename =
          c != null
              && c.moveToFirst()
              && (c.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0;
    }
    if (!canRename) {
      DocumentsContract.deleteDocument(resolver, result);
      result = DocumentsContract.createDocument(resolver, folder, mime, name);
      if (result == null) throw new IOException("Chosen provider did not create the audio file");
    }
    boolean ok = false;
    try {
      try (OutputStream out = resolver.openOutputStream(result, "w")) {
        if (out == null) throw new IOException("Chosen folder is not writable");
        bytes.transferTo(out);
      }
      Uri published = canRename ? DocumentsContract.renameDocument(resolver, result, name) : result;
      if (published != null) result = published;
      ok = true;
      scan(result, mime);
      return result;
    } finally {
      if (!ok)
        try {
          DocumentsContract.deleteDocument(resolver, result);
        } catch (Exception ignored) {
        }
    }
  }

  public static void scan(Uri document, String mime) {
    try {
      String[] id = DocumentsContract.getDocumentId(document).split(":", 2);
      if (id.length != 2) return;
      StorageManager storage = ScopeApp.app.getSystemService(StorageManager.class);
      for (StorageVolume volume : storage.getStorageVolumes()) {
        if (!(id[0].equals("primary") && volume.isPrimary())
            && !id[0].equalsIgnoreCase(String.valueOf(volume.getUuid()))) continue;
        File root = volume.getDirectory();
        if (root == null) continue;
        File file = new File(root, id[1]);
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) return;
        MediaScannerConnection.scanFile(
            ScopeApp.app,
            new String[] {file.getPath()},
            new String[] {mime},
            (path, uri) -> {
              ScopeApp.log(
                  "INFO",
                  uri == null
                      ? "Files indexing requested; provider handles Recent"
                      : "Saved audio indexed for Files / Recent");
            });
        return;
      }
    } catch (Exception e) {
      ScopeApp.log(
          "WARN",
          "Saved document is provided by its folder provider; local media scan unavailable");
    }
  }
}
