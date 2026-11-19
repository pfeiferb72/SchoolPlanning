package at.spps;

import static at.spps.Api.*;
import static at.spps.Model.*;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class Templates {
  record Rule(
      String subject,
      int hours,
      int block,
      String week,
      List<String> equipment,
      List<String> resources,
      List<String> teachers) {}

  record Curriculum(String name, String schoolType, List<Rule> rules) {}

  record Apply(long revision, UUID template, String prefix, List<String> groups) {}

  final Store store;

  Templates(Store s) {
    store = s;
  }

  @GetMapping(BASE + "/templates")
  Object list(Authentication a, @PathVariable String school) {
    Security.member(store.database(), a, school).allow("PLAN", "OFFICE", "DIRECTION");
    return store
        .database()
        .queryForList(
            "select id,name,school_type from curriculum where school_id=? order by name", school);
  }

  @PostMapping(BASE + "/templates")
  @Transactional
  Object save(Authentication a, @PathVariable String school, @RequestBody Curriculum body) {
    var m = Security.member(store.database(), a, school);
    m.allow("PLAN", "OFFICE");
    require(!body.name.isBlank() && !body.rules.isEmpty(), "Name und Fächer erforderlich");
    var id = UUID.randomUUID();
    store
        .database()
        .update(
            "insert into curriculum values(?,?,?,?,?::jsonb)",
            id,
            school,
            body.name,
            body.schoolType,
            store.encode(body.rules));
    store.audit(m, "Stundentafel angelegt " + id);
    return Map.of("id", id);
  }

  @PostMapping(PLAN + "/curriculum")
  @Transactional
  void apply(
      Authentication a, @PathVariable String school, @PathVariable UUID id, @RequestBody Apply body)
      throws Exception {
    var m = Security.member(store.database(), a, school);
    m.allow("PLAN", "OFFICE");
    var p = store.edit(m, id, body.revision).plan();
    var rules =
        store
            .database()
            .queryForList(
                "select rules::text from curriculum where id=? and school_id=? and school_type=?",
                String.class,
                body.template,
                school,
                p.catalog.schoolType)
            .stream()
            .findFirst()
            .orElseThrow(Store::missing);
    Rule[] rows = store.mapper().readValue(rules, Rule[].class);
    for (int i = 0; i < rows.length; i++) {
      var r = rows[i];
      p.catalog.courses.add(
          new Course(
              body.prefix + "-" + (i + 1),
              r.subject,
              body.groups,
              r.teachers,
              r.hours,
              r.block,
              r.week,
              r.equipment,
              r.resources));
    }
    store.save(m, id, body.revision, p, "Unterricht aus Stundentafel");
  }
}
