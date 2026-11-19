package at.spps;

import static at.spps.Api.*;
import static at.spps.Model.*;
import static at.spps.Security.Member;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class Integrations {
  final Store store;
  final Views views;
  final ObjectMapper json;

  Integrations(Store s, Views v) {
    store = s;
    views = v;
    json = s.mapper();
  }

  Member member(org.springframework.security.core.Authentication a, String school) {
    return Security.member(store.database(), a, school);
  }

  static final Map<String, Class<?>> TYPES =
      Map.ofEntries(
          Map.entry("teachers", Teacher.class),
          Map.entry("groups", Group.class),
          Map.entry("students", Student.class),
          Map.entry("subjects", Subject.class),
          Map.entry("rooms", Room.class),
          Map.entry("courses", Course.class),
          Map.entry("bookings", Booking.class),
          Map.entry("periods", Model.Period.class),
          Map.entry("exams", Exam.class));
  static final CSVFormat CSV =
      CSVFormat.DEFAULT.builder().setDelimiter(';').setIgnoreEmptyLines(true).get();

  @GetMapping(PLAN + "/master/{type}.csv")
  ResponseEntity<String> download(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @PathVariable String type)
      throws IOException {
    var m = member(a, school);
    m.allow("PLAN", "OFFICE");
    require(TYPES.containsKey(type), "Unbekannte Tabelle");
    var data = json.valueToTree(store.get(m, id, false).plan().catalog).get(type);
    var out = new StringWriter();
    var fields = TYPES.get(type).getRecordComponents();
    try (var csv = new CSVPrinter(out, CSV)) {
      csv.printRecord(Arrays.stream(fields).map(x -> x.getName()).toList());
      for (var row : data) {
        var vals = new ArrayList<String>();
        for (var f : fields) {
          var v = row.get(f.getName());
          vals.add(v.isNull() ? "" : v.isTextual() ? Exports.safeCsv(v.asText()) : v.toString());
        }
        csv.printRecord(vals);
      }
    }
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=" + type + ".csv")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(out.toString());
  }

  record Import(long revision, String csv, boolean apply) {}

  @PostMapping(PLAN + "/master/{type}/import")
  @Transactional
  Object upload(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @PathVariable UUID id,
      @PathVariable String type,
      @RequestBody Import req)
      throws IOException {
    var m = member(a, school);
    m.allow("PLAN", "OFFICE");
    require(
        TYPES.containsKey(type) && req.csv.length() <= 5_000_000,
        "Unbekannte Tabelle oder Datei zu groß");
    var p = store.edit(m, id, req.revision).plan();
    ObjectNode cat = json.valueToTree(p.catalog);
    var fields = TYPES.get(type).getRecordComponents();
    var rows = new LinkedHashMap<String, JsonNode>();
    cat.get(type).forEach(r -> rows.put(r.get("id").asText(), r));
    var seen = new HashSet<String>();
    int added = 0, updated = 0;
    var errors = new ArrayList<String>();
    try (var parser =
        CSV.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(new StringReader(req.csv.replaceFirst("^\uFEFF", "")))) {
      require(
          new HashSet<>(parser.getHeaderNames())
              .equals(new HashSet<>(Arrays.stream(fields).map(f -> f.getName()).toList())),
          "Spalten stimmen nicht mit der CSV-Vorlage überein");
      for (var r : parser) {
        try {
          ObjectNode node = json.createObjectNode();
          for (var f : fields) {
            var raw = r.get(f.getName());
            if (f.getType().equals(List.class) || f.getType().equals(int.class))
              node.set(f.getName(), json.readTree(raw));
            else if (raw.isBlank() && f.getType().equals(LocalDate.class))
              node.putNull(f.getName());
            else node.put(f.getName(), raw);
          }
          String key = node.path("id").asText();
          require(seen.add(key), "Kürzel doppelt: " + key);
          var old = rows.put(key, node);
          if (old == null) added++;
          else if (!old.equals(node)) updated++;
        } catch (Exception e) {
          errors.add("Zeile " + (r.getRecordNumber() + 1) + ": " + e.getMessage());
        }
      }
    }
    cat.set(type, json.valueToTree(rows.values()));
    try {
      p.catalog = json.treeToValue(cat, Catalog.class);
      Engine.inspect(p);
    } catch (Exception e) {
      errors.add(Objects.toString(e.getMessage(), "Ungültige Daten"));
    }
    if (req.apply && errors.isEmpty()) store.save(m, id, req.revision, p, "CSV-Import " + type);
    return Map.of(
        "added",
        added,
        "updated",
        updated,
        "unchanged",
        Math.max(0, seen.size() - added - updated),
        "errors",
        errors,
        "applied",
        req.apply && errors.isEmpty());
  }

  record Grant(String subject, String role, String entity, String email) {}

  @GetMapping(BASE + "/members")
  Object members(org.springframework.security.core.Authentication a, @PathVariable String school) {
    member(a, school).allow("ADMIN");
    return store
        .database()
        .queryForList(
            "select subject,role,entity,email from membership where school_id=? order by subject",
            school);
  }

  @PostMapping(BASE + "/members")
  @Transactional
  void grant(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @RequestBody Grant g) {
    var m = member(a, school);
    m.allow("ADMIN");
    require(
        Arrays.asList(Security.ROLES).contains(g.role) && !g.subject.isBlank(),
        "Rolle oder Identität ungültig");
    require(
        g.email.isBlank() || g.email.matches("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+"),
        "E-Mail-Adresse ungültig");
    require(
        !Set.of("TEACHER", "CURATOR", "STUDENT", "GUARDIAN").contains(g.role)
            || !g.entity.isBlank(),
        "Personen- oder Klassenkürzel erforderlich");
    require(
        !m.actor().equals(g.subject) || g.role.equals("ADMIN"),
        "Eigenen Administrationszugang beibehalten");
    store
        .database()
        .update(
            "insert into membership(school_id,subject,role,entity,email) values(?,?,?,?,?) on"
                + " conflict(school_id,subject) do update set"
                + " role=excluded.role,entity=excluded.entity,email=excluded.email",
            school,
            g.subject,
            g.role,
            g.entity,
            g.email);
    store.audit(m, "Berechtigung geändert " + g.subject);
  }

  @DeleteMapping(BASE + "/members/{subject}")
  @Transactional
  void revoke(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @PathVariable String subject) {
    var m = member(a, school);
    m.allow("ADMIN");
    require(!subject.equals(m.actor()), "Eigenen Zugang beibehalten");
    store
        .database()
        .update("delete from membership where school_id=? and subject=?", school, subject);
    store.audit(m, "Zugang entzogen " + subject);
  }

  @GetMapping(BASE + "/audit")
  Object audit(org.springframework.security.core.Authentication a, @PathVariable String school) {
    member(a, school).allow("ADMIN", "DIRECTION");
    return store
        .database()
        .queryForList(
            "select actor,event,created from audit where school_id=? order by id desc limit 500",
            school);
  }

  @GetMapping(BASE + "/offboarding")
  @Transactional
  Object offboard(org.springframework.security.core.Authentication a, @PathVariable String school)
      throws Exception {
    var m = member(a, school);
    m.allow("ADMIN");
    store.schoolLock(m);
    store.audit(m, "Mandantenexport");
    var out = json.createObjectNode();
    Map<String, String> tables = new LinkedHashMap<>();
    tables.put("school", "select * from school where id=?");
    for (String table :
        List.of(
            "membership",
            "scenario",
            "publication",
            "absence",
            "notice",
            "audit",
            "wish",
            "mail_queue",
            "feed",
            "curriculum",
            "inbox",
            "solver_job")) tables.put(table, "select * from " + table + " where school_id=?");
    tables.put(
        "revision",
        "select r.* from revision r join scenario s on s.id=r.scenario_id where s.school_id=?");
    tables.put(
        "cover",
        "select c.* from cover c join publication p on p.id=c.publication_id where p.school_id=?");
    for (var e : tables.entrySet())
      out.set(
          e.getKey(),
          json.readTree(
              store
                  .database()
                  .queryForObject(
                      "select coalesce(jsonb_agg(q),'[]')::text from (" + e.getValue() + ") q",
                      String.class,
                      school)));
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=schularchiv.json")
        .body(out);
  }

  @GetMapping(BASE + "/accounts")
  Object accounts(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @RequestParam LocalDate from,
      @RequestParam LocalDate until) {
    var m = member(a, school);
    m.allow("PLAN", "DIRECTION", "TEACHER");
    return store
        .database()
        .queryForList(
            "select c.replacement as teacher,sum(c.hours) as hours from cover c join publication p"
                + " on c.publication_id=p.id where p.school_id=? and c.day between ? and ? and"
                + " c.replacement<>'' and (?<>'TEACHER' or c.replacement=?) group by c.replacement"
                + " order by teacher",
            school,
            from,
            until,
            m.role(),
            m.entity());
  }

  record Subscriptions(boolean plans, boolean covers) {}

  @GetMapping(BASE + "/subscriptions")
  Subscriptions subscriptions(
      org.springframework.security.core.Authentication a, @PathVariable String school) {
    var m = member(a, school);
    return store
        .database()
        .queryForObject(
            "select subscribe_plan,subscribe_cover from membership where school_id=? and subject=?",
            (r, n) -> new Subscriptions(r.getBoolean(1), r.getBoolean(2)),
            school,
            m.actor());
  }

  @PutMapping(BASE + "/subscriptions")
  @Transactional
  void subscriptions(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @RequestBody Subscriptions value) {
    var m = member(a, school);
    store
        .database()
        .update(
            "update membership set subscribe_plan=?,subscribe_cover=? where school_id=? and"
                + " subject=?",
            value.plans,
            value.covers,
            school,
            m.actor());
    store.audit(m, "Benachrichtigungsabo geändert");
  }

  static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  record Scope(String scope, String entity) {}

  @PostMapping(BASE + "/feeds")
  @Transactional
  Object makeFeed(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @RequestBody Scope spec) {
    var m = member(a, school);
    m.allow("PLAN", "DIRECTION");
    var c = store.published(m, null).plan().catalog;
    require(
        spec.scope.equals("group")
            ? index(c.groups, Group::id).containsKey(spec.entity)
            : spec.scope.equals("teacher")
                && index(c.teachers, Teacher::id).containsKey(spec.entity),
        "Unbekannte Klasse oder Lehrkraft");
    String token = UUID.randomUUID().toString() + UUID.randomUUID();
    store
        .database()
        .update(
            "insert into feed(token_hash,school_id,scope,entity) values(?,?,?,?)",
            digest(token),
            school,
            spec.scope,
            spec.entity);
    store.audit(m, "Kalenderfreigabe " + spec.entity);
    return Map.of("calendar", "/feeds/" + token + ".ics", "widget", "/feeds/" + token + ".html");
  }

  @GetMapping(BASE + "/feeds")
  Object feeds(org.springframework.security.core.Authentication a, @PathVariable String school) {
    member(a, school).allow("PLAN", "DIRECTION");
    return store
        .database()
        .queryForList("select token_hash,scope,entity from feed where school_id=?", school);
  }

  @DeleteMapping(BASE + "/feeds/{hash}")
  @Transactional
  void removeFeed(
      org.springframework.security.core.Authentication a,
      @PathVariable String school,
      @PathVariable String hash) {
    var m = member(a, school);
    m.allow("PLAN", "DIRECTION");
    store.database().update("delete from feed where token_hash=? and school_id=?", hash, school);
    store.audit(m, "Kalenderfreigabe widerrufen");
  }

  @GetMapping("/feeds/{token}.{fmt}")
  ResponseEntity<byte[]> feed(@PathVariable String token, @PathVariable String fmt)
      throws IOException {
    require(Set.of("ics", "html").contains(fmt), "Format ungültig");
    var row =
        store
            .database()
            .queryForList(
                "select school_id,scope,entity from feed where token_hash=?", digest(token))
            .stream()
            .findFirst()
            .orElseThrow(Store::missing);
    String school = (String) row.get("school_id");
    var now = LocalDate.now(ZoneId.of("Europe/Vienna"));
    var from = fmt.equals("html") ? now : now.minusDays(7);
    return Exports.render(
        views.events(
            new Member(school, "PLAN", "", "feed"),
            from,
            now.plusDays(fmt.equals("html") ? 6 : 365),
            (String) row.get("scope"),
            (String) row.get("entity")),
        fmt,
        school);
  }
}

@Service
class MailDelivery {
  final Store store;
  final JavaMailSender mail;
  final String host, sender;

  MailDelivery(
      Store s,
      JavaMailSender mail,
      @Value("${spring.mail.host}") String host,
      @Value("${spps.sender}") String sender) {
    store = s;
    this.mail = mail;
    this.host = host;
    this.sender = sender;
  }

  @Scheduled(fixedDelay = 30000)
  @Transactional
  public void deliver() {
    if (host.isBlank()) return;
    for (var row :
        store
            .database()
            .queryForList(
                "select id,recipient,message from mail_queue where retry_at<=now() order by id"
                    + " limit 10 for update skip locked")) {
      try {
        var msg = new SimpleMailMessage();
        msg.setFrom(sender);
        msg.setTo((String) row.get("recipient"));
        msg.setSubject("SchoolPlanning · Planänderung");
        msg.setText((String) row.get("message"));
        mail.send(msg);
        store.database().update("delete from mail_queue where id=?", row.get("id"));
      } catch (org.springframework.mail.MailException e) {
        store
            .database()
            .update(
                "update mail_queue set attempts=attempts+1,retry_at=now()+interval '5 minutes'"
                    + " where id=?",
                row.get("id"));
      }
    }
  }
}
