# AHS-Unterstufe – Beispieldaten für SchoolPlanning

Die zwei JSON-Dateien enthalten identische Stammdaten. Jede Datei wird als eigener Entwurf importiert.

1. Die JSON-Dateien aus [den Beispieldaten](../src/main/resources/static/examples) herunterladen. SchoolPlanning auf den aktuellen Stand bringen und als **Stundenplanung** anmelden.
2. **Wochenplan → Szenario importieren** öffnen.
3. **01-planungsstart.json** für den leeren Plan oder **02-referenzplan.json** für den vollständig belegten Plan auswählen. **Als Entwurf importieren** anklicken.
4. Für einen direkten Vergleich beide Dateien importieren. Im offenen Szenario den Solver starten. Den Referenzplan kannst du unmittelbar zur Prüfung einreichen.

## Inhalte

Acht Klassen von 1A bis 4B, 200 fiktive Schüler, 30 fiktive Lehrkräfte, 15 Fächer und 15 Räume. Der Stundenplan enthält 248 Klassenwochenstunden in 230 Unterrichtsblöcken. Teamteaching in Digitaler Grundbildung der 1A und 1B ergibt insgesamt 250 Lehrerwochenstunden. Die kleinen individuellen Deputate gelten nur für diesen Unterstufenausschnitt. Keine realen Personen- oder Schuldaten.

Die Wochenstunden je Klasse betragen 29, 31, 32 und 32 von der 1. bis zur 4. Klasse. Das Gymnasialbeispiel verwendet Englisch und ab der 3. Klasse Latein. Fachverteilung angelehnt an die österreichische Gymnasial-Stundentafel, Anlage 1, sechster Teil. Die Berufsorientierung in der 3. und 4. Klasse ist integriert. Kalender, Klassen, Namen und Deputate sind Modellannahmen.

Quelle: https://ris.bka.gv.at/NormDokument.wxe?Abfrage=Bundesnormen&Anlage=1&FassungVom=2026-09-27&Gesetzesnummer=10008568

## Ausprobieren

- **Vertretung**: Den Referenzplan über Prüfung und Freigabe veröffentlichen. Für Montag, 23. November 2026, Lehrkraft L19 als abwesend melden. Unter Vertretungen den Vorschlag für diesen Tag berechnen.
- **Prüfung**: Für Dienstag, 24. November 2026, ist um 15:15 Uhr eine mündliche Lateinprüfung mit zwei Kandidaten der 4A angelegt.
- **Ressourcen**: Bei Chemie, Physik, Technik und Design, Kunst, Musik, Sport und Digitaler Grundbildung sind Fachräume zugewiesen. Für den Tabletwagen ist am Freitag, 27. November, die siebte Stunde reserviert.
- **Dateien**: Über die Exportfunktionen können freigegebene Pläne als PDF oder CSV ausgegeben werden. Der JSON-Import übernimmt auch Abhängigkeiten, Gerätepool, Regeln und Zeitraster in einem Schritt.
