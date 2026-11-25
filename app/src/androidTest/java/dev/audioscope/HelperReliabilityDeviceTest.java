package dev.audioscope;

import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;
import java.io.File;
import org.json.JSONObject;

/** Run only after a helper is connected and no real call/recording is active. */
@SuppressWarnings("deprecation")
public class HelperReliabilityDeviceTest extends InstrumentationTestCase {
  public void testSavedAudioContinuesWhenAppDoesNotDrainTheWaveformPipe() throws Exception {
    assertFalse("Stop the current recording first", CaptureService.active());
    ScopeApp.monitor.stop();
    ICaptureBridge helper = ScopeApp.bridge;
    assertNotNull("Connect the helper first", helper);
    assertEquals(5, helper.apiVersion());
    File file =
        File.createTempFile(
            "helper-pipe-", ".wav", getInstrumentation().getTargetContext().getCacheDir());
    ParcelFileDescriptor preview = null;
    try {
      try (ParcelFileDescriptor output =
          ParcelFileDescriptor.open(
              file, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)) {
        preview = helper.openSaved("communication_input", 48000, 1, -1, true, false, false, output);
      }
      // Deliberately do not read the preview pipe. Its buffer fills, exactly as with a frozen app.
      Thread.sleep(8000);
      JSONObject result = new JSONObject(helper.finishSaved("communication_input"));
      assertTrue(result.optBoolean("savedByHelper"));
      assertTrue(
          "Independent audio must keep growing past the preview buffer",
          result.optLong("savedFrames") > 48000 * 6);
      assertEquals("", result.optString("error"));
      try (WavFile.Reader wav = new WavFile.Reader(file)) {
        assertEquals(result.getLong("savedFrames"), wav.frames);
        assertEquals(48000, wav.rate);
      }
    } finally {
      helper.close("communication_input");
      if (preview != null) preview.close();
      file.delete();
    }
  }
}
