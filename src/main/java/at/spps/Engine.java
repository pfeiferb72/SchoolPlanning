package at.spps;

import static at.spps.Model.*;

import java.time.*;
import java.util.*;

/** Occupancy is indexed by resource, ISO weekday and A/B cycle. Slots are bit sets (max. 24). */
final class Engine {
  record Day(String resource, int week, int day) {}

  record Score(int wishes, int spread, int gaps, int late, int home, int morning) {
    int total() {
      return wishes + spread + gaps + late + home + morning;
    }

    Score plus(Score s) {
      return new Score(
          wishes + s.wishes,
          spread + s.spread,
          gaps + s.gaps,
          late + s.late,
          home + s.home,
          morning + s.morning);
    }
  }

  record Metrics(int total, int placed, List<String> outstanding, Score penalties, int score) {}

  final Catalog c;
  final Map<String, Teacher> teachers;
  final Map<String, Group> groups;
  final Map<String, Room> rooms;
  final Map<String, Unit> lessons = new LinkedHashMap<>();
  final Map<String, Place> bandTimes = new HashMap<>();
  final Map<String, List<Room>> suitableRooms = new HashMap<>();
  final Map<String, List<String>> classKeys = new HashMap<>();
  final Map<Day, Integer> occupied = new HashMap<>(),
      unavailable = new HashMap<>(),
      subjectHours = new HashMap<>();
  final Map<String, Integer> load = new HashMap<>();
  Score penalties = new Score(0, 0, 0, 0, 0, 0);

  Engine(Catalog c) {
    this.c = c;
    teachers = index(c.teachers, Teacher::id);
    groups = index(c.groups, Group::id);
    rooms = index(c.rooms, Room::id);
    for (var u : units(c)) lessons.put(u.id(), u);
    for (var a : c.courses) {
      int size = a.groups().stream().mapToInt(g -> groups.get(g).size()).sum();
      suitableRooms.put(
          a.id(),
          c.rooms.stream()
              .filter(r -> r.capacity() >= size && r.equipment().containsAll(a.equipment()))
              .toList());
      Set<String> keys = new LinkedHashSet<>();
      for (var g : a.groups()) groups.get(g).cohorts().forEach(x -> keys.add("G:" + x));
      for (var s : c.students)
        if (!Collections.disjoint(s.groups(), a.groups())) keys.add("S:" + s.id());
      classKeys.put(a.id(), List.copyOf(keys));
    }
    for (var b : c.bookings) {
      LocalDate start = b.from().isBefore(c.from) ? c.from : b.from(),
          end = b.until().isAfter(c.until) ? c.until : b.until();
      for (var d = start; !d.isAfter(end); d = d.plusDays(1))
        if (teachingDate(c, d))
          reserve(
              rooms.containsKey(b.resource()) ? "R:" + b.resource() : "E:" + b.resource(),
              d,
              mask(b.first(), b.last() - b.first() + 1));
    }
    for (var e : c.exams) {
      int m = 0;
      for (int i = 0; i < c.slots.size(); i++)
        if (CatalogRules.overlap(c.slots.get(i).start(), c.slots.get(i).end(), e.start(), e.end()))
          m |= 1 << i;
      reserve("R:" + e.room(), e.date(), m);
      for (String t : e.teachers()) reserve("T:" + t, e.date(), m);
      for (String s : e.candidates()) reserve("S:" + s, e.date(), m);
    }
  }

  void reserve(String resource, LocalDate date, int mask) {
    unavailable.merge(
        new Day(resource, week(c, date), date.getDayOfWeek().getValue()), mask, (a, b) -> a | b);
  }

  static int mask(int slot, int length) {
    return ((1 << length) - 1) << slot;
  }

  static boolean blocked(List<Window> ws, int day, int slot, int week) {
    return ws.stream().anyMatch(w -> w.day() == day && w.slot() == slot && active(w.week(), week));
  }

  static int span(int bits) {
    return bits == 0
        ? 0
        : 32 - Integer.numberOfLeadingZeros(bits) - Integer.numberOfTrailingZeros(bits);
  }

  int run(int bits) {
    int longest = 0, count = 0;
    for (int s = 0; s < c.slots.size(); s++) {
      if (s > 0
          && Duration.between(c.slots.get(s - 1).end(), c.slots.get(s).start()).toMinutes()
              >= c.breakMinutes) count = 0;
      count = (bits & (1 << s)) == 0 ? 0 : count + 1;
      longest = Math.max(longest, count);
    }
    return longest;
  }

  List<String> keys(Course a, Place p) {
    List<String> out = new ArrayList<>(classKeys.get(a.id()));
    out.add("R:" + p.room());
    p.teachers().forEach(t -> out.add("T:" + t));
    a.resources().forEach(r -> out.add("E:" + r));
    return out;
  }

  int cycleLoad(Course a) {
    return a.block() * (a.week().equals("AB") ? 2 : 1);
  }

