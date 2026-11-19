package at.spps;

import static at.spps.Security.Member;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobLedger {
  final JdbcTemplate db;
  final Store store;

  JobLedger(JdbcTemplate db, Store store) {
    this.db = db;
    this.store = store;
  }

  void register(Solver.Job job) {
    try {
      db.update(
          "insert into solver_job(id,school_id,scenario_id,status) values(?,?,?,'WARTET')",
          job.id,
          job.school,
          job.scenario);
    } catch (DuplicateKeyException e) {
      throw new IllegalArgumentException("Solver läuft bereits");
    }
  }

  void record(Solver.State s) {
    db.update(
        "update solver_job set status=?,progress=?,placed=?,score=?,message=?,heartbeat=now() where"
            + " id=? and status in ('WARTET','LÄUFT')",
        s.status(),
        s.progress(),
        s.placed(),
        s.score(),
        s.message(),
        s.id());
  }

  Solver.State read(Member m, UUID id) {
    m.allow("PLAN", "DIRECTION");
    return db
        .query(
            "select status,progress,placed,score,message from solver_job where id=? and"
                + " school_id=?",
            (r, n) ->
                new Solver.State(
                    id, r.getString(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getString(5)),
            id,
            m.school())
        .stream()
        .findFirst()
        .orElseThrow(Store::missing);
  }

  void cancel(Member m, UUID id) {
    m.allow("PLAN");
    read(m, id);
    db.update("update solver_job set cancelled=true where id=? and school_id=?", id, m.school());
  }

  boolean heartbeat(UUID id) {
    return db
        .query(
            "update solver_job set heartbeat=now() where id=? and status in ('WARTET','LÄUFT')"
                + " returning cancelled",
            (r, n) -> r.getBoolean(1),
            id)
        .stream()
        .findFirst()
        .orElse(true);
  }

  @Transactional
  public void finish(
      Solver.Job j,
      Member m,
      UUID id,
      long revision,
      Model.Plan origin,
      Map<String, Model.Place> best,
      int cost) {
    var stopped =
        db.queryForObject(
            "select cancelled or status not in ('WARTET','LÄUFT') from solver_job where id=? for"
                + " update",
            Boolean.class,
            j.id);
    if (Boolean.TRUE.equals(stopped) || j.cancelled.get()) {
      j.set(
          "ABGEBROCHEN",
          100,
          origin.places.size(),
          Engine.inspect(origin).penalties.total(),
          "Entwurf beibehalten");
    } else {
      boolean authorized =
          Boolean.TRUE.equals(
              db.queryForObject(
                  "select exists(select 1 from membership where school_id=? and subject=? and"
                      + " (purpose_until is null or purpose_until >= (now() at time zone"
                      + " 'Europe/Vienna')::date) and role='PLAN')",
                  Boolean.class,
                  m.school(),
                  m.actor()));
      Model.require(authorized, "Planungsberechtigung geändert");
      if (!origin.places.equals(best)) {
        origin.places = best;
        store.save(m, id, revision, origin, "Solver");
      }
      j.set("FERTIG", 100, best.size(), cost, "Vorschlag im Entwurf");
    }
    record(j.state);
  }

  @Scheduled(fixedDelay = 60000)
  void expire() {
    db.update(
        "update solver_job set status='FEHLER',progress=100,message='Worker unterbrochen. Entwurf"
            + " beibehalten.' where status in ('WARTET','LÄUFT') and heartbeat<now()-interval '2"
            + " minutes'");
    db.update(
        "delete from solver_job where status not in ('WARTET','LÄUFT') and heartbeat<now()-interval"
            + " '2 days'");
  }
}
