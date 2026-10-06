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
    JSONObject manifest = new JSONObject();
    try {
      manifest = new JSONObject(Exports.read(new File(folder, "session.json")));
    } catch (Exception ignored) {
    }
    JSONObject call = manifest.optJSONObject("call");
    if (call == null) call = new JSONObject();
    String app = call.optString("app", ""), direction = call.optString("direction", "");
    String template =
        manifest.optString("namingTemplate", "{date}_{app}_{direction}_{contact}_{source}");
    if (call.length() > 0
        && !manifest.optBoolean("automaticLabel", true)
        && !template.contains("{label}")) template += "_{label}";
    if (call.length() == 0 && !template.contains("{label}"))
      template = template.replace("{contact}", "{label}");
    String result =
        CallNames.filename(
            template,
            manifest.optLong("timestampUnixMs") > 0
                ? TimeDisplay.file(manifest.optLong("timestampUnixMs"))
                : folder.getName(),
            app,
            direction,
            call.optString("contact", ""),
            call.optString("number", ""),
            label,
            source,
            index,
            ext);
    if (!label.equals(manifest.optString("label", label)) && !template.contains("{label}"))
      result =
          result.substring(0, result.length() - ext.length() - 1)
              + "_"
              + CallNames.safe(label)
              + "."
              + ext;
    return result;
  }

  private static void persist(File folder, JSONObject manifest) throws Exception {
    try (FileWriter writer = new FileWriter(new File(folder, "session.json"))) {
      writer.write(manifest.toString(2));
    }
  }

  public static Uri rename(Uri uri, String name) throws Exception {
    if (android.provider.DocumentsContract.isDocumentUri(ScopeApp.app, uri)) {
      Uri renamed =
          android.provider.DocumentsContract.renameDocument(
              ScopeApp.app.getContentResolver(), uri, name);
      Uri result = renamed == null ? uri : renamed;
      StorageFolders.scan(result, ScopeApp.app.getContentResolver().getType(result));
      return result;
    }
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
    ScopeApp.app.getContentResolver().update(uri, values, null, null);
    return uri;
  }

  public static String destination(File folder) {
    try {
      JSONObject manifest = new JSONObject(Exports.read(new File(folder, "session.json")));
      JSONArray tracks = manifest.optJSONArray("tracks");
      java.util.Set<String> places = new java.util.LinkedHashSet<>();
      if (tracks != null)
        for (int i = 0; i < tracks.length(); i++)
          if (tracks.getJSONObject(i).has("publicUri"))
            places.add(
                tracks.getJSONObject(i).optString("publicDestination", "Recordings/AudioScope"));
      return places.isEmpty() ? "Sessions" : String.join(", ", places);
    } catch (Exception e) {
      return "Sessions";
    }
  }

  public static int publish(File folder) throws Exception {
    JSONObject manifest = new JSONObject(Exports.read(new File(folder, "session.json")));
    if (!manifest.optBoolean("publicFiles", ScopeApp.prefs().getBoolean("publicFiles", true)))
      return 0;
    String tree = manifest.optString("saveTree", ScopeApp.prefs().getString("saveTree", ""));
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
      String mime =
          ext.equals("m4a")
              ? "audio/mp4"
              : ext.equals("webm")
                  ? "audio/webm"
                  : ext.equals("pcm") ? "application/octet-stream" : "audio/wav";
      if (!tree.isEmpty()) {
        try (InputStream in = new FileInputStream(chosen)) {
          Uri custom = StorageFolders.copy(tree, name, mime, in);
          t.put("publicUri", custom.toString());
          t.put("publicDestination", manifest.optString("saveFolderName", "Selected folder"));
          persist(folder, manifest);
          count++;
          continue;
        } catch (Exception e) {
          Notices.error(
              "Save folder unavailable",
              "The requested folder could not be written. A copy will be saved in"
                  + " Recordings/AudioScope instead; originals remain in Sessions. Re-select your"
                  + " folder or reconnect the SD card.\n\n"
                  + ShellBridge.root(e),
              "settings");
          t.put("publicDestination", "Recordings/AudioScope (folder fallback)");
        }
      }
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
      if (!ext.equals("pcm")) {
        values.put(
            MediaStore.Audio.Media.TITLE,
            manifest.optString("label", "Recording") + " · " + t.optString("title", "Audio"));
        values.put(MediaStore.Audio.Media.ARTIST, "AudioScope");
        values.put(MediaStore.Audio.Media.ALBUM, manifest.optString("label", "Recording"));
        values.put(
            MediaStore.Audio.Media.DURATION,
            t.optLong("frames") * 1000 / Math.max(1, t.optInt("sampleRate", 48000)));
      }
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
        t.put(
            "publicDestination",
            ext.equals("pcm") ? "Downloads/AudioScope" : "Recordings/AudioScope");
        count++;
      } catch (Exception e) {
        resolver.delete(uri, null, null);
        throw e;
      }
      try (FileWriter writer = new FileWriter(new File(folder, "session.json"))) {
        writer.write(manifest.toString(2));
      }
    }
    if (manifest.optBoolean("publicMetadata", ScopeApp.prefs().getBoolean("publicMetadata", true))
        && !tree.isEmpty()
        && !manifest.has("publicMetadataUri")) {
      try (InputStream in =
          new ByteArrayInputStream(
              manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        String metaName =
            folder.getName()
                + "_"
                + manifest.optString("label", "Recording").replaceAll("[^a-zA-Z0-9 -]", "_")
                + ".metadata.json";
        Uri metadata = StorageFolders.copy(tree, metaName, "application/json", in);
        manifest.put("publicMetadataUri", metadata.toString());
        persist(folder, manifest);
      } catch (Exception e) {
        Notices.error(
            "Metadata copy needs attention",
            "Audio copies are safe. Session metadata remains in Sessions.\n\n"
                + ShellBridge.root(e),
            "library");
      }
    }
    return count;
  }
}
