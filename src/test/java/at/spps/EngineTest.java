package at.spps;

import static at.spps.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineTest {
  Plan p;
  Catalog c;
  Course unit;

  @BeforeEach
  void setup() {
    p = Example.school();
    p.places.clear();
    c = p.catalog;
    unit = c.courses.getFirst();
  }

  Place at(int d, int s, String room, String teacher) {
    return new Place(d, s, room, List.of(teacher), false);
  }

  Engine engine() {
    return Engine.inspect(p);
  }

  String problem(Engine e, String lesson, Place at) {
    return e.conflict(e.lessons.get(lesson), at);
  }

  @Test
  void resourceAndTeacherConflictsAndBoundaries() {
    var e = engine();
    var u = e.lessons.get("M-1A#1");
    var first = at(1, 0, "101", "WEB");
    assertNull(e.conflict(u, first));
    e.apply(u, first);
    assertNotNull(problem(e, "M-1B#1", at(1, 0, "102", "WEB")));
    assertNotNull(problem(e, "D-1B#1", at(1, 0, "101", "BER")));
    assertNotNull(problem(e, "D-1A#1", at(1, 0, "102", "BER")));
    assertNotNull(problem(e, "BI-1A#1", at(1, 7, "LAB", "HUB")));
    assertNotNull(problem(e, "BI-1A#1", at(2, 0, "101", "HUB")));
    assertNotNull(problem(e, "M-1B#1", at(0, 0, "102", "WEB")));
    assertNotNull(problem(e, "M-1B#1", at(1, -1, "102", "WEB")));
    assertNotNull(problem(e, "M-1B#1", at(2, 0, "102", "BER")));
    assertTrue(e.metrics(p).outstanding().size() > 1);
  }

  @Test
  void dailyAndConsecutiveAndAvailability() {
    c.teachers = new ArrayList<>(c.teachers);
    c.teachers.set(
        0,
        new Teacher(
            "WEB",
            "Anna",
            List.of("M"),
            6,
            3,
            List.of(new Window(3, 1, "B")),
            List.of(new Window(1, 0, "AB"))));
    c.blocked = List.of(new Window(4, 0, "A"));
    var e = engine();
    var u = e.lessons.get("M-1A#1");
    e.apply(u, at(1, 0, "101", "WEB"));
    assertNotNull(e.conflict(u, at(1, 3, "101", "WEB")));
    assertNotNull(e.conflict(u, at(3, 1, "101", "WEB")));
    assertNotNull(e.conflict(u, at(4, 0, "101", "WEB")));
    assertTrue(e.delta(u, at(2, 1, "101", "WEB")).wishes() > 0);
    c.maxConsecutive = 1;
    assertNotNull(e.conflict(u, at(1, 1, "101", "WEB")));
    assertNull(e.conflict(u, at(1, 2, "101", "WEB")));
    assertEquals(3, e.run(7));
    assertEquals(3, e.run(15));
  }

  @Test
  void splitCouplingAndCycles() {
    c.groups =
        List.of(
            new Group("A", "Ganz", 20, "101", List.of("L", "R")),
            new Group("L", "Links", 10, "101", List.of("L")),
            new Group("R", "Rechts", 10, "102", List.of("R")));
    c.students = List.of();
    c.courses =
        List.of(
            new Course("L", "M", List.of("L"), List.of("WEB"), 1, 1, "A", List.of(), List.of()),
            new Course("R", "D", List.of("R"), List.of("BER"), 1, 1, "A", List.of(), List.of()),
            new Course("ALL", "BI", List.of("A"), List.of("HUB"), 1, 1, "AB", List.of(), List.of()),
            new Course(
                "B", "M", List.of("L", "R"), List.of("WEB"), 1, 1, "B", List.of(), List.of()));
    var e = engine();
    e.apply(e.lessons.get("L#1"), at(1, 1, "101", "WEB"));
    assertNull(problem(e, "R#1", at(1, 1, "102", "BER")));
    assertNull(problem(e, "B#1", at(1, 1, "101", "WEB")));
    assertNotNull(problem(e, "ALL#1", at(1, 1, "LAB", "HUB")));
    assertEquals(1, e.load.get("WEB"));
  }

  @Test
  void bookingsExamsAndCandidates() {
    c.bookings = List.of(new Booking("reserve", "101", c.from, c.from, 1, 2));
    c.exams =
        List.of(
            new Exam(
                "oral",
                "Mündlich",
                "W",
                c.from,
                LocalTime.of(8, 0),
                LocalTime.of(8, 30),
                "LAB",
                List.of("HUB"),
                List.of("S01")));
    var e = engine();
    assertNotNull(problem(e, "M-1A#1", at(1, 0, "102", "WEB")));
    assertNotNull(problem(e, "D-1B#1", at(1, 0, "LAB", "BER")));
    assertNotNull(problem(e, "D-1B#1", at(1, 1, "101", "BER")));
    assertNull(problem(e, "M-1A#1", at(2, 0, "102", "WEB")));
    c.exams =
        List.of(
            c.exams.getFirst(),
            new Exam(
                "oral2",
                "Mündlich",
                "W",
                c.from,
                LocalTime.of(8, 10),
                LocalTime.of(9, 0),
                "102",
                List.of("BER"),
                List.of("S01")));
    assertThrows(IllegalArgumentException.class, this::engine);
  }

  @Test
  void loadTeamsDevicesAndScores() {
    c.teachers =
        List.of(
            new Teacher("T1", "A", List.of("M"), 2, 5, List.of(), List.of()),
            new Teacher("T2", "B", List.of("M"), 1, 5, List.of(), List.of()),
            new Teacher("T3", "C", List.of("D"), 2, 5, List.of(), List.of()));
    c.resources = List.of("Wagen");
    c.courses =
        List.of(
            new Course(
                "T",
                "M",
                List.of("1A"),
                List.of("T1", "T2"),
                2,
                1,
                "AB",
                List.of(),
                List.of("Wagen")),
            new Course(
                "D", "D", List.of("1B"), List.of(), 2, 1, "AB", List.of(), List.of("Wagen")));
    var e = engine();
    var u = e.lessons.get("T#1");
    assertNotNull(e.conflict(u, at(1, 0, "101", "T1")));
    var team = new Place(1, 0, "101", List.of("T2", "T1"), false);
    assertNull(e.conflict(u, team));
    e.apply(u, team);
    assertNotNull(e.conflict(u, new Place(2, 0, "101", List.of("T2", "T1"), false)));
    assertNotNull(problem(e, "D#1", at(1, 0, "102", "T3")));
    assertNotNull(problem(e, "D#1", at(2, 0, "102", "T1")));
    assertNotNull(problem(e, "D#1", at(2, 0, "102", "absent")));
    assertNotNull(problem(e, "D#1", new Place(1, 0, "101", List.of(), false)));
    assertTrue(e.delta(u, new Place(1, 7, "102", List.of("T2", "T1"), false)).total() > 0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"VS", "MS", "AHS", "BHS/BMS", "BS"})
  void referenceTimetables(String type) {
    c.schoolType = type;
    switch (type) {
      case "VS" -> c.maxConsecutive = 3;
      case "MS" -> c.blocked = List.of(new Window(1, 0, "AB"));
      case "BHS/BMS" -> {
        c.resources = List.of("Werkzeug");
        var a = c.courses.getFirst();
        c.courses.set(
            0,
            new Course(
                a.id(),
                a.subject(),
                a.groups(),
                a.teachers(),
                a.hours(),
                a.block(),
                a.week(),
                a.equipment(),
                c.resources));
      }
      case "BS" -> {
        c.bookings = List.of(new Booking("R1", "101", c.from, c.from.plusWeeks(4), 0, 1));
      }
    }
    var e = engine();
    var lessons = new ArrayList<>(e.lessons.values());
    lessons.sort(Comparator.comparingInt((Unit u) -> u.course().block()).reversed());
    for (var u : lessons) {
      var places =
          Solver.choices(e, u, null, null, new Random(17), Long.MAX_VALUE, new AtomicBoolean(), 1);
      assertFalse(places.isEmpty());
      var at = places.getFirst();
      e.apply(u, at);
      p.places.put(u.id(), at);
    }
    var checked = engine().metrics(p);
    assertTrue(checked.outstanding().isEmpty());
    assertTrue(checked.score() <= 60);
  }

  @Test
  void holidaysAndISOWeek() {
    assertEquals(0, week(c, c.from));
    assertEquals(1, week(c, c.from.plusWeeks(1)));
    c.holidays = List.of(new Holiday("Frei", c.from, c.from));
    assertFalse(teachingDate(c, c.from));
    assertFalse(teachingDate(c, c.from.plusDays(5)));
  }
}
