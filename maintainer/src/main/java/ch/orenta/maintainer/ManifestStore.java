package ch.orenta.maintainer;

import ch.orenta.signature.Namespace;
import ch.orenta.signature.SignatureException;
import ch.orenta.signature.SshSignatureVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the manifest to act on. A new manifest is taken only with a valid signature (namespace
 * orenta-infra) and a version not lower than the last valid one; the same version must have the same
 * content. The last valid manifest is kept in the state folder, so the maintainer also starts without
 * the configuration folder and never falls back to an older one.
 */
final class ManifestStore {

    /** Last valid manifest with its signature, in one file so that one atomic move replaces both. */
    static final String STATE_FILE = "manifest-state.json";

    private final SshSignatureVerifier verifier;
    private final Path candidate;
    private final Path candidateSig;
    private final Path stateDir;

    ManifestStore(SshSignatureVerifier verifier, Path candidate, Path candidateSig, Path stateDir) {
        this.verifier = verifier;
        this.candidate = candidate;
        this.candidateSig = candidateSig;
        this.stateDir = stateDir;
    }

    /** What was loaded and why; {@code manifest} is empty if there is nothing valid at all. */
    record Result(Optional<Manifest> manifest, String source, String note) {
    }

    Result load() {
        Optional<Loaded> stored = readState();
        Loaded candidateLoaded;
        try {
            candidateLoaded = readStrict(candidate, candidateSig);
        } catch (Exception e) {
            return keep(stored, "new manifest rejected: " + e.getMessage());
        }
        if (stored.isPresent()) {
            Loaded old = stored.get();
            long oldVersion = old.manifest().version();
            long newVersion = candidateLoaded.manifest().version();
            if (newVersion < oldVersion) {
                return keep(stored, "new manifest rejected: version " + newVersion + " is lower than " + oldVersion);
            }
            if (newVersion == oldVersion) {
                if (MessageDigest.isEqual(old.bytes(), candidateLoaded.bytes())) {
                    return new Result(Optional.of(old.manifest()), "state", "unchanged, version " + oldVersion);
                }
                return keep(stored, "new manifest rejected: same version " + newVersion + " with other content");
            }
        }
        try {
            persist(candidateLoaded);
        } catch (IOException e) {
            return keep(stored, "new manifest valid but cannot be stored: " + e.getMessage());
        }
        return new Result(Optional.of(candidateLoaded.manifest()), "config",
                "accepted version " + candidateLoaded.manifest().version() + " signed by " + candidateLoaded.signer());
    }

    private Result keep(Optional<Loaded> stored, String note) {
        return stored.map(s -> new Result(Optional.of(s.manifest()), "state", note + "; keeping version " + s.manifest().version()))
                .orElseGet(() -> new Result(Optional.empty(), "none", note + "; no valid manifest, doing nothing"));
    }

    private record Loaded(Manifest manifest, byte[] bytes, String signature, String signer) {
    }

    /** The stored manifest is verified again on every read; a damaged state file counts as missing. */
    private Optional<Loaded> readState() {
        Path file = stateDir.resolve(STATE_FILE);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Map<String, Object> state = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            byte[] bytes = Base64.getDecoder().decode((String) state.get("manifest"));
            return Optional.of(verifyAndParse(bytes, (String) state.get("signature")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Loaded readStrict(Path manifest, Path sig) throws IOException, SignatureException {
        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(sig)) {
            throw new IOException("missing " + manifest.getFileName() + " or its signature");
        }
        return verifyAndParse(Files.readAllBytes(manifest), Files.readString(sig, StandardCharsets.US_ASCII));
    }

    private Loaded verifyAndParse(byte[] bytes, String signature) throws SignatureException {
        SshSignatureVerifier.Verified verified = verifier.verify(bytes, signature, Namespace.INFRA);
        Manifest parsed = Manifest.parse(new String(bytes, StandardCharsets.UTF_8));
        return new Loaded(parsed, bytes, signature, verified.key().name());
    }

    private void persist(Loaded loaded) throws IOException {
        Files.createDirectories(stateDir);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", loaded.manifest().version());
        state.put("manifest", Base64.getEncoder().encodeToString(loaded.bytes()));
        state.put("signature", loaded.signature());
        Path tmp = stateDir.resolve(STATE_FILE + ".tmp");
        Files.writeString(tmp, Json.write(state), StandardCharsets.UTF_8);
        Files.move(tmp, stateDir.resolve(STATE_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
