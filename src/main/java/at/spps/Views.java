package at.spps;

import static at.spps.Model.*;
import static at.spps.Security.Member;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
class Views {
  record Event(
      String uid,
      LocalDate date,
      LocalTime start,
      LocalTime end,
      String subject,
      List<String> groups,
      List<String> teachers,
      String room,
      String status) {}

  final Store store;

  Views(Store s) {
    store = s;
  }

  List<Event> events(Member m, LocalDate from, LocalDate until, String scope, String entity) {
    return events(m, from, until, scope, entity, null);
  }

  Collection<WeekSheet> weeks(Member m, LocalDate from, LocalDate until, String scope, String entity) {
    CatalogRules.range(from, until);
    require(!until.isAfter(from.plusYears(2)), "Zeitraum zu lang");
    var sheets = new LinkedHashMap<LocalDate, WeekSheet>();
    for (var monday = WeekSheet.monday(from); !monday.isAfter(until); monday = monday.plusWeeks(1)) {
      String title = "Woche ab " + monday.format(WeekSheet.DATE) + (entity.isBlank() ? "" : " · " + entity);
      sheets.put(monday, new WeekSheet(title, monday));
    }
    events(m, from, until, scope, entity, sheets);
    return sheets.values();
  }

  private List<Event> events(Member m, LocalDate from, LocalDate until, String scope, String entity,
      Map<LocalDate, WeekSheet> sheets) {
    CatalogRules.range(from, until);
    require(!until.isAfter(from.plusYears(2)), "Zeitraum zu lang");
    var pubs =
        store
            .database()
            .query(
                "select id,document from publication where school_id=? and"
                    + " (document->'catalog'->>'from')::date<=? and"
                    + " (document->'catalog'->>'until')::date>=? order by id desc",
                (r, n) -> new Store.Published(r.getLong(1), store.decode(r.getString(2))),
                m.school(),
                until,
                from);
    var out = new ArrayList<Event>();
    var covered = new HashSet<LocalDate>();
    for (var pub : pubs) {
      var plan = pub.plan();
      var c = plan.catalog;
      var engine = new Engine(c);
      var student = c.students.stream().filter(x -> x.id().equals(m.entity())).findFirst();
      var byDay = new HashMap<String, List<Daily.Replacement>>();
      store
          .database()
          .query(
              "select day,lesson,missing,kind,replacement,target from cover where publication_id=?"
                  + " and day between ? and ?",
              r -> {
                var v =
                    new Daily.Replacement(
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        r.getString(5),
                        r.getString(6));
                byDay
                    .computeIfAbsent(r.getString(1) + "/" + v.lesson(), k -> new ArrayList<>())
                    .add(v);
              },
              pub.id(),
              from,
              until);
      for (var date = from.isAfter(c.from) ? from : c.from;
          !date.isAfter(until) && !date.isAfter(c.until);
          date = date.plusDays(1)) {
        if (!covered.add(date)) continue;
        var sheet = sheets == null ? null : sheets.get(WeekSheet.monday(date));
        if (sheet != null) sheet.timetable(c);
        if (!teachingDate(c, date)) continue;
        int w = week(c, date), day = date.getDayOfWeek().getValue();
        for (var x : plan.places.entrySet()) {
          var p = x.getValue();
          var a = engine.lessons.get(x.getKey()).course();
          if (p.day() != day || !active(a.week(), w)) continue;
          var teachers = new ArrayList<>(p.teachers());
          String room = p.room(), status = "Unterricht";
          for (var v : byDay.getOrDefault(date + "/" + x.getKey(), List.of())) {
            teachers.remove(v.missing());
            if (!v.replacement().isBlank()) teachers.add(v.replacement());
            if (!status.equals("Ausfall")) status = v.kind();
            if (!v.target().isBlank()) room = plan.places.get(v.target()).room();
          }
          if (!visible(engine.groups, m, a.groups(), teachers, p.teachers(), student)) continue;
          var event =
              new Event(
                  x.getKey() + "-" + date,
                  date,
                  c.slots.get(p.slot()).start(),
                  c.slots.get(p.slot() + a.block() - 1).end(),
                  a.subject(),
                  a.groups(),
                  teachers,
                  room,
                  status);
          if (matches(engine.groups, event, scope, entity)) {
            if (sheet == null) out.add(event);
            else sheet.lesson(c, a, p, teachers, room, status);
          }
        }
        for (var e : c.exams)
          if (e.date().equals(date)) {
            var examGroups =
                c.students.stream()
                    .filter(s -> e.candidates().contains(s.id()))
                    .flatMap(s -> s.groups().stream())
                    .distinct()
                    .toList();
            if ((m.role().equals("STUDENT") || m.role().equals("GUARDIAN"))
                && !e.candidates().contains(m.entity())) continue;
            if (!visible(engine.groups, m, examGroups, e.teachers(), e.teachers(), student)) continue;
            var event =
                new Event(
                    "exam-" + e.id() + "-" + date,
                    date,
                    e.start(),
                    e.end(),
                    e.name(),
                    examGroups,
                    e.teachers(),
                    e.room(),
                    "Prüfung");
            if (matches(engine.groups, event, scope, entity)) {
              if (sheet == null) out.add(event);
              else sheet.exam(event);
            }
          }
      }
    }
    out.sort(
        Comparator.comparing(Event::date).thenComparing(Event::start).thenComparing(Event::uid));
    return out;
  }

  boolean visible(
      Map<String, Group> known, Member m,
      List<String> groups,
      List<String> teachers,
      List<String> original,
      Optional<Student> student) {
    return switch (m.role()) {
      case "TEACHER" -> teachers.contains(m.entity()) || original.contains(m.entity());
      case "CURATOR" -> groupMatches(known, m.entity(), groups);
      case "STUDENT", "GUARDIAN" ->
          student.isPresent() && !Collections.disjoint(groups, student.get().groups());
      default -> m.full();
    };
  }

  boolean matches(Map<String, Group> known, Event e, String scope, String entity) {
    if (entity == null || entity.isBlank()) return true;
    return switch (scope) {
      case "group" -> groupMatches(known, entity, e.groups);
      case "teacher" -> e.teachers.contains(entity);
      case "room" -> e.room.equals(entity);
      case "subject" -> e.subject.equals(entity);
      default -> false;
    };
  }
}
