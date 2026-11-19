package at.spps;

import static at.spps.Model.*;
import static at.spps.Security.Member;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class Solver {
  record Request(
      long revision, int seconds, boolean empty, String group, String teacher, Integer day) {}

  record State(UUID id, String status, int progress, int placed, int score, String message) {}

  static final class Job {
    final UUID id = UUID.randomUUID();
    final String school;
    final UUID scenario;
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile State state;
    final long created = System.currentTimeMillis();

    Job(String school, UUID scenario) {
      this.school = school;
      this.scenario = scenario;
      set("WARTET", 0, 0, 0, "");
    }

    void set(String status, int progress, int placed, int score, String message) {
      state = new State(id, status, progress, placed, score, message);
    }
  }

  final JobLedger ledger;
  final Store store;
  final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
  final ExecutorService workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(12),
          Thread.ofPlatform().daemon().factory());

  Solver(Store store, JobLedger ledger) {
    this.store = store;
    this.ledger = ledger;
  }

  synchronized State start(Member m, UUID id, Request req) {
    m.allow("PLAN");
    var s = store.get(m, id, false);
    if (s.revision() != req.revision()) Store.stale();
    require(
        s.status().equals("DRAFT") && req.seconds() >= 1 && req.seconds() <= 900,
        "Zeitbudget 1–900 Sekunden, Entwurf erforderlich");
    if (req.day() != null) require(req.day() >= 1 && req.day() <= 5, "Tag außerhalb des Rasters");
    jobs.values().removeIf(j -> System.currentTimeMillis() - j.created > 3600000 && !running(j));
    require(
        jobs.values().stream().noneMatch(j -> j.scenario.equals(id) && running(j)),
        "Solver läuft bereits");
    Job j = new Job(m.school(), id);
    ledger.register(j);
    jobs.put(j.id, j);
    try {
      workers.submit(() -> run(j, m, id, s.plan(), req));
    } catch (RejectedExecutionException e) {
      jobs.remove(j.id);
      j.set("FEHLER", 100, 0, 0, "Warteschlange voll");
      ledger.record(j.state);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }
    return j.state;
  }

  static boolean running(Job j) {
    return Set.of("WARTET", "LÄUFT").contains(j.state.status);
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 500)
  void heartbeat() {
    for (var j : jobs.values())
      if (running(j)) {
        if (ledger.heartbeat(j.id)) j.cancelled.set(true);
        State snapshot = j.state;
        if (Set.of("WARTET", "LÄUFT").contains(snapshot.status())) ledger.record(snapshot);
      }
  }

  static boolean selected(Unit u, Place p, Request r) {
    var a = u.course();
    return (r.group() == null || a.groups().contains(r.group()))
        && (r.teacher() == null
            || a.teachers().contains(r.teacher())
            || (p != null && p.teachers().contains(r.teacher()))
            || (p == null && a.teachers().isEmpty()))
        && (r.day() == null || p == null || p.day() == r.day());
  }

  void run(Job j, Member m, UUID id, Plan origin, Request req) {
    long start = System.nanoTime(), end = start + TimeUnit.SECONDS.toNanos(req.seconds());
    try {
      var orig = Engine.inspect(origin);
      var best = new LinkedHashMap<>(origin.places);
      int cost = orig.penalties.total();
      var movable = new ArrayList<Unit>();
      var base = new LinkedHashMap<String, Place>();
      for (var u : orig.lessons.values()) {
        var p = origin.places.get(u.id());
        if ((p != null && p.fixed()) || !selected(u, p, req)) {
          if (p != null) base.put(u.id(), p);
        } else movable.add(u);
      }
      var random = new Random(2026);
      for (int pass = 0; pass < 512 && System.nanoTime() < end && !j.cancelled.get(); pass++) {
        Plan trial = new Plan();
        trial.catalog = origin.catalog;
        trial.places.putAll(base);
        if (pass == 0 && !req.empty()) trial.places.putAll(origin.places);
        var e = Engine.inspect(trial);
        Collections.shuffle(movable, random);
        movable.sort(Comparator.comparingInt((Unit u) -> u.course().block()).reversed());
        for (var u : movable) {
          if (trial.places.containsKey(u.id())) continue;
          if (System.nanoTime() >= end || j.cancelled.get()) break;
          var choices = choices(e, u, req.day(), req.teacher(), random, end, j.cancelled, 1);
          if (!choices.isEmpty()) {
            Place p = choices.getFirst();
            trial.places.put(u.id(), p);
            e.apply(u, p);
          }
        }
        if (trial.places.size() > best.size()
            || (trial.places.size() == best.size() && e.penalties.total() < cost)) {
          best = new LinkedHashMap<>(trial.places);
          cost = e.penalties.total();
        }
        j.set(
            "LÄUFT",
            (int) Math.min(99, 100 * (System.nanoTime() - start) / (end - start)),
            best.size(),
            cost,
            "");
        if (best.size() == orig.lessons.size() && cost == 0) break;
      }
      ledger.finish(j, m, id, req.revision(), origin, best, cost);
    } catch (Exception e) {
      j.set(
          "FEHLER",
          100,
          0,
          0,
          e instanceof ResponseStatusException
              ? "Entwurf zwischenzeitlich geändert"
              : Objects.toString(e.getMessage(), "Solverfehler"));
      ledger.record(j.state);
    }
  }

  record Candidate(Place place, int score, double tie) {}

  static List<Place> choices(
      Engine e,
      Unit u,
      Integer onlyDay,
      String teacher,
      Random rng,
      long deadline,
      AtomicBoolean cancel,
      int count) {
    List<List<String>> teams = new ArrayList<>();
    if (!u.course().teachers().isEmpty()) teams.add(u.course().teachers());
    else
      for (var t : e.c.teachers)
        if (t.subjects().contains(u.course().subject())
            && (teacher == null || teacher.equals(t.id()))) teams.add(List.of(t.id()));
    var order = Comparator.comparingInt(Candidate::score).thenComparingDouble(Candidate::tie);
    var best = new PriorityQueue<Candidate>(order.reversed());
    for (int d = 1; d <= 5; d++)
      if (onlyDay == null || onlyDay == d)
        for (int s = 0; s <= e.c.slots.size() - u.course().block(); s++)
          for (var room : e.suitableRooms.get(u.course().id()))
            for (var team : teams) {
              if (System.nanoTime() >= deadline || cancel.get())
                return best.stream().sorted(order).map(Candidate::place).toList();
              var p = new Place(d, s, room.id(), team, false);
              if (e.conflict(u, p) == null) {
                var option = new Candidate(p, e.delta(u, p).total(), rng.nextDouble());
                best.offer(option);
                if (best.size() > count) best.poll();
              }
            }
    return best.stream().sorted(order).map(Candidate::place).toList();
  }
}
