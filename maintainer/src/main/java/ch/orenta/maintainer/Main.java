package ch.orenta.maintainer;

import ch.orenta.signature.SignatureException;
import ch.orenta.signature.SshSignatureVerifier;
import ch.orenta.signature.TrustAnchors;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * infra-maintainer: the only Orenta component on the raw Docker socket. Fixed code; acts only on the
 * signed manifest; never loads code, never talks to the database (E25). This version observes only.
 *
 * <p>Configuration through environment variables:
 * <ul>
 *   <li>{@code ORENTA_MAINT_MANIFEST}, {@code ORENTA_MAINT_MANIFEST_SIG}: manifest and signature</li>
 *   <li>{@code ORENTA_MAINT_STATE}: folder for the last valid manifest</li>
 *   <li>{@code ORENTA_MAINT_DOCKER}: Docker socket, default {@code /var/run/docker.sock}</li>
 *   <li>{@code ORENTA_MAINT_INTERVAL}: seconds between rounds, default 30</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        TrustAnchors anchors;
        try {
            anchors = TrustAnchors.builtIn();
        } catch (SignatureException e) {
            log("fatal", Map.of("message", e.getMessage()));
            System.exit(2);
            return;
        }
        log("start", Map.of("trusted", anchors.keys().stream().map(Object::toString).toList()));

        ManifestStore store = new ManifestStore(new SshSignatureVerifier(anchors),
                Path.of(env("ORENTA_MAINT_MANIFEST", "/etc/orenta-maint/infra-manifest.json")),
                Path.of(env("ORENTA_MAINT_MANIFEST_SIG", "/etc/orenta-maint/infra-manifest.json.sig")),
                Path.of(env("ORENTA_MAINT_STATE", "/var/lib/orenta-maint")));
        Observer observer = new Observer(new DockerClient(Path.of(env("ORENTA_MAINT_DOCKER", "/var/run/docker.sock"))));
        Duration interval = Duration.ofSeconds(Long.parseLong(env("ORENTA_MAINT_INTERVAL", "30")));

        String lastNote = null;
        while (true) {
            ManifestStore.Result result = store.load();
            if (!result.note().equals(lastNote)) {
                log("manifest", Map.of("source", result.source(), "note", result.note()));
                lastNote = result.note();
            }
            result.manifest().ifPresent(manifest -> {
                List<Observer.Status> report = observer.observe(manifest);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("version", manifest.version());
                fields.put("problems", report.stream().filter(s -> s.problem() != null).count());
                fields.put("targets", report.stream().map(Observer.Status::toMap).toList());
                log("status", fields);
            });
            Thread.sleep(interval.toMillis());
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** One JSON line per event on stdout, readable for docker logs and later for Orenta. */
    static void log(String event, Map<String, ?> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", Instant.now().toString());
        line.put("event", event);
        line.putAll(fields);
        System.out.println(Json.write(line));
    }
}
