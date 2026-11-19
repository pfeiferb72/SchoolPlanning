package at.spps;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class Model {
  private Model() {}

  public record Slot(LocalTime start, LocalTime end) {}

  public record Window(int day, int slot, String week) {}

  public record Teacher(
      String id,
      String name,
      List<String> subjects,
      int load,
      int daily,
      List<Window> blocked,
      List<Window> wishes,
      String employment,
      LocalDate employedFrom,
      LocalDate employedUntil) {
    public Teacher(
        String id,
        String name,
        List<String> subjects,
        int load,
        int daily,
        List<Window> blocked,
        List<Window> wishes) {
      this(id, name, subjects, load, daily, blocked, wishes, "", null, null);
    }
  }

  public record Group(String id, String name, int size, String home, List<String> cohorts) {}

  public record Room(
      String id, String name, int capacity, List<String> equipment, List<Window> blocked) {}

  public record Subject(String id, String name) {}

  public record Student(String id, String name, List<String> groups) {}

  public record Course(
      String id,
      String subject,
      List<String> groups,
      List<String> teachers,
      int hours,
      int block,
      String week,
      List<String> equipment,
      List<String> resources) {}

  public record Booking(
      String id, String resource, LocalDate from, LocalDate until, int first, int last) {}

  public record Holiday(String name, LocalDate from, LocalDate until) {}

  public record Period(String id, String name, LocalDate from, LocalDate until) {}

  public record Exam(
      String id,
      String name,
      String period,
      LocalDate date,
      LocalTime start,
      LocalTime end,
      String room,
      List<String> teachers,
      List<String> candidates) {}

  public record Weight(int wishes, int spread, int gaps, int late, int home, int morning) {}

  public record Place(int day, int slot, String room, List<String> teachers, boolean fixed) {}

  public record Unit(String id, Course course) {}

  public static class Catalog {
    public String schoolType = "AHS", year = "2026/27", semester = "Wintersemester";
    public LocalDate from = LocalDate.of(2026, 9, 14), until = LocalDate.of(2027, 2, 5);
    public List<Slot> slots = new ArrayList<>();
    public int maxConsecutive = 8, breakMinutes = 15;
    public Weight weights = new Weight(3, 2, 2, 1, 1, 1);
    public List<Window> blocked = new ArrayList<>();
    public List<Holiday> holidays = new ArrayList<>();
    public List<Subject> subjects = new ArrayList<>();
    public List<Teacher> teachers = new ArrayList<>();
    public List<Group> groups = new ArrayList<>();
    public List<Room> rooms = new ArrayList<>();
    public List<Student> students = new ArrayList<>();
    public List<Course> courses = new ArrayList<>();
    public List<String> resources = new ArrayList<>();
    public List<Booking> bookings = new ArrayList<>();
    public List<Period> periods = new ArrayList<>();
    public List<Exam> exams = new ArrayList<>();
    public List<String> coverOrder =
        new ArrayList<>(List.of("Fachvertretung", "Betreuung", "Zusammenlegung", "Ausfall"));
    public boolean balanceCover = true, preferRemainingLoad = true;
  }

  public static class Plan {
    public Catalog catalog = new Catalog();
    public Map<String, Place> places = new LinkedHashMap<>();
  }

  static boolean active(String rhythm, int week) {
    return "AB".equals(rhythm) || rhythm.equals(week == 0 ? "A" : "B");
  }

  static int week(Catalog c, LocalDate date) {
    LocalDate monday = c.from.minusDays(c.from.getDayOfWeek().getValue() - 1);
    return Math.floorMod(ChronoUnit.WEEKS.between(monday, date), 2);
  }

  static boolean teachingDate(Catalog c, LocalDate date) {
    return !date.isBefore(c.from)
        && !date.isAfter(c.until)
        && date.getDayOfWeek().getValue() <= 5
        && c.holidays.stream().noneMatch(h -> !date.isBefore(h.from) && !date.isAfter(h.until));
  }

  static List<Unit> units(Catalog c) {
    var out = new ArrayList<Unit>();
    for (var item : c.courses)
      for (int i = 1; i <= item.hours / item.block; i++) out.add(new Unit(item.id + "#" + i, item));
    return out;
  }

  static void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }

  static <T> Map<String, T> index(List<T> rows, java.util.function.Function<T, String> key) {
    var out = new LinkedHashMap<String, T>();
    for (T row : rows) {
      String id = key.apply(row);
      require(id != null && id.matches("[\\p{L}\\p{N}_.-]{1,80}"), "Ungültiges Kürzel: " + id);
      require(out.putIfAbsent(id, row) == null, "Doppeltes Kürzel: " + id);
    }
    return out;
  }
}
