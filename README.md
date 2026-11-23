# Orenta skeleton

Minimal native runtime for Orenta (GraalVM Native Image). It loads signed code, types and policies
from the database; the skeleton itself contains no business logic. Also contains the infra maintainer,
the only Orenta component allowed to talk to the raw Docker socket, acting solely on a signed manifest.

Licensed under Apache 2.0. "Orenta" is a name of its owner: "based on Orenta" is fine, a modified
skeleton needs its own name.

## Layout

| Path | Content |
|---|---|
| `trust/` | public keys compiled into every image (owner review required) |
| `signature/` | verification of SSHSIG signatures (`ssh-keygen -Y sign`) against those keys |
| `maintainer/` | infra maintainer (fixed code, signed manifest) |
| `.github/workflows/` | build workflow: native images, ghcr.io, cosign keyless |
