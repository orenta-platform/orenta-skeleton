package ch.orenta.maintainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mode observe for every target: reads the state of the container and checks the hash of its
 * configuration file against the manifest. Changes nothing.
 */
final class Observer {

    private final DockerClient docker;

    Observer(DockerClient docker) {
        this.docker = docker;
    }

    /** One line of the status report; {@code problem} is null when all is well. */
    record Status(String name, String mode, String state, String health, String image, String problem) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("mode", mode);
            m.put("state", state);
            m.put("health", health);
            m.put("image", image);
            m.put("problem", problem);
            return m;
        }
    }

    List<Status> observe(Manifest manifest) {
        List<Status> report = new ArrayList<>();
        for (Manifest.Target target : manifest.targets()) {
            report.add(observe(target));
        }
        return report;
    }

    @SuppressWarnings("unchecked")
    Status observe(Manifest.Target target) {
        String mode = target.mode().name().toLowerCase();
        String configProblem = checkConfig(target);
        Optional<Map<String, Object>> inspected;
        try {
            inspected = docker.inspect(target.name());
        } catch (IOException | RuntimeException e) {
            return new Status(target.name(), mode, "unknown", null, null, "docker: " + e.getMessage());
        }
        if (inspected.isEmpty()) {
            boolean expected = target.mode() != Manifest.Mode.MANAGED || target.wanted();
            return new Status(target.name(), mode, "missing", null, null,
                    expected ? join("container does not exist", configProblem) : configProblem);
        }
        Map<String, Object> c = inspected.get();
        Map<String, Object> state = c.get("State") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
        Map<String, Object> config = c.get("Config") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
        String status = String.valueOf(state.getOrDefault("Status", "unknown"));
        String health = state.get("Health") instanceof Map<?, ?> h ? String.valueOf(h.get("Status")) : null;
        String image = config.get("Image") instanceof String i ? i : null;

        String problem = configProblem;
        if (!"running".equals(status) && (target.mode() != Manifest.Mode.MANAGED || target.wanted())) {
            problem = join("not running (" + status + ")", problem);
        }
        if ("unhealthy".equals(health)) {
            problem = join("unhealthy", problem);
        }
        return new Status(target.name(), mode, status, health, image, problem);
    }

    /** Compares the SHA-256 of the configuration file with the manifest (the file path is the same as on the host). */
    static String checkConfig(Manifest.Target target) {
        if (target.config() == null) {
            return null;
        }
        Path path = Path.of(target.config());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            String actual = HexFormat.of().formatHex(digest);
            return actual.equals(target.configSha256()) ? null
                    : "config " + path + " differs from manifest (sha256 " + actual + ")";
        } catch (IOException e) {
            return "config " + path + " not readable: " + e.getMessage();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + "; " + b;
    }
}
