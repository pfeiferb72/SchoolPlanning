# SchoolPlanning

Deutschsprachige Webanwendung für Stunden-, Vertretungs- und Prüfungsplanung. Ein gemeinsamer Regelprüfer bewertet manuelle Platzierungen, Solver-Vorschläge und den Plan vor der Freigabe.

## Lokal starten

Voraussetzung ist Docker mit Compose v2.

```bash
git clone https://github.com/pfeiferb72/SchoolPlanning.git
cd SchoolPlanning
docker compose up --build -d
```

Öffne **http://localhost:8080**, wähle **Stundenplanung** und klicke auf **Arbeitsplatz öffnen**. Beim ersten Start wird die fiktive Schule *Campus Alpenblick* mit zwei Klassen und einem bearbeitbaren Wintersemester angelegt. Der erste Build lädt Maven-Abhängigkeiten und Container-Images. Bei einem belegten Port startet `PORT=8081 docker compose up -d` die Anwendung auf Port 8081.

```bash
docker compose logs -f app
docker compose stop
docker compose start
```

PostgreSQL speichert die Daten im benannten Volume `schooldata`. Der Compose-Einstieg ist eine lokale Demo mit frei wählbaren Rollen. Für Schulbetrieb stehen ein eigenes OIDC-Profil und eine getrennte Betriebsanleitung bereit.

## Erster Durchlauf

1. Im **Wochenplan** einen offenen Block anklicken oder auf eine Rasterzelle ziehen. Raum und Lehrkraft wählen. Eine unzulässige Platzierung wird mit Ursache angezeigt.
2. **Solver → Plan berechnen** starten. Der fertige Vorschlag erscheint im Entwurf und kann mit **Zurück** rückgängig gemacht werden.
3. Unter **Planqualität** offene Blöcke und Lehrverpflichtungen prüfen. Den vollständigen Entwurf **zur Prüfung einreichen**.
4. Abmelden, die Demorolle **Schulleitung** öffnen, den Plan freigeben und veröffentlichen.
5. In **Veröffentlichungen** einen Zeitraum innerhalb des geplanten Semesters wählen. Für die Beispieldaten eignet sich der 14.–18. September 2026. Die Schülerrolle sieht die Termine des zugeordneten Schülers.
6. In der Rolle **Stundenplanung** einen Unterrichtstag mit Abwesenheit erfassen, die Vorschlagsliste prüfen und veröffentlichen. Der freigegebene Tagesplan übernimmt die Vertretung.

## Technik und Dokumentation

Java 21, Spring Boot, Spring Security, PostgreSQL 16, Flyway und Vue 3. Die Anwendung liefert die SPA und ihre Abhängigkeiten vom eigenen Server. Die Solver-Engine verwendet eine zeitbegrenzte Mehrstart-Heuristik. ISO-Wochenbeginn ist Montag, Schulzeitzone ist Europe/Vienna.

| Thema | Einstieg |
|---|---|
| Bedienung, Rollen und Schulungsübung | [Benutzerhandbuch](docs/BEDIENUNG.md) |
| Installation, OIDC, SMTP, Sicherung und Wiederherstellung | [Betrieb und Administration](docs/BETRIEB.md) |
| Datenmodell, Planung und Schnittstellen | [Architektur](docs/ARCHITEKTUR.md) |
| API | [OpenAPI-Datei](docs/openapi.json), nach Anmeldung auch unter `/api-docs` und `/v3/api-docs` |
| Verifikation und Abnahmestand | [Testbericht](docs/TESTS.md), [Abgleich mit dem Pflichtenheft](docs/ABNAHME.md) |

Das Pflichtenheft verlangt zusätzlich einen semesterlangen Betrieb an zwei Pilotschulen sowie unabhängige Prüfungen. Der jeweilige Nachweisstand ist im Abgleich dokumentiert.
