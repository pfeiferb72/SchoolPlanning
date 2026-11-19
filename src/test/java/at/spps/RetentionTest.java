package at.spps;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spps.demo=true")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
@Transactional
class RetentionTest {
  @Autowired RetentionService retention;
  @Autowired JdbcTemplate db;

  @Test
  void expirationRespectsPurposeEndTenantAndConfiguredOptIn() {
    String first = "qa-" + UUID.randomUUID(), other = "qa-" + UUID.randomUUID();
    var expired = LocalDate.of(2000, 1, 1);
    for (String school : java.util.List.of(first, other)) {
      db.update("insert into school(id,name) values(?,?)", school, "QA");
      db.update(
          "insert into membership(school_id,subject,role,purpose_until)"
              + " values(?,'retired','TEACHER',?)",
          school,
          expired);
      db.update("insert into membership(school_id,subject,role) values(?,'active','PLAN')", school);
      db.update(
          "insert into absence(school_id,teacher,day,reason) values(?,'T',?,'Fortbildung')",
          school,
          expired);
    }
    retention.sweep(first);
    assertEquals(1, retention.preview(first, 30).identities());
    retention.set(
        new Security.Member(first, "ADMIN", "", "admin"), new RetentionService.Policy(30));
    retention.sweep(first);
    assertEquals(0, retention.preview(first, 30).identities());
    assertEquals(0, retention.preview(first, 30).absences());
    assertEquals(1, retention.preview(other, 30).identities());
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from membership where school_id=?", Integer.class, first));
  }
}
