# Bedienung und Schulungsübung

## Planung und Verwaltung

Wähle zuerst Schule und Szenario. Die Statusmarke zeigt den Workflowzustand. Filter für Woche, Klasse, Raum, Lehrkraft und Fach beschränken die Rasteransicht. Offene Unterrichtsblöcke sind unterhalb des Rasters aufgelistet. Ein Klick öffnet denselben Platzierungsdialog wie Drag-and-drop. Alternative Plätze lassen sich direkt übernehmen. Eine Fixierung schützt den Block vor dem Solver. Zum Verschieben einer fixierten Stunde zuerst die Fixierung am ursprünglichen Platz lösen.

Die Verwaltung pflegt im Bereich **Stammdaten** zuerst Fächer, Räume, Klassen, Lehrkräfte und Schüler, dann Unterricht, Ferien, Reservierungen und Prüfperioden. Eine Veranstaltung ohne feste Lehrkraft erlaubt die automatische Auswahl nach Fachqualifikation. Das leere Feld „Lehrkräfte“ ist dafür vorgesehen. Die Fächerqualifikation aller Mitglieder eines Teams muss zum Fach passen. Mehrfachauswahl funktioniert auf dem Desktop mit Strg bzw. Cmd. Ein Gerätepool wird unter Planungsregeln angelegt.

Bei Sperr- und Wunschfenstern bedeutet beispielsweise `1/2/AB, 5/1/B` Montag zweite Stunde in beiden Wochen und Freitag erste Stunde in Woche B. Anfangs- und Endzeiten jedes Unterrichtsblocks ergeben sich aus dem bearbeitbaren Zeitraster. In der API beginnen Slotindizes bei 0, in der Oberfläche bei 1. Gruppenkürzel und Schüler-IDs bleiben innerhalb einer Schule stabil.

Änderungen an Stammdaten werden zusammen mit den vorhandenen Platzierungen geprüft. Entferne betroffene Platzierungen bei einem Konflikt vor der Änderung. Lege für alternative Entwürfe ein neues Szenario auf Basis eines vorhandenen Plans an. Beim Kopieren mit neuem Semesterzeitraum werden datumsgebundene Ferien, Reservierungen und Prüfungen neu erfasst.

## Klasse für zwei Fächer teilen

Wähle unter **Stammdaten → Klassen & Gruppen** bei einer ungeteilten Klasse **Klasse teilen**. Wähle beispielsweise Griechisch und Französisch, gib die Größe der ersten Gruppe sowie die gemeinsamen Wochenstunden an. Die zweite Gruppe erhält die restlichen Plätze. Bereits erfasste Schüler kannst du sofort ihrem Fach zuordnen oder die Zugehörigkeit später unter **Schüler** ergänzen. Für den persönlichen Plan bleiben die Schüler der Gesamtklasse und ihrer gewählten Teilgruppe zugeordnet.

Beim Speichern entstehen zwei Teilgruppen und zwei Kurse mit demselben **Parallelband**. Im Bereich **Unterricht** kannst du je Kurs Lehrkräfte, Ausstattung oder Geräte vorgeben. Für bestehende Teilgruppen trägst du das gemeinsame Bandkürzel direkt bei den Kursen ein. Alle Kurse eines Bandes brauchen dieselbe Stundenzahl, Blocklänge und denselben Wochenrhythmus. Die Gesamtklasse muss die Teilgruppenkürzel beider Gruppen im Feld **Teilgruppen** enthalten.

Der Solver plant die zusammengehörigen Blöcke gleichzeitig in verschiedenen Räumen. Im Klassenraster erscheinen beide Fächer im selben Zeitfeld. Verschiebst du einen bereits platzierten Block, verschiebt der Planer seine platzierten Partner mit. Eine Fixierung oder Kollision verhindert die gesamte Verschiebung.

## Grafischen Wochenplan exportieren

Wähle im **Planungsraster** Woche A oder B und bei Bedarf Klasse, Lehrkraft, Raum oder Fach. **Wochenplan als PDF** exportiert diese Auswahl im Querformat mit Montag bis Freitag, Stundenbeginn und farbigen Fachfeldern. Auch ein Entwurf lässt sich so exportieren.

Unter **Veröffentlichungen** nutzt **Wochenplan als PDF** den eingestellten Datumsbereich und die gewählte Ansicht. Jede Kalenderwoche beginnt auf einer neuen Seite. Prüfungen, veröffentlichte Vertretungen und Ausfälle erscheinen im Raster. **Terminliste als PDF**, CSV, HTML und Kalenderexport bleiben über die benachbarten Links erreichbar.

## Schulleitung

Öffne eine Variante im Status **In Prüfung**. Prüfe Vollständigkeit, Strafpunkte und Unterschiede zu einer anderen Variante. Verwende **Zur Überarbeitung**, wenn Anpassungen erforderlich sind. Nach der Freigabe kann die Schulleitung oder Planung veröffentlichen. Erscheint kein Plan für ein Datum, kontrolliere zuerst Semesterzeitraum und Ferien.

## Lehrkraft, Klasse und Familie

Die Lehrkraft öffnet ihren freigegebenen Plan unter **Veröffentlichungen**. Unter **Vertretungen** kann sie eine eigene Abwesenheit und einen Planungswunsch melden und ihr Vertretungskonto abrufen. Klassenvorstände sehen ihre Klasse. Schüler- und Elternkonten werden über die Administration mit einer Schüler-ID verbunden. Für Prüfungen ist die Zuordnung als Kandidat erforderlich.

## Tagesplanung

Melde für eine Lehrkraft den Zeitraum einer Abwesenheit. Berechne den Vertretungsvorschlag für einen einzelnen Tag. Die Schule konfiguriert Reihenfolge und Kontoausgleich unter Planungsregeln. Prüfe die Alternativen pro Stunde und veröffentliche die gesamte Liste. Der Server validiert die Auswahl erneut. Treffen neue Abwesenheiten ein, berechne die Liste erneut. Änderungen an bereits veröffentlichten Tagesvertretungen erfordern eine erneute Veröffentlichung für diesen Tag.

## Übung für Multiplikatoren

Arbeite in einer Kopie des Beispielszenarios. Versuche zuerst, zwei Mathematikstunden von WEB gleichzeitig zu platzieren, und prüfe die Konfliktmeldung. Fixiere anschließend eine zulässige Stunde und starte den Solver. Vergleiche beide Szenarien und stelle den vorherigen Entwurf mit Undo wieder her. Führe den Freigabeprozess mit der Schulleitung durch und kontrolliere den Unterschied zwischen der Schüleransicht und der Planeransicht. Verwende für einen Testausfall einen Montag im Semester und prüfe den geänderten Kalendereintrag.
