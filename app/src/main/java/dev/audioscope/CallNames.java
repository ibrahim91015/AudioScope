package dev.audioscope;

/** Filesystem-safe Unicode names with optional, evidence-based call identity. */
public final class CallNames {
  public static String safe(String s) {
    if (s == null) return "";
    s = s.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim().replaceAll("\\s+", " ");
    return s.substring(0, s.offsetByCodePoints(0, Math.min(60, s.codePointCount(0, s.length()))));
  }

  public static String label(String app, String direction, String contact, String number) {
    java.util.List<String> parts = new java.util.ArrayList<>();
    if (app != null && !app.isBlank()) parts.add(app);
    if ("in".equals(direction)) parts.add("Incoming");
    else if ("out".equals(direction)) parts.add("Outgoing");
    String who = contact == null || contact.isBlank() ? number : contact;
    if (who != null && !who.isBlank()) parts.add(who);
    return parts.isEmpty() ? "Call" : String.join(" · ", parts);
  }

  public static String filename(
      String template,
      String date,
      String app,
      String direction,
      String contact,
      String number,
      String label,
      String source,
      int index,
      String ext) {
    String who = contact == null || contact.isBlank() ? number : contact;
    String[] keys = {"date", "app", "direction", "contact", "number", "label", "source"};
    String[] values = {
      date,
      app,
      "in".equals(direction) || "out".equals(direction) ? direction : "",
      who,
      number,
      label,
      source
    };
    String result = template;
    for (int i = 0; i < keys.length; i++)
      result = result.replace("{" + keys[i] + "}", safe(values[i]));
    result = result.replaceAll("_+", "_").replaceAll("^_|_$", "");
    result = result.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
    if (!template.contains("{date}")) result = safe(date) + "_" + result;
    String suffix = "_" + index + "." + ext;
    if (result.length() > 200) result = safe(date) + "_" + result;
    while ((result + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240)
      result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
    return result + suffix;
  }
}
