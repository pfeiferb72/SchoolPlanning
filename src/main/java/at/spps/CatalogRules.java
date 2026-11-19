package at.spps;

import static at.spps.Model.*;

import java.time.*;
import java.util.*;

final class CatalogRules {
  static boolean distinct(List<?> ids) {
    return ids != null
        && new HashSet<>(ids).size() == ids.size()
        && ids.stream().noneMatch(Objects::isNull);
  }

  static void refs(List<String> ids, Collection<String> known) {
    require(distinct(ids) && known.containsAll(ids), "Fehlender oder doppelter Verweis: " + ids);
  }

  static void range(LocalDate a, LocalDate b) {
    require(a != null && b != null && !b.isBefore(a), "Ungültiger Datumsbereich");
  }

  static void windows(List<Window> ws, int n) {
    for (var w : ws)
      require(
          w.day() >= 1
              && w.day() <= 5
              && w.slot() >= 0
              && w.slot() < n
              && List.of("A", "B", "AB").contains(w.week()),
          "Ungültiges Zeitfenster");
  }

  static void validate(Catalog c) {
    range(c.from, c.until);
    require(c.until.isBefore(c.from.plusYears(2)), "Semesterzeitraum zu lang");
    require(c.slots.size() > 0 && c.slots.size() <= 24, "1 bis 24 Zeiteinheiten erforderlich");
    LocalTime last = null;
    for (var s : c.slots) {
      require(
          s.start().isBefore(s.end()) && (last == null || !s.start().isBefore(last)),
          "Zeitraster überlappt");
      last = s.end();
    }
    int n = c.slots.size();
    windows(c.blocked, n);
    require(c.maxConsecutive > 0 && c.breakMinutes >= 0, "Ungültige Pausenregel");
    var w = c.weights;
    for (int v : List.of(w.wishes(), w.spread(), w.gaps(), w.late(), w.home(), w.morning()))
      require(v >= 0 && v <= 1000, "Gewicht außerhalb 0–1000");
    var subjects = index(c.subjects, Subject::id);
    var teachers = index(c.teachers, Teacher::id);
    var groups = index(c.groups, Group::id);
    var rooms = index(c.rooms, Room::id);
    var students = index(c.students, Student::id);
    index(c.courses, Course::id);
    index(c.bookings, Booking::id);
    index(c.exams, Exam::id);
    var periods = index(c.periods, Model.Period::id);
    require(distinct(c.resources), "Doppelte Ressource");
    for (var t : c.teachers) {
      if (t.employedFrom() != null && t.employedUntil() != null)
        range(t.employedFrom(), t.employedUntil());
      refs(t.subjects(), subjects.keySet());
      windows(t.blocked(), n);
      windows(t.wishes(), n);
      require(t.load() >= 0 && t.daily() > 0, "Ungültige Lehrverpflichtung oder Tagesgrenze");
    }
    for (var g : c.groups) {
      require(
          g.size() > 0 && distinct(g.cohorts()) && !g.cohorts().isEmpty(),
          "Gruppengröße und Teilgruppen prüfen");
      require(
          g.home() == null || g.home().isBlank() || rooms.containsKey(g.home()),
          "Unbekannter Stammraum");
    }
    for (var r : c.rooms) {
      windows(r.blocked(), n);
      require(r.capacity() > 0 && distinct(r.equipment()), "Raumdaten prüfen");
    }
    for (var s : c.students) refs(s.groups(), groups.keySet());
    for (var a : c.courses) {
      refs(a.groups(), groups.keySet());
      refs(a.teachers(), teachers.keySet());
      refs(a.resources(), c.resources);
      require(
          subjects.containsKey(a.subject()) && !a.groups().isEmpty() && distinct(a.equipment()),
          "Fach, Gruppen oder Ausstattung prüfen");
      require(
          a.hours() >= 1
              && a.hours() <= 100
              && a.block() > 0
              && a.block() <= n
              && a.hours() % a.block() == 0,
          "Wochenstunden müssen durch Blocklänge teilbar sein");
      require(List.of("A", "B", "AB").contains(a.week()), "Wochenrhythmus A, B oder AB erwartet");
      var cohorts = new HashSet<String>();
      for (String g : a.groups())
        for (String k : groups.get(g).cohorts())
          require(cohorts.add(k), "Überlappende Gruppen in Kopplung");
    }
    for (var b : c.bookings) {
      range(b.from(), b.until());
      require(
          (rooms.containsKey(b.resource()) || c.resources.contains(b.resource()))
              && b.first() >= 0
              && b.last() >= b.first()
              && b.last() < n,
          "Buchung prüfen");
    }
    for (int i = 0; i < c.bookings.size(); i++)
      for (int j = 0; j < i; j++) {
        var a = c.bookings.get(i);
        var b = c.bookings.get(j);
        require(
            !a.resource().equals(b.resource())
                || a.until().isBefore(b.from())
                || b.until().isBefore(a.from())
                || a.last() < b.first()
                || b.last() < a.first(),
            "Buchungskollision: " + a.id() + " / " + b.id());
      }
    for (var h : c.holidays) range(h.from(), h.until());
    for (var p : c.periods) range(p.from(), p.until());
    for (var e : c.exams) {
      var p = periods.get(e.period());
      require(
          p != null
              && teachingDate(c, e.date())
              && !e.date().isBefore(p.from())
              && !e.date().isAfter(p.until())
              && e.start().isBefore(e.end()),
          "Prüftermin außerhalb der Periode");
      refs(e.teachers(), teachers.keySet());
      refs(e.candidates(), students.keySet());
      require(
          !e.teachers().isEmpty()
              && !e.candidates().isEmpty()
              && rooms.containsKey(e.room())
              && rooms.get(e.room()).capacity() >= e.candidates().size(),
          "Prüfungsraum, Kandidaten oder Aufsicht prüfen");
      for (var f : c.exams) {
        if (f == e) break;
        if (e.date().equals(f.date()) && overlap(e.start(), e.end(), f.start(), f.end()))
          require(
              !e.room().equals(f.room())
                  && Collections.disjoint(e.teachers(), f.teachers())
                  && Collections.disjoint(e.candidates(), f.candidates()),
              "Prüfungskollision: " + e.id() + " / " + f.id());
      }
      for (int i = 0; i < n; i++)
        if (overlap(e.start(), e.end(), c.slots.get(i).start(), c.slots.get(i).end())) {
          int day = e.date().getDayOfWeek().getValue(), wk = week(c, e.date());
          require(
              !Engine.blocked(c.blocked, day, i, wk)
                  && !Engine.blocked(rooms.get(e.room()).blocked(), day, i, wk),
              "Prüfung in Sperrzeit");
          for (String t : e.teachers())
            require(!Engine.blocked(teachers.get(t).blocked(), day, i, wk), "Aufsicht gesperrt");
          for (var b : c.bookings)
            require(
                !b.resource().equals(e.room())
                    || e.date().isBefore(b.from())
                    || e.date().isAfter(b.until())
                    || i < b.first()
                    || i > b.last(),
                "Prüfungsraum gebucht");
        }
    }
    require(
        distinct(c.coverOrder)
            && !c.coverOrder.isEmpty()
            && c.coverOrder.getLast().equals("Ausfall")
            && List.of("Fachvertretung", "Betreuung", "Zusammenlegung", "Ausfall")
                .containsAll(c.coverOrder),
        "Vertretungskaskade muss mit Ausfall enden");
  }

  static boolean overlap(LocalTime a, LocalTime b, LocalTime x, LocalTime y) {
    return a.isBefore(y) && x.isBefore(b);
  }
}
