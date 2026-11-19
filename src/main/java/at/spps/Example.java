package at.spps;

import static at.spps.Model.*;

import java.time.*;
import java.util.*;

class Example {
  static Plan blank() {
    Plan p = new Plan();
    for (int i = 0; i < 8; i++) {
      LocalTime t = LocalTime.of(8, 0).plusMinutes(i * 55 + (i >= 3 ? 10 : 0));
      p.catalog.slots.add(new Slot(t, t.plusMinutes(50)));
    }
    return p;
  }

  static Plan school() {
    Plan p = blank();
    var c = p.catalog;
    c.subjects =
        List.of(
            new Subject("M", "Mathematik"),
            new Subject("D", "Deutsch"),
            new Subject("BI", "Biologie"),
            new Subject("E", "Englisch"));
    c.rooms =
        List.of(
            new Room("101", "Klassenraum 101", 28, List.of(), List.of()),
            new Room("102", "Klassenraum 102", 28, List.of(), List.of()),
            new Room("LAB", "Biologiesaal", 32, List.of("Labor"), List.of()));
    c.groups =
        List.of(
            new Group("1A", "1A", 22, "101", List.of("1A")),
            new Group("1B", "1B", 24, "102", List.of("1B")));
    c.teachers =
        List.of(
            new Teacher("WEB", "Anna Weber", List.of("M"), 6, 6, List.of(), List.of()),
            new Teacher("BER", "Paul Berger", List.of("D", "E"), 6, 6, List.of(), List.of()),
            new Teacher("HUB", "Eva Huber", List.of("BI", "E"), 6, 6, List.of(), List.of()));
    c.students =
        List.of(
            new Student("S01", "Alex Beispiel", List.of("1A")),
            new Student("S02", "Robin Beispiel", List.of("1B")));
    var courses = new ArrayList<Course>();
    for (var g : c.groups) {
      courses.add(
          new Course(
              "M-" + g.id(),
              "M",
              List.of(g.id()),
              List.of("WEB"),
              3,
              1,
              "AB",
              List.of(),
              List.of()));
      courses.add(
          new Course(
              "D-" + g.id(),
              "D",
              List.of(g.id()),
              List.of("BER"),
              3,
              1,
              "AB",
              List.of(),
              List.of()));
      courses.add(
          new Course(
              "BI-" + g.id(),
              "BI",
              List.of(g.id()),
              List.of("HUB"),
              2,
              2,
              "AB",
              List.of("Labor"),
              List.of()));
      courses.add(
          new Course(
              "E-" + g.id(),
              "E",
              List.of(g.id()),
              List.of("HUB"),
              1,
              1,
              "AB",
              List.of(),
              List.of()));
    }
    c.courses = courses;
    c.periods = List.of(new Model.Period("W", "Winterprüfungen", c.from, c.until));
    p.places.put("M-1A#1", new Place(1, 0, "101", List.of("WEB"), false));
    p.places.put("D-1B#1", new Place(1, 0, "102", List.of("BER"), false));
    p.places.put("BI-1A#1", new Place(2, 1, "LAB", List.of("HUB"), false));
    p.places.put("D-1A#1", new Place(3, 0, "101", List.of("BER"), false));
    p.places.put("M-1B#1", new Place(4, 1, "102", List.of("WEB"), false));
    return p;
  }
}