  String conflict(Unit u, Place p) {
    Course a = u.course();
    if (p.day() < 1 || p.day() > 5 || p.slot() < 0 || p.slot() + a.block() > c.slots.size())
      return "Außerhalb des Zeitrasters";
    Place partner = bandTimes.get(u.bandBlock());
    if (partner != null && (p.day() != partner.day() || p.slot() != partner.slot()))
      return "Parallelband " + a.parallelBand() + " muss zur selben Zeit stattfinden";
    Room r = rooms.get(p.room());
    if (r == null
        || r.capacity() < a.groups().stream().mapToInt(g -> groups.get(g).size()).sum()
        || !r.equipment().containsAll(a.equipment()))
      return "Raumgröße oder Ausstattung ungeeignet";
    if (p.teachers().isEmpty()
        || !CatalogRules.distinct(p.teachers())
        || (!a.teachers().isEmpty()
            && !new HashSet<>(p.teachers()).equals(new HashSet<>(a.teachers()))))
      return "Lehrerzuweisung passt nicht zum Kurs";
    for (String t : p.teachers()) {
      if (!teachers.containsKey(t) || !teachers.get(t).subjects().contains(a.subject()))
        return "Fehlende Fachqualifikation: " + t;
      if ((teachers.get(t).employedFrom() != null && teachers.get(t).employedFrom().isAfter(c.from))
          || (teachers.get(t).employedUntil() != null
              && teachers.get(t).employedUntil().isBefore(c.until)))
        return "Anstellung deckt das Semester nicht ab: " + t;
      if (load.getOrDefault(t, 0) + cycleLoad(a) > teachers.get(t).load() * 2)
        return "Lehrverpflichtung überschritten: " + t;
    }
    int m = mask(p.slot(), a.block());
    var all = keys(a, p);
    for (int w = 0; w < 2; w++)
      if (active(a.week(), w)) {
        for (int s = p.slot(); s < p.slot() + a.block(); s++) {
          if (blocked(c.blocked, p.day(), s, w) || blocked(r.blocked(), p.day(), s, w))
            return "Schul- oder Raumsperre";
          for (String t : p.teachers())
            if (blocked(teachers.get(t).blocked(), p.day(), s, w))
              return "Lehrkraft gesperrt: " + t;
        }
        for (String k : all) {
          var day = new Day(k, w, p.day());
          int bits = occupied.getOrDefault(day, 0);
          if (((bits | unavailable.getOrDefault(day, 0)) & m) != 0)
            return "Doppelbelegung oder Buchung: " + k;
          if (k.startsWith("T:") && span(bits | m) > teachers.get(k.substring(2)).daily())
            return "Tägliche Präsenzgrenze: " + k.substring(2);
          if ((k.startsWith("T:") || k.startsWith("G:")) && run(bits | m) > c.maxConsecutive)
            return "Zu viele Einheiten ohne Mindestpause";
        }
      }
    return null;
  }

  Score delta(Unit u, Place p) {
    var a = u.course();
    var wt = c.weights;
    int wishes = 0, spread = 0, gaps = 0, late = 0, home = 0, morning = 0;
    int m = mask(p.slot(), a.block());
    for (int w = 0; w < 2; w++)
      if (active(a.week(), w)) {
        for (String t : p.teachers()) {
          var wants = teachers.get(t).wishes();
          for (int s = p.slot(); !wants.isEmpty() && s < p.slot() + a.block(); s++)
            if (!blocked(wants, p.day(), s, w)) wishes++;
        }
        for (String g : a.groups()) {
          var group = groups.get(g);
          if (group.home() != null && !group.home().isBlank() && !group.home().equals(p.room()))
            home++;
          if (subjectHours.containsKey(new Day(g + "/" + a.subject(), w, p.day()))) spread++;
          for (String cohort : group.cohorts()) {
            int before = occupied.getOrDefault(new Day("G:" + cohort, w, p.day()), 0);
            gaps += span(before | m) - span(before) - a.block();
          }
        }
        if (p.slot() + a.block() == c.slots.size()) late++;
        if (!c.slots.get(p.slot()).start().isBefore(LocalTime.NOON)) morning++;
      }
    return new Score(
        wishes * wt.wishes(),
        spread * wt.spread(),
        gaps * wt.gaps(),
        late * wt.late(),
        home * wt.home(),
        morning * wt.morning());
  }

  void apply(Unit u, Place p) {
    var a = u.course();
    if (!u.bandBlock().isEmpty()) bandTimes.putIfAbsent(u.bandBlock(), p);
    penalties = penalties.plus(delta(u, p));
    for (String t : p.teachers()) load.merge(t, cycleLoad(a), Integer::sum);
    for (int w = 0; w < 2; w++)
      if (active(a.week(), w)) {
        for (String key : keys(a, p))
          occupied.merge(new Day(key, w, p.day()), mask(p.slot(), a.block()), (x, y) -> x | y);
        for (String g : a.groups())
          subjectHours.merge(new Day(g + "/" + a.subject(), w, p.day()), 1, Integer::sum);
      }
  }

  static Engine inspect(Plan plan) {
    CatalogRules.validate(plan.catalog);
    var e = new Engine(plan.catalog);
    plan.places.forEach(
        (id, p) -> {
          var unit = e.lessons.get(id);
          require(unit != null, "Unbekannte Unterrichtsstunde: " + id);
          var message = e.conflict(unit, p);
          require(message == null, id + " · " + message);
          e.apply(unit, p);
        });
    return e;
  }

  Metrics metrics(Plan plan) {
    var issues = new ArrayList<String>();
    if (plan.places.size() != lessons.size())
      issues.add((lessons.size() - plan.places.size()) + " offene Unterrichtsblöcke");
    for (var t : c.teachers)
      if (load.getOrDefault(t.id(), 0) != t.load() * 2)
        issues.add(
            t.name()
                + ": "
                + load.getOrDefault(t.id(), 0) / 2.0
                + " von "
                + t.load()
                + " Wochenstunden");
    if (lessons.isEmpty()) issues.add("Keine Unterrichtsveranstaltungen");
    return new Metrics(lessons.size(), plan.places.size(), issues, penalties, penalties.total());
  }
}
