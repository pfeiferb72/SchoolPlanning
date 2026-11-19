package at.spps;

import static at.spps.Model.*;
import static at.spps.Security.Member;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Api {
  static final String BASE = "/api/v1/schools/{school}", PLAN = BASE + "/scenarios/{id}";
  final Store store;
  final Solver solver;
  final Daily daily;
  final Views views;
  final boolean demo;

  Api(Store store, Solver solver, Daily daily, Views views, @Value("${spps.demo}") boolean demo) {
    this.store = store;
    this.solver = solver;
    this.daily = daily;
    this.views = views;
    this.demo = demo;
  }

  Member member(Authentication auth, String school) {
    return Security.member(store.database(), auth, school);
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    return Map.of("database", store.database().queryForObject("select 1", Integer.class) == 1);
  }

  @GetMapping("/session")
  Map<String, Object> session(Authentication auth, CsrfToken csrf) {
    return Map.of(
        "demo",
        demo,
        "csrf",
        csrf.getToken(),
        "authenticated",
        auth != null,
        "schools",
        auth == null
            ? List.of()
            : store
                .database()
                .queryForList(
                    "select s.id,s.name,m.role,m.entity from school s join membership m on"
                        + " m.school_id=s.id where m.subject=? order by s.name",
                    Security.subject(auth)));
  }

  @GetMapping(BASE + "/scenarios")
  Object scenarios(Authentication a, @PathVariable String school) {
    var m = member(a, school);
    m.allow("PLAN", "DIRECTION", "OFFICE");
    return store
        .database()
        .queryForList(
            "select id,name,status,revision from scenario where school_id=? order by name,id",
            school);
  }

  record Create(String name, UUID source, LocalDate from, LocalDate until) {}

  @PostMapping(BASE + "/scenarios")
  Object create(Authentication a, @PathVariable String school, @RequestBody Create body) {
    var m = member(a, school);
    m.allow("PLAN");
    Plan p = body.source == null ? Example.blank() : store.get(m, body.source, false).plan();
    if (body.from != null || body.until != null) {
      CatalogRules.range(body.from, body.until);
      p.catalog.from = body.from;
      p.catalog.until = body.until;
      p.catalog.exams = List.of();
      p.catalog.bookings = List.of();
      p.catalog.holidays = List.of();
      p.catalog.periods = List.of();
    }
    return Map.of("id", store.create(m, body.name, p));
  }

  record ScenarioView(Store.Scenario scenario, Engine.Metrics metrics, boolean redo) {}

  @GetMapping(PLAN)
  ScenarioView scenario(Authentication a, @PathVariable String school, @PathVariable UUID id) {
    var m = member(a, school);
    m.allow("PLAN", "DIRECTION", "OFFICE");
    var s = store.get(m, id, false);
    return new ScenarioView(
        s,
        Engine.inspect(s.plan()).metrics(s.plan()),
        Boolean.TRUE.equals(
            store
                .database()
                .queryForObject(
                    "select exists(select 1 from revision where scenario_id=? and step>?)",
                    Boolean.class,
                    id,
                    s.cursor())));
  }

  record Move(long revision, String lesson, Place place) {}

  @PostMapping(PLAN + "/placement")
  @Transactional
  Engine.Metrics place(
      Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @RequestBody Move move,
      @RequestParam(defaultValue = "false") boolean preview) {
    var m = member(a, school);
    m.allow("PLAN");
    var s = store.edit(m, id, move.revision);
    Plan p = s.plan();
    var old = p.places.get(move.lesson);
    require(
        old == null
            || !old.fixed()
            || move.place != null
                && old.day() == move.place.day()
                && old.slot() == move.place.slot()
                && old.room().equals(move.place.room())
                && old.teachers().equals(move.place.teachers()),
        "Fixierung zuerst lösen");
    require(
        units(p.catalog).stream().anyMatch(u -> u.id().equals(move.lesson)), "Unbekannte Stunde");
    if (move.place == null) p.places.remove(move.lesson);
    else p.places.put(move.lesson, move.place);
    var metrics = Engine.inspect(p).metrics(p);
    if (!preview) store.save(m, id, move.revision, p, "Platzierung " + move.lesson);
    return metrics;
  }

  @GetMapping(PLAN + "/suggestions")
  List<Place> suggestions(
      Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @RequestParam String lesson) {
    var m = member(a, school);
    m.allow("PLAN");
    var p = store.get(m, id, false).plan();
    p.places.remove(lesson);
    var e = Engine.inspect(p);
    require(e.lessons.containsKey(lesson), "Unbekannte Stunde");
    return Solver.choices(
        e,
        e.lessons.get(lesson),
        null,
        null,
        new Random(1),
        System.nanoTime() + 2_000_000_000L,
        new AtomicBoolean(),
        5);
  }

  record CatalogEdit(long revision, Catalog catalog) {}

  @PutMapping(PLAN + "/catalog")
  @Transactional
  void catalog(
      Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @RequestBody CatalogEdit edit) {
    var m = member(a, school);
    m.allow("PLAN", "OFFICE");
    var p = store.edit(m, id, edit.revision).plan();
    p.catalog = edit.catalog;
    store.save(m, id, edit.revision, p, "Stammdaten");
  }

  record Step(long revision, String action) {}

  @PostMapping(PLAN + "/history")
  void history(
      Authentication a, @PathVariable String school, @PathVariable UUID id, @RequestBody Step r) {
    require(Set.of("undo", "redo").contains(r.action), "Unbekannter Schritt");
    store.history(member(a, school), id, r.revision, r.action.equals("undo") ? -1 : 1);
  }

  @PostMapping(PLAN + "/workflow")
  void workflow(
      Authentication a, @PathVariable String school, @PathVariable UUID id, @RequestBody Step r) {
    store.transition(member(a, school), id, r.revision, r.action);
  }

  @GetMapping(PLAN + "/diff")
  Object diff(
      Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @RequestParam UUID other) {
    var m = member(a, school);
    m.allow("PLAN", "DIRECTION", "OFFICE");
    var left = store.get(m, id, false).plan();
    var right = store.get(m, other, false).plan();
    var ids = new TreeSet<>(left.places.keySet());
    ids.addAll(right.places.keySet());
    var changes = new ArrayList<Object>();
    for (String lesson : ids)
      if (!Objects.equals(left.places.get(lesson), right.places.get(lesson)))
        changes.add(new Difference(lesson, left.places.get(lesson), right.places.get(lesson)));
    return Map.of(
        "catalogChanged",
        !store.encode(left.catalog).equals(store.encode(right.catalog)),
        "placements",
        changes);
  }

  record Difference(String lesson, Place current, Place other) {}

  @PostMapping(PLAN + "/solver")
  Solver.State start(
      Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @RequestBody Solver.Request body) {
    return solver.start(member(a, school), id, body);
  }

  @GetMapping(BASE + "/jobs/{job}")
  Solver.State job(Authentication a, @PathVariable String school, @PathVariable UUID job) {
    return solver.ledger.read(member(a, school), job);
  }

  @DeleteMapping(BASE + "/jobs/{job}")
  void cancel(Authentication a, @PathVariable String school, @PathVariable UUID job) {
    solver.ledger.cancel(member(a, school), job);
  }

  @PostMapping(BASE + "/absences")
  void absence(
      Authentication a, @PathVariable String school, @RequestBody Daily.RecordAbsence body) {
    daily.absent(member(a, school), body);
  }

  @GetMapping(BASE + "/covers")
  Daily.Review proposals(
      Authentication a, @PathVariable String school, @RequestParam LocalDate day) {
    return daily.propose(member(a, school), day, null);
  }

  @PostMapping(BASE + "/covers")
  void covers(Authentication a, @PathVariable String school, @RequestBody Daily.Proposal proposal) {
    daily.publish(member(a, school), proposal);
  }

  @GetMapping(BASE + "/events")
  List<Views.Event> events(
      Authentication a,
      @PathVariable String school,
      @RequestParam LocalDate from,
      @RequestParam LocalDate until,
      @RequestParam(defaultValue = "group") String scope,
      @RequestParam(defaultValue = "") String entity) {
    return views.events(member(a, school), from, until, scope, entity);
  }

  @GetMapping(BASE + "/exports/{fmt}")
  ResponseEntity<byte[]> export(
      Authentication a,
      @PathVariable String school,
      @PathVariable String fmt,
      @RequestParam LocalDate from,
      @RequestParam LocalDate until,
      @RequestParam(defaultValue = "group") String scope,
      @RequestParam(defaultValue = "") String entity)
      throws IOException {
    return Exports.render(views.events(member(a, school), from, until, scope, entity), fmt, school);
  }

  @GetMapping(BASE + "/notices")
  Object notices(Authentication a, @PathVariable String school) {
    member(a, school);
    return store
        .database()
        .queryForList(
            "select n.message,n.created from notice n join inbox i on i.notice_id=n.id where"
                + " i.school_id=? and i.subject=? order by n.id desc limit 100",
            school,
            Security.subject(a));
  }

  record Wish(String message) {}

  @PostMapping(BASE + "/wishes")
  @Transactional
  void wish(Authentication a, @PathVariable String school, @RequestBody Wish body) {
    var m = member(a, school);
    m.allow("TEACHER");
    require(
        body.message != null && !body.message.isBlank() && body.message.length() <= 1000,
        "Text bis 1000 Zeichen");
    store
        .database()
        .update(
            "insert into wish(school_id,teacher,message) values(?,?,?)",
            school,
            m.entity(),
            body.message);
    store.audit(m, "Wunsch eingereicht");
  }

  @GetMapping(BASE + "/wishes")
  Object wishes(Authentication a, @PathVariable String school) {
    var m = member(a, school);
    m.allow("TEACHER", "PLAN");
    return store
        .database()
        .queryForList(
            "select id,teacher,message,status from wish where school_id=? and (?='PLAN' or"
                + " teacher=?) order by id desc",
            school,
            m.role(),
            m.entity());
  }

  @PostMapping(BASE + "/wishes/{wish}/close")
  @Transactional
  void close(Authentication a, @PathVariable String school, @PathVariable long wish) {
    var m = member(a, school);
    m.allow("PLAN");
    require(
        store
                .database()
                .update(
                    "update wish set status='ERLEDIGT' where id=? and school_id=?", wish, school)
            == 1,
        "Unbekannter Wunsch");
    store.audit(m, "Wunsch erledigt " + wish);
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
  ResponseEntity<?> input(RuntimeException e) {
    return ResponseEntity.unprocessableEntity()
        .body(
            Map.of(
                "message",
                e instanceof NullPointerException
                    ? "Eingabe unvollständig"
                    : Objects.toString(e.getMessage(), "Ungültige Eingabe")));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("message", Objects.toString(e.getReason(), e.getStatusCode().toString())));
  }
}
