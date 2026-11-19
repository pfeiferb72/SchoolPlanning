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

  static boolean selected(Engine e, Unit u, Place p, Request r) {
    var a = u.course();
    return (r.group() == null || groupMatches(e.groups, r.group(), a.groups()))
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
      var bundles = new LinkedHashMap<String, List<Unit>>();
      for (var u : orig.lessons.values()) {
        String key = u.bandBlock().isEmpty() ? "course/" + u.id() : "band/" + u.bandBlock();
        bundles.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
      }
      var movable = new ArrayList<List<Unit>>();
      var base = new LinkedHashMap<String, Place>();
      for (var bundle : bundles.values()) {
        boolean included = bundle.stream().anyMatch(u -> selected(orig, u, origin.places.get(u.id()), req));
        var pending = new ArrayList<Unit>();
        for (var u : bundle) {
          Place p = origin.places.get(u.id());
          if (included && (p == null || (!p.fixed() && selected(orig, u, p, req)))) pending.add(u);
          else if (p != null) base.put(u.id(), p);
        }
        if (!pending.isEmpty()) movable.add(pending);
      }
      var random = new Random(2026);
      for (int pass = 0; pass < 512 && System.nanoTime() < end && !j.cancelled.get(); pass++) {
        Plan trial = new Plan();
        trial.catalog = origin.catalog;
        trial.places.putAll(base);
        if (pass == 0 && !req.empty()) trial.places.putAll(origin.places);
        var e = Engine.inspect(trial);
        Collections.shuffle(movable, random);
        movable.sort(Comparator.comparingInt((List<Unit> b) -> b.getFirst().course().block()).reversed());
        for (var bundle : movable) {
          var pending = bundle.stream().filter(u -> !trial.places.containsKey(u.id())).toList();
          if (pending.isEmpty()) continue;
          if (System.nanoTime() >= end || j.cancelled.get()) break;
          Map<Unit, Place> placements;
          if (pending.size() == 1) {
            var u = pending.getFirst();
            String filter = selected(orig, u, origin.places.get(u.id()), req) ? req.teacher() : null;
            var choices = choices(e, u, req.day(), filter, random, end, j.cancelled, 1);
            placements = choices.isEmpty() ? Map.of() : Map.of(u, choices.getFirst());
          } else {
            var preferred = pending.stream()
                .filter(u -> selected(orig, u, origin.places.get(u.id()), req))
                .sorted(Comparator.comparing(u -> u.course().teachers().isEmpty()))
                .findFirst().orElse(null);
            placements = parallelChoices(e, pending, req.day(), preferred, req.teacher(), random, end, j.cancelled);
          }
          placements.forEach((u, p) -> {
            trial.places.put(u.id(), p);
            e.apply(u, p);
          });
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

  // Try each common time. At a given time, backtrack over room and teacher
  // assignments so a flexible course can leave a specialist room for its partner.
  static Map<Unit, Place> parallelChoices(Engine e, List<Unit> units, Integer onlyDay,
      Unit preferred, String teacher, Random rng, long end, AtomicBoolean cancel) {
    Map<Unit, Place> best = Map.of();
    int bestScore = Integer.MAX_VALUE;
    var times = new ArrayList<Integer>();
    for (int day = 1; day <= 5; day++)
      if (onlyDay == null || onlyDay == day)
        for (int slot = 0; slot + units.getFirst().course().block() <= e.c.slots.size(); slot++)
          times.add(day * 32 + slot);
    Collections.shuffle(times, rng);
    for (int time : times) {
      if (System.nanoTime() >= end || cancel.get()) break;
      var options = new LinkedHashMap<Unit, List<Place>>();
      for (var u : units) {
        var places = candidates(e, u, time / 32, time % 32, u.equals(preferred) ? teacher : null,
            rng, end, cancel, Integer.MAX_VALUE);
        if (places.isEmpty()) break;
        options.put(u, places);
      }
      if (options.size() != units.size()) continue;
      var ordered = units.stream().sorted(Comparator.comparingInt(u -> options.get(u).size())).toList();
      var result = new LinkedHashMap<Unit, Place>();
      if (assign(ordered, options, result, new HashSet<>(), new HashSet<>(), end, cancel)) {
        int score = result.entrySet().stream().mapToInt(x -> e.delta(x.getKey(), x.getValue()).total()).sum();
        if (score < bestScore) {
          best = result;
          bestScore = score;
        }
      }
    }
    return best;
  }

  private static boolean assign(List<Unit> units, Map<Unit, List<Place>> options,
      Map<Unit, Place> result, Set<String> rooms, Set<String> teachers, long end, AtomicBoolean cancel) {
    if (result.size() == units.size()) return true;
    Unit u = units.get(result.size());
    for (Place p : options.get(u)) {
      if (System.nanoTime() >= end || cancel.get()) return false;
      if (rooms.contains(p.room()) || !Collections.disjoint(teachers, p.teachers())) continue;
      result.put(u, p);
      rooms.add(p.room());
      teachers.addAll(p.teachers());
      if (assign(units, options, result, rooms, teachers, end, cancel)) return true;
      result.remove(u);
      rooms.remove(p.room());
      teachers.removeAll(p.teachers());
    }
    return false;
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
    return candidates(e, u, onlyDay, null, teacher, rng, deadline, cancel, count);
  }

  private static List<Place> candidates(Engine e, Unit u, Integer onlyDay, Integer onlySlot,
      String teacher, Random rng, long deadline, AtomicBoolean cancel, int count) {
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
        for (int s = onlySlot == null ? 0 : onlySlot; s <= (onlySlot == null ? e.c.slots.size() - u.course().block() : onlySlot); s++)
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
