package at.spps;

import static at.spps.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ParallelWeekTest {
  static Plan languages() {
    var p = Example.blank();
    var c = p.catalog;
    c.groups = List.of(new Group("6A", "6A", 24, "101", List.of("GR", "FR")),
        new Group("6A-GR", "Griechischgruppe", 12, "101", List.of("GR")),
        new Group("6A-FR", "Französischgruppe", 12, "102", List.of("FR")));
    c.subjects = List.of(new Subject("GR", "Griechisch"), new Subject("FR", "Französisch"));
    c.rooms = List.of(new Room("101", "Klassenraum", 24, List.of(), List.of()),
        new Room("102", "Sprachlabor", 12, List.of("Audio"), List.of()));
    c.teachers = List.of(new Teacher("GRL", "Alpha", List.of("GR"), 2, 6, List.of(), List.of()),
        new Teacher("FRL", "Beta", List.of("FR"), 2, 6, List.of(), List.of()));
    c.courses = List.of(new Course("gr", "GR", List.of("6A-GR"), List.of(), 2, 1, "A",
            List.of(), List.of(), "Sprachen"),
        new Course("fr", "FR", List.of("6A-FR"), List.of(), 2, 1, "A",
            List.of("Audio"), List.of(), "Sprachen"));
    return p;
  }

  @Test
  void solverPairsEachOccurrenceAndEnforcesDifferentRooms() {
    var p = languages();
    var e = Engine.inspect(p);
    for (int i = 1; i <= 2; i++) {
      var gr = e.lessons.get("gr#" + i);
      var fr = e.lessons.get("fr#" + i);
      var pair = Solver.parallelChoices(e, List.of(gr, fr), null, null, null, new Random(3),
          Long.MAX_VALUE, new AtomicBoolean());
      assertEquals(2, pair.size());
      var g = pair.get(gr);
      var f = pair.get(fr);
      assertEquals(g.day(), f.day());
      assertEquals(g.slot(), f.slot());
      assertEquals("102", f.room());
      assertEquals("101", g.room());
      e.apply(gr, g);
      assertNotNull(e.conflict(fr, new Place(g.day() % 5 + 1, g.slot(), f.room(), f.teachers(), false)));
      assertNull(e.conflict(fr, f));
      e.apply(fr, f);
      pair.forEach((u, place) -> p.places.put(u.id(), place));
    }
    assertEquals(4, Engine.inspect(p).metrics(p).placed());
    p.catalog.rooms = p.catalog.rooms.subList(0, 1);
    p.places.clear();
    var withoutLab = Engine.inspect(p);
    assertTrue(Solver.parallelChoices(withoutLab, List.of(e.lessons.get("gr#1"), e.lessons.get("fr#1")),
        null, null, null, new Random(1), Long.MAX_VALUE, new AtomicBoolean()).isEmpty());
  }

  @Test
  void conflictingPupilsAndMismatchedRhythmsAreRejected() {
    var p = languages();
    p.catalog.students = List.of(new Student("S", "Alex", List.of("6A-GR", "6A-FR")));
    assertThrows(IllegalArgumentException.class, () -> Engine.inspect(p));
    p.catalog.students = List.of();
    p.catalog.courses = List.of(p.catalog.courses.getFirst(), new Course("fr", "FR", List.of("6A-FR"),
        List.of(), 2, 1, "B", List.of(), List.of(), "Sprachen"));
    assertThrows(IllegalArgumentException.class, () -> Engine.inspect(p));
  }

  @Test
  void pdfIncludesBothHalvesAndEmptyPeriodsAndPaginatesDenseGrids() throws Exception {
    var p = languages();
    p.places.put("gr#1", new Place(1, 0, "101", List.of("GRL"), false));
    p.places.put("fr#1", new Place(1, 0, "102", List.of("FRL"), false));
    var scenario = new Store.Scenario(UUID.randomUUID(), "Sprachwahl", "DRAFT", 0, 0, p);
    var sheet = WeekSheet.scenario(scenario, "A", "6A", "", "", "");
    var monday = sheet.row(LocalTime.of(8, 0)).cells.getFirst();
    assertEquals(2, monday.size());
    assertTrue(WeekSheet.scenario(scenario, "B", "6A", "", "", "").rows.values()
        .stream().flatMap(row -> row.cells.stream()).allMatch(List::isEmpty));
    assertEquals(1, WeekSheet.scenario(scenario, "A", "6A", "", "102", "").rows.get(LocalTime.of(8, 0))
        .cells.getFirst().size());
    try (var pdf = PDDocument.load(WeekSheet.download(List.of(sheet)).getBody())) {
      assertEquals(1, pdf.getNumberOfPages());
      var text = new PDFTextStripper().getText(pdf);
      for (String label : List.of("Montag", "Freitag", "8. Std.", "08:00", "Griechisch", "Französisch",
          "101", "102", "GRL", "FRL")) assertTrue(text.contains(label), label);
    }
    for (int i = 0; i < 70; i++) monday.add(new WeekSheet.Card("Kurs " + i, "Gruppe", 1));
    try (var pdf = PDDocument.load(WeekSheet.download(List.of(sheet)).getBody())) {
      assertTrue(pdf.getNumberOfPages() > 1);
      assertTrue(new PDFTextStripper().getText(pdf).contains("Kurs 69"));
    }
  }
}
