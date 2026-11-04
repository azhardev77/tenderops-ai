package com.tenderops.visual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses SAM 3.1 structured output (live-verified 2026-11-05 probe).
 *
 * <p>Grammar: {@code <|box;x1=..;y1=..;x2=..;y2=..[;c=..][;w=..;h=..]|>} records in
 * source-image pixels (top-left origin), normalized by the embedded {@code w/h} when
 * present else the rendered image size; optional
 * {@code <|mask;x=..;y=..;data=..|>} attaching to the immediately preceding box
 * (orphan masks are ignored). The mask {@code data} blob runs to the end of its span
 * (it may itself contain {@code ;} and {@code =}).
 *
 * <p>A {@code <0f>} header/counter prefix, whitespace and digits between spans are
 * skipped; natural-language prose fails closed. Blank output means the model found
 * nothing and yields zero detections (still a live outcome, never an error).
 */
public final class SamOutputParser {

  public record SamBox(double x0, double y0, double x1, double y1, double confidence,
      String maskRef) {}

  private static final Pattern HEADER_TAIL = Pattern.compile("(\\s|<[^<>|]*>|\\d+)*");
  private static final Set<String> BOX_KEYS = Set.of("x1", "y1", "x2", "y2", "c", "w", "h");
  private static final Set<String> MASK_SCALARS = Set.of("x", "y", "h", "w", "enc");

  private SamOutputParser() {}

  public static List<SamBox> parse(String output, int imageWidth, int imageHeight) {
    List<SamBox> boxes = new ArrayList<>();
    if (output == null || output.isBlank()) {
      return boxes;
    }
    StringBuilder rest = new StringBuilder();
    int cursor = 0;
    int pendingBox = -1;
    while (true) {
      int start = output.indexOf("<|", cursor);
      if (start < 0) {
        rest.append(output, cursor, output.length());
        break;
      }
      rest.append(output, cursor, start);
      int end = output.indexOf("|>", start + 2);
      if (end < 0) {
        throw new SamFormatException(
            "Unterminated SAM record: " + abbreviate(output.substring(start)));
      }
      String span = output.substring(start, end + 2);
      String inner = output.substring(start + 2, end);
      int sep = inner.indexOf(';');
      String kind = sep < 0 ? inner : inner.substring(0, sep);
      if ("box".equals(kind)) {
        boxes.add(parseBox(sep < 0 ? "" : inner.substring(sep + 1), imageWidth,
            imageHeight, span));
        pendingBox = boxes.size() - 1;
      } else if ("mask".equals(kind)) {
        parseMask(inner, span);
        if (pendingBox >= 0 && boxes.get(pendingBox).maskRef() == null) {
          SamBox prev = boxes.get(pendingBox);
          boxes.set(pendingBox, new SamBox(prev.x0(), prev.y0(), prev.x1(), prev.y1(),
              prev.confidence(), span));
        }
      } else {
        throw new SamFormatException("Unknown SAM record: " + abbreviate(span));
      }
      cursor = end + 2;
    }
    if (!HEADER_TAIL.matcher(rest.toString()).matches()) {
      throw new SamFormatException("Unparseable SAM text: " + abbreviate(rest.toString()));
    }
    return boxes;
  }

  private static SamBox parseBox(String fieldsText, int imageWidth, int imageHeight,
      String span) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String segment : fieldsText.split(";", -1)) {
      if (segment.isEmpty()) {
        continue;
      }
      int eq = segment.indexOf('=');
      if (eq <= 0) {
        throw new SamFormatException("Bad box field: " + abbreviate(span));
      }
      fields.put(segment.substring(0, eq), segment.substring(eq + 1));
    }
    for (String key : fields.keySet()) {
      if (!BOX_KEYS.contains(key)) {
        throw new SamFormatException(
            "Unknown box key '" + key + "': " + abbreviate(span));
      }
    }
    double x1 = pixel(fields, "x1", span);
    double y1 = pixel(fields, "y1", span);
    double x2 = pixel(fields, "x2", span);
    double y2 = pixel(fields, "y2", span);
    if (!(x2 > x1) || !(y2 > y1)) {
      throw new SamFormatException("Degenerate box: " + abbreviate(span));
    }
    double confidence = 0.90;
    if (fields.containsKey("c")) {
      confidence = number(fields.get("c"), "confidence");
      if (confidence < 0.0 || confidence > 1.0) {
        throw new SamFormatException(
            "Confidence out of [0,1]: " + abbreviate(span));
      }
    }
    double width = fields.containsKey("w") ? positive(fields, "w", span) : imageWidth;
    double height = fields.containsKey("h") ? positive(fields, "h", span) : imageHeight;
    return new SamBox(clamp(x1 / width), clamp(y1 / height), clamp(x2 / width),
        clamp(y2 / height), confidence, null);
  }

  private static void parseMask(String inner, String span) {
    if (inner.length() <= "mask".length() + 1) {
      throw new SamFormatException("Mask needs data: " + abbreviate(span));
    }
    String tail = inner.substring("mask".length() + 1);
    while (true) {
      if (tail.startsWith("data=")) {
        if (tail.length() <= "data=".length()) {
          throw new SamFormatException("Mask needs data: " + abbreviate(span));
        }
        return;
      }
      int semi = tail.indexOf(';');
      if (semi < 0) {
        throw new SamFormatException("Mask needs data: " + abbreviate(span));
      }
      String pair = tail.substring(0, semi);
      tail = tail.substring(semi + 1);
      int eq = pair.indexOf('=');
      if (eq <= 0 || pair.substring(eq + 1).isBlank()
          || !MASK_SCALARS.contains(pair.substring(0, eq))) {
        throw new SamFormatException("Bad mask field: " + abbreviate(span));
      }
    }
  }

  private static double pixel(Map<String, String> fields, String key, String span) {
    if (!fields.containsKey(key)) {
      throw new SamFormatException("Box needs " + key + ": " + abbreviate(span));
    }
    return number(fields.get(key), "pixel");
  }

  private static double positive(Map<String, String> fields, String key, String span) {
    double value = number(fields.get(key), key);
    if (!(value > 0)) {
      throw new SamFormatException("Box " + key + " must be positive: " + span);
    }
    return value;
  }

  private static double number(String field, String what) {
    try {
      return Double.parseDouble(field.trim());
    } catch (NumberFormatException e) {
      throw new SamFormatException("Bad " + what + " number: " + field);
    }
  }

  private static double clamp(double v) {
    return Math.min(1.0, Math.max(0.0, v));
  }

  private static String abbreviate(String record) {
    return record.length() <= 80 ? record : record.substring(0, 80) + "...";
  }
}
