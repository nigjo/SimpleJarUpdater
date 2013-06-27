SimpleJarUpdater
================

A Simple Updater to self-update a jar-application

See http://blog.nigjo.de/netbeans/2013/06/selbstaktualisierende-anwendungen/ for details.


Eine Utilityklasse um eine beliebige Jar-Datei zu aktualisieren. Die Updateklasse
kann in der zu ersetzenden Jar-Datei selbst enthalten sein. Beim Update über die
Methode SimpleUpdater.update(URL, Path, String[]) kopiert sich die Klasse
zunächst selbst in eine temporäre Jar-Datei und startet einen neuen Java-Prozess.
Dieser Prozess ist für den Download der "entfernten" Datei zuständig und kopiert
anschließend die Datei zum angegebenen lokalen Speicherort. Als drittes wird
dann ein erneuter Prozess gestartet mit der aktualisierten Jar-Datei.

Damit die Aktualisierung funktioniert darf kein aktiver Prozess auf die
Update-aufrufende Jar-Datei zugreifen. Sprich nach dem Aufruf der
update() Methode muss ein System.exit(int) oder ähnliches ausgeführt werden.
Beim Update wird ein paar Sekunden lang versucht die bisherige Jar-Datei
(falls vorhanden) umzubenennen, bevor der Download beginnt.
