package at.spps;

import static at.spps.Model.*;
import static at.spps.Security.Member;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Store implements ApplicationRunner {
  final JdbcTemplate db;
  final ObjectMapper json;
  final boolean demo;
  final String admin;

  Store(
      JdbcTemplate db,
      ObjectMapper json,
      @Value("${spps.demo}") boolean demo,
      @Value("${spps.bootstrap-sub}") String admin) {
    this.db = db;
    this.json = json;
    this.demo = demo;
    this.admin = admin;
  }

  public JdbcTemplate database() {
    return db;
  }

  public ObjectMapper mapper() {
    return json;
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  Plan decode(String raw) {
    try {
      return json.readValue(raw, Plan.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    db.execute("select pg_advisory_xact_lock(47266081)");
    String school = demo ? "demo" : "schule";
    boolean fresh =
        db.update(
                "insert into school(id,name) values(?,?) on conflict do nothing",
                school,
                demo ? "Campus Alpenblick" : "Meine Schule")
            > 0;
    if (demo) {
      for (String role : Security.ROLES) {
        String entity =
            switch (role) {
              case "TEACHER" -> "WEB";
              case "CURATOR" -> "1A";
              case "STUDENT", "GUARDIAN" -> "S01";
              default -> "";
            };
        db.update(
            "insert into membership(school_id,subject,role,entity) values(?,?,?,?) on conflict do"
                + " nothing",
            school,
            role.toLowerCase(Locale.ROOT),
            role,
            entity);
      }
      if (fresh)
        create(
            new Member(school, "PLAN", "", "system"),
            "Wintersemester · Arbeitsplan",
            Example.school());
    } else if (!admin.isBlank())
      db.update(
          "insert into membership(school_id,subject,role) values(?,?,'ADMIN') on conflict do"
              + " nothing",
          school,
          admin);
  }

  record Scenario(UUID id, String name, String status, long revision, int cursor, Plan plan) {}

  record Published(long id, Plan plan) {}

  static RuntimeException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  static void stale() {
    throw new ResponseStatusException(
        HttpStatus.CONFLICT, "Der Stand hat sich geändert. Bitte neu laden.");
  }

  Scenario get(Member m, UUID id, boolean lock) {
    return db
        .query(
            "select id,name,status,revision,cursor,document from scenario where school_id=? and"
                + " id=?"
                + (lock ? " for update" : ""),
            (r, n) ->
                new Scenario(
                    r.getObject(1, UUID.class),
                    r.getString(2),
                    r.getString(3),
                    r.getLong(4),
                    r.getInt(5),
                    decode(r.getString(6))),
            m.school(),
            id)
        .stream()
        .findFirst()
        .orElseThrow(Store::missing);
  }

  Published published(Member m, java.time.LocalDate date) {
    String filter =
        date == null
            ? ""
            : " and (document->'catalog'->>'from')::date<=? and"
                + " (document->'catalog'->>'until')::date>=?";
    Object[] args =
        date == null ? new Object[] {m.school()} : new Object[] {m.school(), date, date};
    return db
        .query(
            "select id,document from publication where school_id=?"
                + filter
                + " order by id desc limit 1",
            (r, n) -> new Published(r.getLong(1), decode(r.getString(2))),
            args)
        .stream()
        .findFirst()
        .orElseThrow(Store::missing);
  }

  void audit(Member m, String event) {
    db.update(
        "insert into audit(school_id,actor,event) values(?,?,?)", m.school(), m.actor(), event);
  }

  void notify(
      Member m,
      String msg,
      boolean covers,
      Set<String> staff,
      Set<String> groups,
      Set<String> students) {
    long notice =
        db.queryForObject(
            "insert into notice(school_id,message) values(?,?) returning id",
            Long.class,
            m.school(),
            msg);
    String subscription = covers ? "subscribe_cover" : "subscribe_plan";
    var recipients =
        db.queryForList(
            "select subject,email,role,entity from membership where school_id=? and (purpose_until"
                + " is null or purpose_until >= (now() at time zone 'Europe/Vienna')::date) and "
                + subscription,
            m.school());
    for (var row : recipients) {
      String entity = (String) row.get("entity");
      boolean affected =
          switch ((String) row.get("role")) {
            case "TEACHER" -> staff.contains(entity);
            case "CURATOR" -> groups.contains(entity);
            case "STUDENT", "GUARDIAN" -> students.contains(entity);
            default -> true;
          };
      if (!affected) continue;
      db.update("insert into inbox values(?,?,?)", notice, m.school(), row.get("subject"));
      if (!row.get("email").toString().isBlank())
        db.update(
            "insert into mail_queue(school_id,recipient,message) values(?,?,?)",
            m.school(),
            row.get("email"),
            msg);
    }
  }

  @Transactional
  public UUID create(Member m, String name, Plan plan) {
    m.allow("PLAN");
    require(name != null && !name.isBlank() && name.length() <= 120, "Szenarionamen angeben");
    Engine.inspect(plan);
    var id = UUID.randomUUID();
    String body = encode(plan);
    db.update(
        "insert into scenario(id,school_id,name,document) values(?,?,?,?::jsonb)",
        id,
        m.school(),
        name,
        body);
    db.update(
        "insert into revision(scenario_id,step,document,author) values(?,0,?::jsonb,?)",
        id,
        body,
        m.actor());
    audit(m, "Szenario angelegt " + id);
    return id;
  }

  Scenario edit(Member m, UUID id, long revision) {
    var s = get(m, id, true);
    if (s.revision != revision) stale();
    require(s.status.equals("DRAFT"), "Nur Entwürfe können bearbeitet werden");
    return s;
  }

  @Transactional
  public void save(Member m, UUID id, long revision, Plan plan, String event) {
    var s = edit(m, id, revision);
    Engine.inspect(plan);
    String body = encode(plan);
    db.update("delete from revision where scenario_id=? and step>?", id, s.cursor);
    db.update(
        "insert into revision(scenario_id,step,document,author) values(?,?,?::jsonb,?)",
        id,
        s.cursor + 1,
        body,
        m.actor());
    db.update(
        "update scenario set revision=revision+1,cursor=cursor+1,document=?::jsonb where id=?",
        body,
        id);
    audit(m, event + " " + id);
  }

  @Transactional
  public void history(Member m, UUID id, long revision, int direction) {
    m.allow("PLAN");
    var s = edit(m, id, revision);
    int step = s.cursor + direction;
    require(
        db.update(
                "update scenario set document=r.document,cursor=r.step,revision=revision+1 from"
                    + " revision r where scenario.id=r.scenario_id and scenario.id=? and r.step=?",
                id,
                step)
            == 1,
        "Kein weiterer Änderungsschritt");
    audit(m, direction < 0 ? "Rückgängig " + id : "Wiederhergestellt " + id);
  }

  @Transactional
  public void transition(Member m, UUID id, long revision, String next) {
    var s = get(m, id, true);
    if (s.revision != revision) stale();
    String expected =
        switch (next) {
          case "REVIEW" -> {
            m.allow("PLAN");
            yield "DRAFT";
          }
          case "APPROVED" -> {
            m.allow("DIRECTION");
            yield "REVIEW";
          }
          case "PUBLISHED" -> {
            m.allow("PLAN", "DIRECTION");
            yield "APPROVED";
          }
          case "DRAFT" -> {
            m.allow("PLAN", "DIRECTION");
            yield s.status.equals("APPROVED") ? "APPROVED" : "REVIEW";
          }
          default -> throw new IllegalArgumentException("Unbekannter Status");
        };
    require(s.status.equals(expected), "Unzulässiger Statuswechsel");
    if (!next.equals("DRAFT")) {
      var issues = Engine.inspect(s.plan).metrics(s.plan).outstanding();
      require(issues.isEmpty(), String.join(" · ", issues));
    }
    if (next.equals("PUBLISHED")) {
      schoolLock(m);
      db.update(
          "insert into publication(school_id,scenario_id,document) select school_id,id,document"
              + " from scenario where id=?",
          id);
      var c = s.plan.catalog;
      notify(
          m,
          "Stundenplan veröffentlicht: " + s.name,
          false,
          index(c.teachers, Teacher::id).keySet(),
          index(c.groups, Group::id).keySet(),
          index(c.students, Student::id).keySet());
    }
    db.update("update scenario set status=?,revision=revision+1 where id=?", next, id);
    audit(m, next + " " + id);
  }

  void schoolLock(Member m) {
    db.queryForObject("select id from school where id=? for update", String.class, m.school());
  }
}
