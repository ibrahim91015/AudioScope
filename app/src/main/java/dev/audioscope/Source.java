package dev.audioscope;

import java.util.*;

public final class Source {
  public final String id, title, detail, group;
  public final int input, usage, color;

  public Source(
      String id, String title, String detail, String group, int input, int usage, int color) {
    this.id = id;
    this.title = title;
    this.detail = detail;
    this.group = group;
    this.input = input;
    this.usage = usage;
    this.color = color;
  }

  public boolean playback() {
    return usage >= 0;
  }

  public boolean privileged() {
    return playback() || input == 2 || input == 3 || input == 4 || input == 8;
  }

  public static final List<Source> ALL =
      Collections.unmodifiableList(
          Arrays.asList(
              new Source(
                  "mic",
                  "Microphone",
                  "Sound around the phone and your voice.",
                  "INPUT",
                  1,
                  -1,
                  0xff76e2c3),
              new Source(
                  "unprocessed",
                  "Unprocessed mic",
                  "Raw input when the device supports it",
                  "INPUT",
                  9,
                  -1,
                  0xff90d6ee),
              new Source(
                  "recognition",
                  "Voice recognition",
                  "Recognition input preset",
                  "INPUT",
                  6,
                  -1,
                  0xffb6b9fc),
              new Source(
                  "communication_input",
                  "Communication mic",
                  "Echo cancellation / voice processing preset",
                  "INPUT",
                  7,
                  -1,
                  0xffedc387),
              new Source(
                  "camcorder",
                  "Camcorder mic",
                  "Video recording input preset",
                  "INPUT",
                  5,
                  -1,
                  0xffaccfa3),
              new Source(
                  "default_input",
                  "Default input",
                  "Platform-selected default input preset",
                  "INPUT",
                  0,
                  -1,
                  0xffd0bcff),
              new Source(
                  "performance",
                  "Performance mic",
                  "Low-latency voice performance input",
                  "INPUT",
                  10,
                  -1,
                  0xffa2c9ff),
              new Source(
                  "voice_call",
                  "Carrier • both sides",
                  "Both sides of carrier calls; Wi-Fi calls may use VoIP playback instead.",
                  "TELEPHONY",
                  4,
                  -1,
                  0xffb6b9fc),
              new Source(
                  "uplink",
                  "Carrier • uplink",
                  "Your side of a carrier call; shell helper required.",
                  "TELEPHONY",
                  2,
                  -1,
                  0xff76e2c3),
              new Source(
                  "downlink",
                  "Carrier • downlink",
                  "Other side of a carrier call; shell helper required.",
                  "TELEPHONY",
                  3,
                  -1,
                  0xff90d6ee),
              new Source(
                  "media",
                  "Media playback",
                  "Music, videos and podcasts played by apps.",
                  "PLAYBACK",
                  -1,
                  1,
                  0xff90d6ee),
              new Source(
                  "game",
                  "Game playback",
                  "USAGE_GAME • independent playback bus",
                  "PLAYBACK",
                  -1,
                  14,
                  0xffedc387),
              new Source(
                  "voice_playback",
                  "VoIP / Wi-Fi call playback",
                  "Other side of Wi-Fi, Teams and app calls. Arm before joining.",
                  "PLAYBACK",
                  -1,
                  2,
                  0xff76e2c3),
              new Source(
                  "navigation",
                  "Navigation playback",
                  "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE",
                  "PLAYBACK",
                  -1,
                  12,
                  0xffb6b9fc),
              new Source(
                  "assistant",
                  "Assistant playback",
                  "USAGE_ASSISTANT",
                  "PLAYBACK",
                  -1,
                  16,
                  0xffedc387),
              new Source("alarm", "Alarms", "USAGE_ALARM", "PLAYBACK", -1, 4, 0xff90d6ee),
              new Source(
                  "notification",
                  "Notifications",
                  "USAGE_NOTIFICATION",
                  "PLAYBACK",
                  -1,
                  5,
                  0xffaccfa3),
              new Source(
                  "unknown",
                  "Other eligible playback",
                  "USAGE_UNKNOWN",
                  "PLAYBACK",
                  -1,
                  0,
                  0xffb6b9fc),
              new Source(
                  "signalling",
                  "Call signalling / DTMF",
                  "USAGE_VOICE_COMMUNICATION_SIGNALLING",
                  "PLAYBACK",
                  -1,
                  3,
                  0xffd0bcff),
              new Source(
                  "ringtone",
                  "Ringtone playback",
                  "USAGE_NOTIFICATION_RINGTONE",
                  "PLAYBACK",
                  -1,
                  6,
                  0xffffb1ce),
              new Source(
                  "accessibility",
                  "Accessibility playback",
                  "USAGE_ASSISTANCE_ACCESSIBILITY",
                  "PLAYBACK",
                  -1,
                  11,
                  0xff89dfc5),
              new Source(
                  "sonification",
                  "Interface sounds",
                  "USAGE_ASSISTANCE_SONIFICATION",
                  "PLAYBACK",
                  -1,
                  13,
                  0xfff5cd87),
              new Source(
                  "notification_event",
                  "Notification events",
                  "Reminder / battery event usage",
                  "PLAYBACK",
                  -1,
                  10,
                  0xffa2c9ff),
              new Source(
                  "notification_request",
                  "Communication requests",
                  "Legacy usage 7; Android 13+ treats it as notification",
                  "LEGACY PLAYBACK",
                  -1,
                  7,
                  0xffd0bcff),
              new Source(
                  "notification_instant",
                  "Instant communication",
                  "Legacy usage 8; Android 13+ treats it as notification",
                  "LEGACY PLAYBACK",
                  -1,
                  8,
                  0xffffb1ce),
              new Source(
                  "notification_delayed",
                  "Delayed communication",
                  "Legacy usage 9; Android 13+ treats it as notification",
                  "LEGACY PLAYBACK",
                  -1,
                  9,
                  0xff89dfc5),
              new Source(
                  "remote_submix",
                  "Remote submix",
                  "Experimental • may redirect / silence device output",
                  "ADVANCED",
                  8,
                  -1,
                  0xfffa998e)));

  public static Source get(String id) {
    for (Source s : ALL) if (s.id.equals(id)) return s;
    throw new IllegalArgumentException("Unknown source: " + id);
  }
}
