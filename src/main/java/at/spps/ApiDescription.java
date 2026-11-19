package at.spps;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration
class ApiDescription {
  @Bean
  OpenAPI schoolPlanningApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("SchoolPlanning")
                .version("1.0")
                .description(
                    "Mandantenbezogene Stundenplanung. Sitzung über OIDC oder lokales Demoprofil."
                        + " GET /session liefert X-CSRF-TOKEN für schreibende Anfragen. Gemeinsame"
                        + " Änderungen verwenden den Revisionsstand des Szenarios. Zeitraster-Slots"
                        + " sind nullbasiert, Wochentage reichen von 1 (Montag) bis 5."))
        .servers(List.of(new Server().url("/")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "schoolSession",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("SESSION")))
        .addSecurityItem(new SecurityRequirement().addList("schoolSession"));
  }
}
