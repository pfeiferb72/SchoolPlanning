package at.spps;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExamplePackTest {
  final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  Api.ScenarioImport read(String filename) throws Exception {
    try (var in = getClass().getResourceAsStream("/static/examples/" + filename + ".json")) {
      assertNotNull(in);
      return json.readValue(in, Api.ScenarioImport.class);
    }
  }

  @Test
  void referenceMeetsAllHoursAndContainsExactlyEightClassesAndThirtyTeachers() throws Exception {
    var draft = read("01-planungsstart").plan();
    var ref = read("02-referenzplan").plan();
    assertEquals(json.valueToTree(draft.catalog), json.valueToTree(ref.catalog));
    assertTrue(draft.places.isEmpty());
    Engine.inspect(draft);
    assertEquals(8, ref.catalog.groups.size());
    assertEquals(30, ref.catalog.teachers.size());
    assertEquals(200, ref.catalog.students.size());
    assertTrue(ref.catalog.teachers.stream().allMatch(t -> t.load() > 0));
    var metrics = Engine.inspect(ref).metrics(ref);
    assertTrue(metrics.outstanding().isEmpty(), metrics.outstanding().toString());
    assertEquals(metrics.total(), metrics.placed());
    int[] perGrade = {29, 31, 32, 32};
    for (int g = 1; g <= 4; g++)
      for (String suffix : List.of("A", "B")) {
        String group = g + suffix;
        assertEquals(
            perGrade[g - 1],
            ref.catalog.courses.stream()
                .filter(c -> c.groups().contains(group))
                .mapToInt(Model.Course::hours)
                .sum());
      }
  }
}
