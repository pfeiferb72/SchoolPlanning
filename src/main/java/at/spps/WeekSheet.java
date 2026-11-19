package at.spps;

import static at.spps.Model.*;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.http.*;

/** A five-day grid. Keys are actual start times, including exams outside the bell schedule. */
final class WeekSheet {
  static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  static final List<String> DAYS = List.of("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag");
  record Card(String title, String detail, int color) {}
  static final class Row {
    final List<List<Card>> cells = new ArrayList<>();
    final Set<Integer> periods = new TreeSet<>();
    Row() {
      for (int i = 0; i < DAYS.size(); i++) cells.add(new ArrayList<>());
    }
  }

  final String title;
  final LocalDate monday;
  final SortedMap<LocalTime, Row> rows = new TreeMap<>();

  WeekSheet(String title, LocalDate monday) {
    this.title = title;
    this.monday = monday;
  }

  static LocalDate monday(LocalDate date) {
    return date.minusDays(date.getDayOfWeek().getValue() - 1);
  }

  Row row(LocalTime start) {
    return rows.computeIfAbsent(start, k -> new Row());
  }

  void timetable(Catalog c) {
    for (int i = 0; i < c.slots.size(); i++) row(c.slots.get(i).start()).periods.add(i + 1);
  }

  void lesson(Catalog c, Course course, Place p, List<String> teachers, String room, String status) {
    int index = 0;
    while (!c.subjects.get(index).id().equals(course.subject())) index++;
    var card = new Card(c.subjects.get(index).name(),
        String.join(" + ", course.groups()) + " · " + room + " · " + String.join(", ", teachers)
            + (status.equals("Unterricht") ? "" : " · " + status), index % 4);
    for (int i = p.slot(); i < p.slot() + course.block(); i++)
      row(c.slots.get(i).start()).cells.get(p.day() - 1).add(card);
  }

  void exam(Views.Event e) {
    row(e.start()).cells.get(e.date().getDayOfWeek().getValue() - 1)
        .add(new Card(e.subject(), e.status() + " " + e.start() + "–" + e.end() + " · "
            + String.join(", ", e.groups()) + " · " + e.room() + " · " + String.join(", ", e.teachers()), 3));
  }

  static WeekSheet scenario(Store.Scenario s, String week, String group, String teacher, String room, String subject) {
    require(Set.of("A", "B").contains(week), "Planwoche A oder B wählen");
    var filters = String.join(" · ", List.of(group, subject, teacher, room).stream().filter(x -> !x.isBlank()).toList());
    var sheet = new WeekSheet(s.name() + " · Woche " + week + (filters.isEmpty() ? "" : " · " + filters), null);
    var plan = s.plan();
    sheet.timetable(plan.catalog);
    var groups = index(plan.catalog.groups, Group::id);
    for (var u : units(plan.catalog)) {
      var a = u.course();
      var p = plan.places.get(u.id());
      if (p == null || !active(a.week(), week.equals("A") ? 0 : 1)
          || (!group.isEmpty() && !groupMatches(groups, group, a.groups()))
          || (!room.isEmpty() && !room.equals(p.room()))
          || (!teacher.isEmpty() && !p.teachers().contains(teacher))
          || (!subject.isEmpty() && !subject.equals(a.subject()))) continue;
      sheet.lesson(plan.catalog, a, p, p.teachers(), p.room(), "Unterricht");
    }
    return sheet;
  }

  static ResponseEntity<byte[]> download(Collection<WeekSheet> weeks) throws IOException {
    try (var doc = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
      for (var week : weeks) week.draw(doc);
      doc.save(bytes);
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
          .header("Content-Disposition", "attachment; filename=stundenplan-wochenraster.pdf")
          .body(bytes.toByteArray());
    }
  }

  private static final int LEFT = 22, TIME = 59, COL = 148, TOP = 505, BOTTOM = 27, LINE = 12;
  private static final int[] FILL = {0xdfeef5, 0xe5f1d9, 0xf7e9db, 0xeee6f3};
  private static final int[] ACCENT = {0x4d849c, 0x83a954, 0xb7764b, 0x9b77aa};
  private static final int WHITE = 0xffffff, GRID = 0xd6dfd8, DARK = 0x223c34;
  private static final PDType1Font NORMAL = PDType1Font.HELVETICA, BOLD = PDType1Font.HELVETICA_BOLD;
  private record TextLine(String text, boolean bold, int color) {}

  private static String printable(String text) {
    var out = new StringBuilder();
    Objects.toString(text, "").codePoints().forEach(cp -> {
      String ch = Character.toString(cp);
      try {
        NORMAL.encode(ch);
        out.append(ch);
      } catch (IllegalArgumentException | IOException ex) {
        out.append(Character.isWhitespace(cp) ? ' ' : '?');
      }
    });
    return out.toString();
  }

  private static List<String> wrap(String text, PDType1Font font, int size, int width) throws IOException {
    var result = new ArrayList<String>();
    String rest = printable(text).strip();
    while (!rest.isEmpty()) {
      int n = 0, space = 0;
      float used = 0;
      while (n < rest.length()) {
        used += font.getStringWidth(rest.substring(n, n + 1)) * size / 1000f;
        if (used > width) break;
        if (rest.charAt(n++) == ' ') space = n;
      }
      int cut = n < rest.length() && space > 0 ? space : Math.max(n, 1);
      result.add(rest.substring(0, cut).strip());
      rest = rest.substring(cut).stripLeading();
    }
    return result;
  }

