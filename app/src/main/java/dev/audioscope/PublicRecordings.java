package dev.audioscope;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import org.json.*;

/** Publish selected-format copies through MediaStore, making them visible to Files and Recent. */
public final class PublicRecordings {
  public static String name(File folder, String label, String source, int index, String ext) {
    return folder.getName()
        + "_"
        + label.replaceAll("[^a-zA-Z0-9 -]", "_")
        + "_"
        + source.replaceAll("[^a-zA-Z0-9 -]", "_")
        + "_"
        + index
        + "."
        + ext;
  }

  public static int publish(File folder) throws Exception {
    if (!ScopeApp.prefs().getBoolean("publicFiles", true)) return 0;
    JSONObject manifest = new JSONObject(Exports.read(new File(folder, "session.json")));
    JSONArray tracks = manifest.getJSONArray("tracks");
    int count = 0;
    for (int i = 0; i < tracks.length(); i++) {
      JSONObject t = tracks.getJSONObject(i);
      if (t.has("publicUri")) continue;
      File wav = new File(folder, t.optString("file"));
      if (!wav.isFile() || wav.length() <= 44) continue;
      String codec = t.optString("codec", "WAV");
      String ext =
          codec.equals("AAC")
              ? "m4a"
              : codec.equals("Opus") ? "webm" : codec.equals("PCM") ? "pcm" : "wav";
      File chosen = new File(folder, wav.getName().replace(".wav", "." + ext));
      if (!chosen.isFile() || chosen.length() == 0) {
        chosen = wav;
        ext = "wav";
      }
      String name =
          name(
              folder,
              manifest.optString("label", "Recording"),
              t.optString("title", t.optString("id")),
              i,
              ext);
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      values.put(
          MediaStore.MediaColumns.MIME_TYPE,
          ext.equals("m4a")
              ? "audio/mp4"
              : ext.equals("webm")
                  ? "audio/webm"
                  : ext.equals("pcm") ? "application/octet-stream" : "audio/wav");
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          (ext.equals("pcm") ? Environment.DIRECTORY_DOWNLOADS : Environment.DIRECTORY_RECORDINGS)
              + "/AudioScope");
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
      values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
      ContentResolver resolver = ScopeApp.app.getContentResolver();
      Uri uri =
          resolver.insert(
              ext.equals("pcm")
                  ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                  : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
              values);
      if (uri == null) throw new IOException("MediaStore did not create a recording entry");
      try {
        try (InputStream in = new FileInputStream(chosen);
            OutputStream out = resolver.openOutputStream(uri)) {
          if (out == null) throw new IOException("Public recording stream unavailable");
          in.transferTo(out);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        t.put("publicUri", uri.toString());
        count++;
      } catch (Exception e) {
        resolver.delete(uri, null, null);
        throw e;
      }
      try (FileWriter writer = new FileWriter(new File(folder, "session.json"))) {
        writer.write(manifest.toString(2));
      }
    }
    return count;
  }
}
