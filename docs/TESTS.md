# Testbericht

Stand 19. November 2026. Die Prüfung erfolgte mit OpenJDK 21, Maven 3.9, einem echten PostgreSQL-16.4-Server und Chromium unter Linux. Zur Prüfung liefen PostgreSQL und die gepackte Spring-Boot-JAR als lokale Prozesse. Der vollständige Compose-Start mit anschließendem Browsertest wurde zusätzlich in GitHub Actions erfolgreich geprüft. Der Kubernetes-Einsatz ist im Zielcluster zu erproben.

| Prüfung | Ergebnis |
|---|---|
| `mvn clean verify` | 18 erfolgreiche JUnit-Ausführungen bei gesetzter DB_URL, keine Fehler oder übersprungenen Tests |
| JaCoCo, Zeilenabdeckung | Engine 169/171 (98,8 %), CatalogRules 143/146 (97,9 %). Die Build-Grenze liegt für jede der beiden Klassen bei 90 %. |
| Fünf synthetische Schulartvarianten | Vollständige Platzierung, exakte Lehrlast und gewichteter Score ≤ 60 auf jedem kleinen Referenzraster |
| Regelprüfungen | A/B-Rhythmus, Blockgrenzen, Überschneidungen, Kopplung und Teilung, Räume, Geräte, Teamteaching, Qualifikation, Deputat, Präsenz, Mindestpausen, Sperren, Feiertage, Prüfungskollisionen |
| Vertretung und Kalender | Fachvertretung, Betreuung, Kapazitätsgrenzen der Zusammenlegung, Ausfall, Tagesauswahl, Sommer-/Winterzeit und iCalendar-Zeilenfaltung |
| Browser mit laufendem Backend | Demo-Anmeldung und Rollenwechsel, Stammdatenansicht, Platzierungsdialog, CSV-Vorschau, Revisionskonflikt, Undo/Redo, Solver mit Fixierung, Workflow, getrennte Leserrechte, Vertretung, PDF/CSV/ICS/HTML, Feed, Admin-Export, OpenAPI und 360-px-Leseransicht |
| Datenbankmigration | Erstinitialisierung inklusive Demodaten, Neustart mit vorhandenen Plänen und Migrationen für verteilte Aufträge, Abos und Aufbewahrungsfristen |

Die Coverage bezieht sich auf ausführbare Zeilen. Die Prüfung der Rollentrennung enthält unter anderem den abgelehnten Freigabeversuch eines Planers, den abgelehnten Entwurfszugriff eines Schülers und den abgelehnten Zugriff auf eine fremde Schule.

Die zwei importierbaren AHS-Beispiele werden zusätzlich gegen die produktive Regelprüfung getestet. Der Integrationstest der Fristen verwendet eine echte PostgreSQL-Datenbank, zwei isolierte Testschulen und eine zurückgerollte Transaktion. Er prüft das aktivierte und deaktivierte Löschverfahren sowie die Trennung der Schulen. Ohne DB_URL überspringt Maven diesen Test.

## Synthetischer Größenversuch

`qa/benchmark.py` erzeugt 40 Klassen, 80 Lehrkräfte, 40 Räume und **4.000 einzeln zu platzierende Stunden** in zwei Wochen. Es gibt zwei Fächer pro Klasse, disjunkte Lehrerteams und jeweils einen geeigneten Raum. Alle zehn Slots pro Tag sind zu belegen. Mit einem Zeitbudget von 30 Sekunden ergaben sich folgende Messwerte:

| Ergebnis | Messwert |
|---|---:|
| Platzierte Stunden | 4.000 / 4.000 |
| Offene Verpflichtungen beim abschließenden Regelcheck | 0 |
| Laufzeit vom Startaufruf bis zum beobachteten Abschluss | 30,24 s |
| Gewichtete Strafpunkte | 9.200 |
| Abruf des gesamten Szenarios, P95 aus 20 lokalen HTTP-Abrufen | 54,9 ms |

Das Größenprofil hat feste Raum- und Lehrerzuordnungen und keine Konkurrenz mehrerer Klassen um dieselbe Lehrkraft. Für eine Leistungsabnahme müssen zusätzlich Engpässe, wechselnde Raumwahl, realistische Schülergruppen, parallele Nutzer und mehrere Schulen gemessen werden. Der HTTP-Messwert umfasst Abruf, Regelprüfung, Übertragung und JSON-Lesen, jedoch keine Browserdarstellung.

## Wiederholung

```bash
mvn clean verify
docker compose up --build -d
npm ci --prefix qa
(cd qa && npx playwright install chromium)
node qa/browser.cjs
python3 qa/benchmark.py
```

Die Skripte verwenden eine **dedizierte lokale Demodatenbank** und erzeugen zusätzliche Szenarien und Veröffentlichungen. Der Browsertest meldet eine fiktive Abwesenheit. Mit `APP_URL` kann die URL des Testsystems gesetzt werden und mit `CHROMIUM_BIN` ein vorhandener Browser. Die Workflowdatei führt Build, Coverage und Browserprüfung mit PostgreSQL aus.

Ausstehend sind unter anderem die Integration mit den tatsächlichen Schul-IdPs, SMLS und SMTP-Diensten, der Spitzenlastversuch mit 50 Mandanten, das externe Sicherheitsaudit, die vollständige Barrierefreiheitsprüfung und der Pilotbetrieb.
