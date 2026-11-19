# Architektur und Datenmodell

Der Browser lädt die Vue-Komponenten vom Java-Dienst. Änderungen laufen über die versionierte REST-API unter `/api/v1/schools/{school}`. Spring Security verwaltet die OIDC-Anmeldung und den CSRF-Schutz für schreibende Anfragen. Für eine Sitzung liefert `GET /session` die freigegebenen Schulen und den aktuellen Token für `X-CSRF-TOKEN`. Nach der Anmeldung ist ein neuer Token abzurufen.

| PostgreSQL-Tabelle | Bedeutung |
|---|---|
| school, membership | Schule, OIDC-Subject, Rolle und fachliche Zuordnung je Schule |
| scenario | Szenarioname, Workflowstatus, monotone Revision, Historiencursor und Arbeitsstand |
| revision | Transaktionale Schnappschüsse für Undo/Redo mit Zeitpunkt und Autor |
| publication | Bei Veröffentlichung eingefrorene Planfassung und Zeitpunkt |
| curriculum | Schulartspezifische Stundentafeln mit Unterrichts- und Kopplungsvorgaben |
| absence, cover | Datierte Abwesenheiten und veröffentlichte Vertretungen pro Planfassung |
| wish, notice, mail_queue | Wünsche, In-App-Mitteilungen und transaktionale E-Mail-Ausgangswarteschlange |
| feed, audit | Widerrufbare Freigaben als Token-Hash und Protokoll |

Stammdaten und Platzierungen liegen gemeinsam als typisierter JSONB-Schnappschuss im relationalen Versionsmodell. So behält ein veröffentlichter Plan seine damaligen Raum-, Personal- und Gruppendaten. Das Schema wird mit Flyway weiterentwickelt. Das Anlegen einer neuen Datenbank und der Neustart mit bestehenden Daten verwenden dieselbe Migration.

## Planungsregeln

Eine Veranstaltung erzeugt `Wochenstunden / Blocklänge` Unterrichtsblöcke. A und B wechseln ab dem Montag der ersten Semesterwoche. AB belegt beide Wochen. Ein Block von zwei Einheiten in Woche A verbraucht im Zweiwochenmittel eine Wochenstunde. Die Summe muss bei Veröffentlichung der Lehrverpflichtung entsprechen. Im Entwurf sind offene Stunden zulässig.

Jede Teilgruppe erhält ein atomares Kürzel. Besteht Klasse 1A aus zwei Hälften, hat die ganze Klasse beispielsweise die Kürzel L und R. Eine Teilgruppe verwendet nur L, die andere nur R. Zusätzlich prüft die Engine explizite Schülerzugehörigkeiten. Gekoppelte Klassen teilen sich eine Veranstaltung. Für Teamteaching werden alle eingetragenen Lehrkräfte gleichzeitig reserviert.

Der Regelprüfer indiziert die Belegung je Ressource, Wochentag und A/B-Woche als Bitmaske. Er berücksichtigt Blocklängen, Sperren, Qualifikation, Präsenzspanne, tatsächliche Pausendauer, Raumeigenschaften, Geräte, Prüfungen und Reservierungen. Eine datierte Buchung blockiert kollidierende wiederkehrende Platzierungen im betroffenen Wochenrhythmus. Dadurch bleibt der Wochenplan über das Semester gültig. Prüfungen haben eigene Uhrzeiten. Für Ferien werden keine Termine ausgegeben.

Der Solver erzeugt mehrere zulässige Aufbauversuche mit unterschiedlichen Reihenfolgen, bevorzugt längere Blöcke und bewertet mögliche Plätze nach den konfigurierten Gewichten. Zuerst zählt die Anzahl platzierter Blöcke, danach die Summe der Strafpunkte. Fixierungen und Reparaturfilter begrenzen die veränderbaren Stunden. Ein Lauf kann ein Teilergebnis liefern. Abbruch vor Übernahme verwirft das Ergebnis. Ein zwischenzeitlich geänderter Entwurf weist das Solver-Ergebnis über die Revisionsprüfung zurück.

## Transaktionen und Sichtbarkeit

Schreiboperationen sperren die Szenariozeile und vergleichen die vom Client gelesene Revision. HTTP 409 fordert zum Neuladen auf. Undo und Redo ändern den Historiencursor und erhöhen ebenfalls die Revision. Eine neue Bearbeitung nach Undo schneidet den Redo-Zweig ab. Jede Bearbeitung wird protokolliert. Eine veröffentlichte Variante kann als neuer Entwurf kopiert werden.

Leserzugriffe verwenden die jüngste freigegebene Veröffentlichung für das jeweilige Datum. Lehrkräfte sehen eigene Termine, Klassenvorstände die zugewiesene Klasse, Schüler und Eltern die zugeordnete Schüler-ID. Prüfungen werden anhand der Kandidatenliste gefiltert. Abwesenheitsgründe werden in Leserexporten nicht ausgegeben. Kalender-Abos und Widgets verwenden explizit erstellte, widerrufbare Freigaben für einen Ausschnitt.

## Integration

CSV-Import und -Export verwenden UTF-8, Semikolon und die Spaltennamen des jeweiligen Tabellendownloads. Listenfelder enthalten JSON-Arrays. Kürzel dienen als Abgleichsschlüssel. Vorschau und Übernahme liefern hinzugefügte, geänderte und identische Einträge sowie Konflikte. Ein fehlerhafter Abgleich schreibt keine Teilmenge. Das konkrete SMLS muss auf dieses Austauschformat abgebildet werden.

Die Kalenderausgabe erzeugt datierte VEVENT-Einträge mit stabilen UIDs und UTC-Zeitstempeln. Der Feed rolliert über die veröffentlichten Termine. Das HTML-Widget aktualisiert sich alle zwei Minuten. PDF und CSV verwenden dieselben berechtigungsgefilterten Termine. Das Einbetten eines Widgets erfolgt mit der erzeugten HTML-Adresse im src-Attribut eines iframe.

Die Planungsvorlagen für VS, MS, AHS, BHS/BMS und BS enthalten veränderbare Gewichtungen. Sie sind technische Startwerte. Rechtsverbindliche Stundentafeln sind je Schule und Bildungsdirektion fachlich zu hinterlegen. Bei unterjährig wechselnden Anstellungen ist der Planungszeitraum entsprechend zu teilen.
