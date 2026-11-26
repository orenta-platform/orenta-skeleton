# CLAUDE.md – orenta-skeleton

Wahrheit ist das Dokument «Orenta: Zielarchitektur» (https://claude.ai/artifact/SABVTmnkuH19WAo2VxPr9J),
v. a. Regeln R1–R53, Entscheide E1–E52, «Einrichten und Start», Umsetzungsplan.

## Regeln für die Arbeit hier

- Code, Bezeichner, Commits auf Englisch (E19); Texte für den Eigentümer und Dokumentation auf
  Deutsch mit Schweizer Schreibweise (ss, nie ß; R32).
- Keine Geheimnisse, keine privaten Schlüssel, keine Laufzeitdaten im Repo (R1, R3, R16).
- Nie auf `main` pushen: eigener Branch, Pull Request, Merge nur durch den Eigentümer.
- `trust/` ändert nur der Eigentümer (CODEOWNERS, R45).
- Das Skelett enthält keine Fachlogik; Fachliches kommt als signiertes Paket aus der Datenbank (E16, E20).
- Der Maintainer hat festen Code, lädt nie Code aus der Datenbank, spricht nie mit der Datenbank (E25).
- Signiert wird nur vom Eigentümer mit `ssh-keygen -Y sign` vom Stick (E22, R42); nie in CI.
- Images: Native Image statisch mit musl, `FROM scratch`, cosign keyless im Bau-Workflow (R13, E30).
- docker und ssh auf Mac und Flatcar macht der Betreiber, nicht Claude (E26).
