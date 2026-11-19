# Betrieb und Administration

## Lokale Umgebung

Compose startet PostgreSQL und den Java-Dienst, bindet die Weboberfläche an 127.0.0.1 und prüft die Datenbankbereitschaft. `GET /health` führt eine Datenbankabfrage aus. Die Demorollen verwenden synthetische Daten und sind absichtlich frei wählbar. Für jede Schulinstallation eine eigene Datenbank bzw. einen explizit provisionierten Mandanten anlegen.

## Anmeldung mit dem Schul-IdP

Das Produktionsprofil verwendet Spring Security OIDC mit Authorization Code Flow. Stelle ein eigenes Deployment mit externer Datenbank, TLS-Terminierung und folgenden Umgebungsvariablen bereit. Zugangsdaten gehören in die Secret-Verwaltung des Betreibers.

| Variable | Wert oder Bedeutung |
|---|---|
| SPRING_PROFILES_ACTIVE | oidc |
| DEMO_MODE | false |
| OIDC_ISSUER | Discovery-fähiger Issuer des Schul-IdP |
| OIDC_CLIENT_ID, OIDC_CLIENT_SECRET | Registrierte Webanwendung |
| ADMIN_SUB | Exaktes OIDC-Subject des ersten Schuladministrators |
| DB_URL, DB_USER, DB_PASSWORD | JDBC-Verbindung mit eigener Datenbankrolle |
| SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM | Mailserver und Absender |
| SMTP_STARTTLS | true für STARTTLS |

Registriere `https://<schulhost>/login/oauth2/code/school` als Callback. Die Basisseite bietet die Schulkonto-Anmeldung an. Der Reverse Proxy muss den öffentlichen HTTPS-Ursprung korrekt weiterreichen. Bei Termination am vertrauenswürdigen Proxy kann `SERVER_FORWARD_HEADERS_STRATEGY=framework` gesetzt werden. Blockiere direkte externe Zugriffe am Proxy vorbei. Für Admin-Konten MFA beim IdP erzwingen. Die Websitzung läuft nach 30 Minuten Inaktivität ab.

Beim ersten Start wird die Schule `schule` angelegt. Die Administration trägt unter **Benutzer und Rollen** die Subjects des IdP, Rollen, fachlichen Kürzel und optional E-Mail-Adressen ein. Unterrichtsnamen aus dem IdP werden nicht zur Rechtevergabe verwendet. Der Einstieg ins erste Szenario erfolgt über einen zugewiesenen Planer. Weitere Schulen können durch den Betreiber in einer Transaktion in `school` und `membership` provisioniert werden. Die Applikation schränkt Anfragen über die Schule der Mitgliedschaft ein.

## E-Mail und Datenhaltung

Veröffentlichungen erzeugen in derselben Transaktion einen In-App-Eintrag und Queue-Einträge für die abonnierten Empfänger. Bei Tagesvertretungen berücksichtigt der Versand betroffene Lehrkräfte, Klassen und Schüler. Auslieferungen verwenden SMTP mit einem Wiederholintervall von fünf Minuten bei Fehlern. Ohne SMTP-Konfiguration bleiben Einträge erhalten. Überwache Größe und Alter der Warteschlange. Bei einer Unterbrechung nach Übermittlung und vor Datenbank-Commit kann eine Nachricht doppelt ankommen.

Plane EU/EWR-Hosting, verschlüsselte Volumes und Backups, TLS, Betreiberrollen und dokumentierte Supportzugriffe für den gewählten Infrastrukturbetreiber. Infrastruktur- und Betreiberverträge sind Bestandteile der konkreten Installation. Websitzungen und Solver-Status liegen in PostgreSQL. Mehrere App-Instanzen können dieselbe Datenbank verwenden. Der Worker berechnet einen Vorschlag im Speicher. Andere Instanzen können Fortschritt lesen oder den Abbruch anfordern. Bei Workerausfall wird ein verwaister Lauf nach ausbleibendem Lebenszeichen als unterbrochen markiert. Bereits gespeicherte Entwürfe bleiben erhalten.

## Sicherung und Restore-Probe

`sh ops/backup.sh /geschuetzter/pfad` erstellt und prüft einen PostgreSQL-Dump. Beispielsweise kann ein Systemtimer täglich um 02:00 Uhr im Projektverzeichnis diesen Befehl starten. Bewahre die Sicherung verschlüsselt in einem unabhängigen Speicher auf. Die Aufbewahrungsdauer wird mit dem Betreiber vereinbart. Eine Kopie auf demselben Host deckt dessen Ausfall nicht ab.

Für eine Restore-Probe eine zusätzliche leere PostgreSQL-Datenbank bereitstellen und den Dump einspielen:

```bash
pg_restore --exit-on-error --no-owner --dbname="$RESTORE_DATABASE_URL" schoolplanning.dump
```

Den Dienst gegen die wiederhergestellte Datenbank starten. Anmelden, eine Veröffentlichung öffnen, einen geschützten Schülerexport und das Änderungsprotokoll prüfen. Zeitpunkt der Sicherung und Wiederanlaufdauer dokumentieren. RPO und RTO benötigen einen gemessenen Wiederanlauf im Betriebsumfeld.

## Offboarding und Löschung

Für das Offboarding liefert die Administration ein JSON-Archiv mit Schul-, Plan-, Versions-, Kommunikations- und Berechtigungsdaten. Nach Freigabe und Sicherung kann der Datenbankbetreiber den betreffenden Schuleintrag transaktional löschen. Die Fremdschlüssel entfernen die abhängigen Datensätze. Die Aufbewahrungsregel muss auch Backups und freigegebene externe Kopien umfassen. Für die selektive personenbezogene Löschung in historischen Fassungen ist ein geprüftes Betriebskonzept erforderlich.

## Kubernetes

Die Vorlage in `ops/kubernetes.yaml` enthält Deployment, Service und tägliche Sicherung. Ersetze den Imagenamen durch den Digest des selbst gebauten Images in der Schul-Registry. Lege `schoolplanning-config` mit den OIDC-, SMTP- und DB-Variablen sowie `schoolplanning-backup` mit PGHOST, PGPORT, PGDATABASE, PGUSER und PGPASSWORD an. Provisioniere die Backup-PVC `schoolplanning-backups` auf einem unabhängig gesicherten Volume. TLS-Ingress, MFA-Richtlinie, Datenbank-HA, Speicherklasse, Zugriffsprotokollierung und Wiederherstellungstest gehören zur jeweiligen Installation.
