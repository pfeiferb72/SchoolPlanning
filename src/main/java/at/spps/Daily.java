package at.spps;

import static at.spps.Model.*;
import static at.spps.Security.Member;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Daily {
  record Replacement(
      String lesson, String missing, String kind, String replacement, String target) {}

  record Proposal(long publication, LocalDate day, List<Replacement> rows) {}

  record RecordAbsence(String teacher, LocalDate from, LocalDate until, String reason) {}

  final Store store;

  Daily(Store store) {
    this.store = store;
  }

  @Transactional
  public void absent(Member m, RecordAbsence r) {
    m.allow("PLAN", "TEACHER");
    CatalogRules.range(r.from, r.until);
    require(!r.until.isAfter(r.from.plusDays(366)), "Maximal ein Jahr je Meldung");
    if (m.role().equals("TEACHER"))
      require(m.entity().equals(r.teacher), "Nur eigene Abwesenheit zulässig");
    require(
        List.of("Krankheit", "Dienstweg", "Fortbildung", "Exkursion", "Freistellung")
            .contains(r.reason),
        "Ungültiger Grund");
    store.schoolLock(m);
    var p = store.published(m, r.from).plan();
    require(
        p.catalog.teachers.stream().anyMatch(t -> t.id().equals(r.teacher)),
        "Unbekannte Lehrkraft");
    for (var d = r.from; !d.isAfter(r.until); d = d.plusDays(1))
      store
          .database()
          .update(
              "insert into absence(school_id,teacher,day,reason) values(?,?,?,?) on"
                  + " conflict(school_id,teacher,day) do update set reason=excluded.reason",
              m.school(),
              r.teacher,
              d,
              r.reason);
    store.audit(m, "Abwesenheit erfasst " + r.from + " – " + r.until);
  }

  static class Roster {
    final Plan p;
    final Catalog c;
    final Engine engine;
    final Map<String, Place> today = new TreeMap<>();
    final Set<String> absent, merged = new HashSet<>();
    final Map<String, Integer> busy = new HashMap<>(), accounts;
    final Map<String, Integer> weeklyHours = new HashMap<>();
    final int day, week;
    final LocalDate date;

    Roster(Plan plan, LocalDate date, Set<String> absent, Map<String, Integer> accounts) {
      p = plan;
      c = p.catalog;
      engine = new Engine(c);
      this.absent = absent;
      this.accounts = accounts;
      this.date = date;
      day = date.getDayOfWeek().getValue();
      week = week(c, date);
      if (!teachingDate(c, date)) return;
      for (var row : p.places.entrySet()) {
        var at = row.getValue();
        var a = engine.lessons.get(row.getKey()).course();
        if (!active(a.week(), week)) continue;
        for (String t : at.teachers()) weeklyHours.merge(t, a.block(), Integer::sum);
        if (day != at.day()) continue;
        today.put(row.getKey(), at);
        for (String t : at.teachers())
          busy.merge(t, Engine.mask(at.slot(), a.block()), (x, y) -> x | y);
      }
      for (var e : c.exams)
        if (e.date().equals(date))
          for (int i = 0; i < c.slots.size(); i++)
            if (CatalogRules.overlap(
                e.start(), e.end(), c.slots.get(i).start(), c.slots.get(i).end()))
              for (String t : e.teachers()) busy.merge(t, 1 << i, (x, y) -> x | y);
    }

    int pupils(Course a) {
      return a.groups().stream().mapToInt(g -> engine.groups.get(g).size()).sum();
    }

    List<Replacement> options(String lesson, String missing) {
      var at = today.get(lesson);
      var a = engine.lessons.get(lesson).course();
      var out = new ArrayList<Replacement>();
      int mask = Engine.mask(at.slot(), a.block());
      var staff = new ArrayList<>(c.teachers);
      staff.sort(
          Comparator.comparingInt(
                  (Teacher t) -> c.balanceCover ? accounts.getOrDefault(t.id(), 0) : 0)
              .thenComparingInt(
                  t -> c.preferRemainingLoad ? weeklyHours.getOrDefault(t.id(), 0) - t.load() : 0)
              .thenComparingInt(
                  t ->
                      Engine.span(busy.getOrDefault(t.id(), 0) | mask)
                          - Engine.span(busy.getOrDefault(t.id(), 0)))
              .thenComparing(Teacher::id));
      for (String kind : c.coverOrder) {
        if (kind.equals("Ausfall")) {
          out.add(new Replacement(lesson, missing, kind, "", ""));
          continue;
        }
        if (kind.equals("Zusammenlegung")) {
          for (var x : today.entrySet()) {
            var dest = x.getValue();
            var other = engine.lessons.get(x.getKey()).course();
            if (x.getKey().equals(lesson)
                || merged.contains(x.getKey())
                || merged.contains(lesson)
                || at.teachers().size() != 1
                || dest.slot() != at.slot()
                || other.block() != a.block()
                || !a.subject().equals(other.subject())
                || !Collections.disjoint(dest.teachers(), absent)
                || pupils(a) + pupils(other) > engine.rooms.get(dest.room()).capacity()) continue;
            out.add(new Replacement(lesson, missing, kind, dest.teachers().getFirst(), x.getKey()));
          }
          continue;
        }
        for (var t : staff) {
          int prior = busy.getOrDefault(t.id(), 0);
          if (absent.contains(t.id())
              || (prior & mask) != 0
              || Engine.span(prior | mask) > t.daily()
              || engine.run(prior | mask) > c.maxConsecutive) continue;
          boolean free = true;
          for (int s = at.slot(); s < at.slot() + a.block(); s++)
            if (Engine.blocked(t.blocked(), day, s, week)) free = false;
          if (free && (!kind.equals("Fachvertretung") || t.subjects().contains(a.subject())))
            out.add(new Replacement(lesson, missing, kind, t.id(), ""));
        }
      }
      return out;
    }

    void assign(Replacement r) {
      if (r.kind.equals("Ausfall")) return;
      if (r.kind.equals("Zusammenlegung")) {
        merged.add(r.target);
        merged.add(r.lesson);
      }
      var a = engine.lessons.get(r.lesson).course();
      busy.merge(
          r.replacement, Engine.mask(today.get(r.lesson).slot(), a.block()), (x, y) -> x | y);
      accounts.merge(r.replacement, a.block(), Integer::sum);
    }
  }

  record Review(Proposal proposal, Map<String, List<Replacement>> options) {}

  Review propose(Member m, LocalDate date, List<Replacement> manual) {
    m.allow("PLAN", "DIRECTION");
    var publication = store.published(m, date);
    var abs =
        new HashSet<>(
            store
                .database()
                .queryForList(
                    "select teacher from absence where school_id=? and day=?",
                    String.class,
                    m.school(),
                    date));
    var counts = new HashMap<String, Integer>();
    store
        .database()
        .query(
            "select c.replacement,sum(c.hours) from cover c join publication p on"
                + " p.id=c.publication_id where p.school_id=? and c.day>=? and c.day<? and"
                + " c.replacement<>'' group by c.replacement",
            r -> {
              counts.put(r.getString(1), r.getInt(2));
            },
            m.school(),
            publication.plan().catalog.from,
            date);
    var roster = new Roster(publication.plan(), date, abs, counts);
    var rows = new ArrayList<Replacement>();
    var options = new LinkedHashMap<String, List<Replacement>>();
    var chosen = new HashMap<String, Replacement>();
    if (manual != null)
      for (var r : manual)
        require(chosen.put(r.lesson + "/" + r.missing, r) == null, "Doppelte Vertretung");
    for (var x : roster.today.entrySet())
      for (String t : x.getValue().teachers())
        if (abs.contains(t)) {
          String key = x.getKey() + "/" + t;
          var choices = roster.options(x.getKey(), t);
          var r = chosen.remove(key);
          if (r == null) {
            require(manual == null, "Vertretungsliste inzwischen geändert");
            r = choices.getFirst();
          }
          require(choices.contains(r), "Vertretung nicht verfügbar: " + key);
          roster.assign(r);
          rows.add(r);
          options.put(key, choices);
        }
    require(chosen.isEmpty(), "Veraltete Vertretungen");
    return new Review(new Proposal(publication.id(), date, rows), options);
  }

  @Transactional
  public void publish(Member m, Proposal p) {
    m.allow("PLAN");
    store.schoolLock(m);
    var checked = propose(m, p.day, p.rows).proposal;
    if (checked.publication != p.publication) Store.stale();
    store
        .database()
        .update("delete from cover where publication_id=? and day=?", p.publication, p.day);
    var plan = store.published(m, p.day).plan();
    var units = new Engine(plan.catalog).lessons;
    for (var r : p.rows)
      store
          .database()
          .update(
              "insert into cover(publication_id,day,lesson,missing,replacement,kind,target,hours)"
                  + " values(?,?,?,?,?,?,?,?)",
              p.publication,
              p.day,
              r.lesson,
              r.missing,
              r.replacement,
              r.kind,
              r.target,
              units.get(r.lesson).course().block());
    var staff = new HashSet<String>();
    var groups = new HashSet<String>();
    var students = new HashSet<String>();
    for (var row : p.rows) {
      staff.addAll(plan.places.get(row.lesson).teachers());
      staff.add(row.replacement);
      groups.addAll(units.get(row.lesson).course().groups());
      if (!row.target.isBlank()) groups.addAll(units.get(row.target).course().groups());
    }
    for (var student : plan.catalog.students)
      if (!Collections.disjoint(groups, student.groups())) students.add(student.id());
    store.notify(m, "Vertretungsplan veröffentlicht · " + p.day, true, staff, groups, students);
    store.audit(m, "Vertretungsplan " + p.day);
  }
}