  private static List<TextLine> lines(List<Card> cards) throws IOException {
    var out = new ArrayList<TextLine>();
    for (var card : cards) {
      if (!out.isEmpty()) out.add(new TextLine("", false, -1));
      for (var line : wrap(card.title(), BOLD, 9, COL - 15)) out.add(new TextLine(line, true, card.color()));
      for (var line : wrap(card.detail(), NORMAL, 8, COL - 15)) out.add(new TextLine(line, false, card.color()));
    }
    return out;
  }

  private static void color(PDPageContentStream ink, int rgb) throws IOException {
    ink.setNonStrokingColor(new java.awt.Color(rgb));
  }

  private static void box(PDPageContentStream ink, int x, int y, int w, int h, int rgb) throws IOException {
    color(ink, rgb);
    ink.addRect(x, y, w, h);
    ink.fill();
  }

  private static void text(PDPageContentStream ink, int x, int y, int size, PDType1Font font, String s)
      throws IOException {
    color(ink, DARK);
    ink.beginText();
    ink.setFont(font, size);
    ink.newLineAtOffset(x, y);
    ink.showText(s);
    ink.endText();
  }

  private PDPageContentStream page(PDDocument doc, int part) throws IOException {
    var page = new PDPage(new PDRectangle(842, 595));
    doc.addPage(page);
    var ink = new PDPageContentStream(doc, page);
    text(ink, LEFT, 558, 18, BOLD, "Wochenstundenplan");
    var titles = wrap(title, NORMAL, 10, 795);
    if (!titles.isEmpty()) text(ink, LEFT, 539, 10, NORMAL, titles.getFirst());
    if (part > 1) text(ink, 713, 558, 10, NORMAL, "Fortsetzung " + part);
    box(ink, LEFT, TOP, TIME + 5 * COL, 25, 0xe9efe8);
    text(ink, LEFT + 6, TOP + 8, 9, BOLD, "Zeit");
    for (int d = 0; d < 5; d++) {
      String label = DAYS.get(d) + (monday == null ? "" : "  " + monday.plusDays(d).format(DATE));
      text(ink, LEFT + TIME + d * COL + 6, TOP + 8, 9, BOLD, label);
    }
    return ink;
  }

  private record Layout(LocalTime start, Row row, List<List<TextLine>> cells, int length) {}

  private void draw(PDDocument doc) throws IOException {
    int part = 1, y = TOP;
    var ink = page(doc, part);
    try {
      if (rows.isEmpty()) text(ink, LEFT + 6, y - 25, 10, NORMAL, "Keine Termine im gewählten Zeitraum.");
      var layout = new ArrayList<Layout>();
      int totalHeight = 0;
      for (var entry : rows.entrySet()) {
        var cells = new ArrayList<List<TextLine>>();
        for (var cards : entry.getValue().cells) cells.add(lines(cards));
        int length = Math.max(2, cells.stream().mapToInt(List::size).max().orElse(0));
        layout.add(new Layout(entry.getKey(), entry.getValue(), cells, length));
        totalHeight += length * LINE + 8;
      }
      int padding = Math.clamp((TOP - BOTTOM - totalHeight) / Math.max(1, rows.size()), 0, 26);
      for (var item : layout) {
        var row = item.row();
        int length = item.length();
        int offset = 0;
        while (offset < length) {
          int remaining = length - offset;
          int capacity = (y - BOTTOM - 8 - padding) / LINE;
          if (capacity < 2 || (remaining > capacity && y != TOP)) {
            ink.close();
            ink = page(doc, ++part);
            y = TOP;
            capacity = (y - BOTTOM - 8 - padding) / LINE;
          }
          int take = Math.min(remaining, capacity), height = Math.max(2, take) * LINE + 8 + padding;
          box(ink, LEFT, y - height, TIME + COL * 5, height, GRID);
          box(ink, LEFT + 1, y - height + 1, TIME - 1, height - 1, 0xf6f8f4);
          String number = row.periods.size() == 1 ? row.periods.iterator().next() + ". Std." : "Beginn";
          text(ink, LEFT + 5, y - 14, 9, BOLD, number);
          text(ink, LEFT + 5, y - 25, 8, NORMAL, item.start().toString());
          for (int d = 0; d < 5; d++) {
            int x = LEFT + TIME + COL * d;
            box(ink, x + 1, y - height + 1, COL - 1, height - 1, WHITE);
            var cell = item.cells().get(d);
            for (int i = offset; i < Math.min(offset + take, cell.size()); i++) {
              var line = cell.get(i);
              if (line.color() < 0) continue;
              int bottom = y - 4 - (i - offset + 1) * LINE;
              box(ink, x + 3, bottom, COL - 6, LINE, FILL[line.color()]);
              box(ink, x + 3, bottom, 2, LINE, ACCENT[line.color()]);
              text(ink, x + 8, bottom + 3, line.bold() ? 9 : 8, line.bold() ? BOLD : NORMAL, line.text());
            }
          }
          y -= height;
          offset += take;
        }
      }
    } finally {
      ink.close();
    }
  }
}
