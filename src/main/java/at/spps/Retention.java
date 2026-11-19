package at.spps;

import static at.spps.Api.BASE;
import static at.spps.Model.require;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class RetentionService {
  final JdbcTemplate db;
  final Store store;

  RetentionService(JdbcTemplate db, Store store) {
    this.db = db;
    this.store = store;
  }

  record Policy(Integer days) {}

  record Preview(
      LocalDate cutoff,
      int scenarios,
      int identities,
      int absences,
      int notices,
      int wishes,
      int audit,
      int pendingMail) {}

  static final String EXPIRED_SCENARIO =
      "school_id=? and (document->'catalog'->>'until')::date < ?";
  static final String EXPIRED_MEMBER = "school_id=? and purpose_until < ?";

  Policy policy(String school) {
    return db.queryForObject(
        "select retention_days from school where id=?",
        (r, n) -> new Policy(r.getObject(1, Integer.class)),
        school);
  }

  int count(String table, String filter, String school, LocalDate cutoff) {
    return db.queryForObject(
        "select count(*) from " + table + " where " + filter, Integer.class, school, cutoff);
  }

  LocalDate cutoff(int days) {
    require(days >= 1 && days <= 36500, "Frist 1–36.500 Tage");
    return LocalDate.now(ZoneId.of("Europe/Vienna")).minusDays(days);
  }

  Preview preview(String school, int days) {
    var date = cutoff(days);
    String dated = "school_id=? and (created at time zone 'Europe/Vienna')::date < ?";
    return new Preview(
        date,
        count("scenario", EXPIRED_SCENARIO, school, date),
        count("membership", EXPIRED_MEMBER, school, date),
        count("absence", "school_id=? and day < ?", school, date),
        count("notice", dated, school, date),
        count("wish", dated, school, date),
        count("audit", dated, school, date),
        count("mail_queue", dated, school, date));
  }

  @Transactional
  public void set(Security.Member m, Policy policy) {
    m.allow("ADMIN");
    if (policy.days != null) cutoff(policy.days);
    db.update("update school set retention_days=? where id=?", policy.days, m.school());
    store.audit(
        m,
        policy.days == null
            ? "Automatische Löschung deaktiviert"
            : "Aufbewahrungsfrist " + policy.days + " Tage");
  }

  @Transactional
  public void sweep(String school) {
    if (db.query(
            "select id from school where id=? for update skip locked",
            (r, n) -> r.getString(1),
            school)
        .isEmpty()) return;
    var policy = policy(school);
    if (policy.days == null) return;
    var day = cutoff(policy.days);
    int deleted = 0;
    deleted += db.update("delete from scenario where " + EXPIRED_SCENARIO, school, day);
    deleted += db.update("delete from absence where school_id=? and day < ?", school, day);
    for (String table : List.of("notice", "wish", "audit", "mail_queue"))
      deleted +=
          db.update(
              "delete from "
                  + table
                  + " where school_id=? and (created at time zone 'Europe/Vienna')::date < ?",
              school,
              day);
    var identities =
        db.queryForList(
            "select subject,email,role,entity from membership where " + EXPIRED_MEMBER,
            school,
            day);
    for (var row : identities) {
      String subject = (String) row.get("subject");
      if (!row.get("email").toString().isBlank())
        db.update(
            "delete from mail_queue where school_id=? and recipient=?", school, row.get("email"));
      if (row.get("role").equals("TEACHER"))
        db.update(
            "delete from feed where school_id=? and scope='teacher' and entity=?",
            school,
            row.get("entity"));
      db.update(
          "update audit set actor='(gelöscht)' where school_id=? and actor=?", school, subject);
      db.update(
          "update revision r set author='(gelöscht)' from scenario s where s.id=r.scenario_id and"
              + " s.school_id=? and r.author=?",
          school,
          subject);
      deleted +=
          db.update("delete from membership where school_id=? and subject=?", school, subject);
    }
    if (deleted > 0)
      store.audit(
          new Security.Member(school, "ADMIN", "", "system"),
          "Fristgerechte Bereinigung: " + deleted + " Datensätze, Stichtag " + day);
  }
}

@Service
class RetentionScheduler {
  final RetentionService service;
  final JdbcTemplate db;

  RetentionScheduler(RetentionService service, JdbcTemplate db) {
    this.service = service;
    this.db = db;
  }

  @Scheduled(cron = "0 15 3 * * *", zone = "Europe/Vienna")
  void run() {
    for (String school :
        db.queryForList("select id from school where retention_days is not null", String.class))
      service.sweep(school);
  }
}

@RestController
class Retention {
  final RetentionService service;
  final JdbcTemplate db;

  Retention(RetentionService service, JdbcTemplate db) {
    this.service = service;
    this.db = db;
  }

  @GetMapping(BASE + "/retention")
  RetentionService.Policy policy(Authentication auth, @PathVariable String school) {
    Security.member(db, auth, school).allow("ADMIN");
    return service.policy(school);
  }

  @GetMapping(BASE + "/retention/preview")
  RetentionService.Preview preview(
      Authentication auth, @PathVariable String school, @RequestParam int days) {
    Security.member(db, auth, school).allow("ADMIN");
    return service.preview(school, days);
  }

  @PutMapping(BASE + "/retention")
  void save(
      Authentication auth, @PathVariable String school, @RequestBody RetentionService.Policy p) {
    service.set(Security.member(db, auth, school), p);
  }
}
