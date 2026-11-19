package at.spps;

import static at.spps.Views.Event;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.http.*;
import org.springframework.web.util.HtmlUtils;

class Exports {
  static final List<String> HEAD =
      List.of("Datum", "Beginn", "Ende", "Fach", "Gruppe", "Lehrkraft", "Raum", "Status");

  static List<String> fields(Event e) {
    return List.of(
        e.date().toString(),
        e.start().toString(),
        e.end().toString(),
        e.subject(),
        String.join(", ", e.groups()),
        String.join(", ", e.teachers()),
        e.room(),
        e.status());
  }

  static String safeCsv(String field) {
    return field.matches("(?s)^[=+@\\-\\t\\r].*") ? "'" + field : field;
  }

  static String csv(List<Event> rows) throws IOException {
    var out = new StringWriter();
    try (var p = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setDelimiter(';').get())) {
      p.printRecord(HEAD);
      for (var e : rows) p.printRecord(fields(e).stream().map(Exports::safeCsv).toList());
    }
    return out.toString();
  }

  static String esc(String s) {
    return s.replace("\\", "\\\\")
        .replace("\r", "")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;");
  }

  static String utc(LocalDate d, LocalTime t) {
    return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(d.atTime(t).atZone(ZoneId.of("Europe/Vienna")));
  }

  static String ics(List<Event> rows, String school) {
    var lines =
        new ArrayList<>(
            List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//SPPS//SchoolPlanning//DE",
                "CALSCALE:GREGORIAN"));
    var stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    for (var e : rows)
      lines.addAll(
          List.of(
              "BEGIN:VEVENT",
              "UID:" + esc(school + "-" + e.uid() + "@schoolplanning"),
              "DTSTAMP:" + stamp,
              "DTSTART:" + utc(e.date(), e.start()),
              "DTEND:" + utc(e.date(), e.end()),
              "SUMMARY:" + esc(e.subject() + " · " + String.join(", ", e.groups())),
              "LOCATION:" + esc(e.room()),
              "DESCRIPTION:" + esc(e.status() + " · " + String.join(", ", e.teachers())),
              "STATUS:" + (e.status().equals("Ausfall") ? "CANCELLED" : "CONFIRMED"),
              "END:VEVENT"));
    lines.add("END:VCALENDAR");
    StringBuilder result = new StringBuilder();
    for (String line : lines) {
      int len = 0;
      for (int cp : line.codePoints().toArray()) {
        String ch = Character.toString(cp);
        int size = ch.getBytes(StandardCharsets.UTF_8).length;
        if (len + size > 75) {
          result.append("\r\n ");
          len = 1;
        }
        result.append(ch);
        len += size;
      }
      result.append("\r\n");
    }
    return result.toString();
  }

  static byte[] pdf(List<Event> rows) throws IOException {
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      int total = Math.max(1, (rows.size() + 25) / 26);
      for (int sheet = 0; sheet < total; sheet++) {
        var page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(842, 595));
        doc.addPage(page);
        try (var ink = new PDPageContentStream(doc, page)) {
          write(ink, 28, 555, 16, "Stundenplan  /  " + (sheet + 1) + " von " + total);
          int[] cols = {28, 100, 150, 200, 350, 450, 565, 660};
          for (int i = 0; i < 8; i++) write(ink, cols[i], 527, 10, HEAD.get(i));
          for (int n = sheet * 26; n < Math.min(rows.size(), sheet * 26 + 26); n++) {
            var vals = fields(rows.get(n));
            for (int i = 0; i < 8; i++) {
              int limit = (i == 7 ? 820 : cols[i + 1]) - cols[i] - 5;
              String s = vals.get(i).replaceAll("[^\\x20-\\x7e\\u00a0-\\u00ff]", "?");
              while (s.length() > 1 && PDType1Font.HELVETICA.getStringWidth(s) * .009 > limit)
                s = s.substring(0, s.length() - 1);
              write(ink, cols[i], 505 - (n % 26) * 18, 9, s);
            }
          }
        }
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  static void write(PDPageContentStream ink, int x, int y, int size, String s) throws IOException {
    ink.beginText();
    ink.setFont(PDType1Font.HELVETICA, size);
    ink.newLineAtOffset(x, y);
    ink.showText(s);
    ink.endText();
  }

  static String html(List<Event> rows) {
    var out =
        new StringBuilder(
            "<!doctype html><html lang=de><meta charset=utf-8><meta http-equiv=refresh"
                + " content=120><meta name=viewport"
                + " content='width=device-width,initial-scale=1'><title>Stundenplan</title><style>body{font:16px"
                + " system-ui;color:#173b34}table{border-collapse:collapse}td,th{padding:.5em;border-bottom:1px"
                + " solid #ccc;text-align:left}</style><h1>Stundenplan</h1><table><thead><tr>");
    for (String h : HEAD) out.append("<th>").append(h).append("</th>");
    out.append("</tr></thead><tbody>");
    for (var e : rows) {
      out.append("<tr>");
      for (String v : fields(e)) out.append("<td>").append(HtmlUtils.htmlEscape(v)).append("</td>");
      out.append("</tr>");
    }
    return out.append("</tbody></table></html>").toString();
  }

  static ResponseEntity<byte[]> render(List<Event> rows, String fmt, String school)
      throws IOException {
    String mime;
    byte[] bytes;
    switch (fmt) {
      case "pdf" -> {
        mime = "application/pdf";
        bytes = pdf(rows);
      }
      case "ics" -> {
        mime = "text/calendar;charset=UTF-8";
        bytes = ics(rows, school).getBytes(StandardCharsets.UTF_8);
      }
      case "csv" -> {
        mime = "text/csv;charset=UTF-8";
        bytes = csv(rows).getBytes(StandardCharsets.UTF_8);
      }
      case "html" -> {
        mime = "text/html;charset=UTF-8";
        bytes = html(rows).getBytes(StandardCharsets.UTF_8);
      }
      default -> throw new IllegalArgumentException("Unbekanntes Format");
    }
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            (fmt.equals("html") ? "inline" : "attachment") + "; filename=stundenplan." + fmt)
        .contentType(MediaType.parseMediaType(mime))
        .body(bytes);
  }
}
