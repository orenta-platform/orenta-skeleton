# Vertrauensanker

Öffentliche Schlüssel des Eigentümers, die beim Bau fest ins Image kompiliert werden (R45, E30).
Nie private Schlüssel hier ablegen.

| Datei | Zweck |
|---|---|
| `orenta_main.pub` | Hauptschlüssel: signiert Pakete (`orenta-package`), das Manifest der Infrastruktur (`orenta-infra`) und die Schlüsselliste (`orenta-keylist`) |
| `orenta_emergency.pub` | Notfallschlüssel, offline: darf den Hauptschlüssel sperren und einen neuen aktivieren (R43) |

Änderungen nur über einen Pull Request mit Review des Eigentümers (CODEOWNERS).
