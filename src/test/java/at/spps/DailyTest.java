package at.spps;

import static at.spps.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DailyTest {
  @Test
  void qualifiedBeforeSupervisionAndCancelledFallback() {
    var p = Example.school();
    var c = p.catalog;
    var staff = new ArrayList<>(c.teachers);
    staff.add(new Teacher("MAT", "Mathematikreserve", List.of("M"), 0, 6, List.of(), List.of()));
    c.teachers = staff;
    var roster = new Daily.Roster(p, c.from, Set.of("WEB"), new HashMap<>());
    var options = roster.options("M-1A#1", "WEB");
    assertEquals("MAT", options.getFirst().replacement());
    assertEquals("Fachvertretung", options.getFirst().kind());
    assertTrue(options.stream().noneMatch(r -> r.replacement().equals("BER")));
    assertEquals("Ausfall", options.getLast().kind());
    roster.assign(options.getFirst());
    assertTrue(
        roster.options("M-1A#1", "WEB").stream().noneMatch(r -> r.replacement().equals("MAT")));
  }

  @Test
  void mergeRequiresCapacityAndAvailableTarget() {
    var p = Example.school();
    var c = p.catalog;
    c.rooms = new ArrayList<>(c.rooms);
    c.rooms.set(1, new Room("102", "Großraum", 50, List.of(), List.of()));
    c.courses = new ArrayList<>(c.courses);
    c.courses.add(
        new Course(
            "Reserve", "M", List.of("1B"), List.of("HUB"), 1, 1, "AB", List.of(), List.of()));
    p.places.put("Reserve#1", new Place(1, 0, "102", List.of("HUB"), false));
    p.places.remove("D-1B#1");
    c.coverOrder = List.of("Zusammenlegung", "Ausfall");
    var roster = new Daily.Roster(p, c.from, Set.of("WEB"), new HashMap<>());
    var option = roster.options("M-1A#1", "WEB").getFirst();
    assertEquals("Zusammenlegung", option.kind());
    assertEquals("Reserve#1", option.target());
    roster.assign(option);
    assertEquals("Ausfall", roster.options("M-1A#1", "WEB").getFirst().kind());
    var other = new Daily.Roster(p, c.from, Set.of("WEB", "HUB"), new HashMap<>());
    assertEquals("Ausfall", other.options("M-1A#1", "WEB").getFirst().kind());
  }

  @Test
  void holidayAndDateRange() {
    var p = Example.school();
    var c = p.catalog;
    c.holidays = List.of(new Holiday("frei", c.from, c.from));
    assertTrue(new Daily.Roster(p, c.from, Set.of("WEB"), new HashMap<>()).today.isEmpty());
    assertTrue(
        new Daily.Roster(p, c.until.plusWeeks(1), Set.of("WEB"), new HashMap<>()).today.isEmpty());
  }

  @Test
  void calendarUsesViennaOffsetAndSupportsSpecialCharacters() {
    assertEquals("20261026T070000Z", Exports.utc(LocalDate.of(2026, 10, 26), LocalTime.of(8, 0)));
    assertEquals("20261019T060000Z", Exports.utc(LocalDate.of(2026, 10, 19), LocalTime.of(8, 0)));
    String longText = "Ü".repeat(105) + ",Gruppe";
    var event =
        new Views.Event(
            "m-1",
            LocalDate.of(2026, 10, 19),
            LocalTime.of(8, 0),
            LocalTime.of(8, 50),
            longText,
            List.of("A"),
            List.of("T"),
            "101",
            "Ausfall");
    String calendar = Exports.ics(List.of(event), "school");
    assertTrue(calendar.contains("STATUS:CANCELLED"));
    assertTrue(calendar.replace("\r\n ", "").contains("\\,Gruppe"));
    for (String line : calendar.split("\r\n"))
      assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75);
  }

  @Test
  void invalidSchoolMasterDataRejected() {
    var c = Example.school().catalog;
    c.bookings =
        List.of(
            new Booking("a", "101", c.from, c.until, 0, 3),
            new Booking("b", "101", c.from, c.until, 2, 4));
    assertThrows(IllegalArgumentException.class, () -> CatalogRules.validate(c));
    c.bookings = List.of();
    var exam =
        new Exam(
            "x",
            "Prüfung",
            "W",
            c.from,
            LocalTime.of(8, 0),
            LocalTime.of(9, 0),
            "101",
            List.of("WEB"),
            List.of("S01"));
    c.exams = List.of(exam);
    c.blocked = List.of(new Window(1, 0, "A"));
    assertThrows(IllegalArgumentException.class, () -> CatalogRules.validate(c));
    c.blocked = List.of();
    c.bookings = List.of(new Booking("x", "101", c.from, c.from, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> CatalogRules.validate(c));
  }
}
