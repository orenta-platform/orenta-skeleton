package ch.orenta.maintainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signed manifest of the infrastructure (namespace orenta-infra). It alone decides what the
 * maintainer may touch; anything not listed is left alone.
 */
record Manifest(long version, String issued, Set<String> allowedImages, List<Target> targets) {

    enum Mode { OBSERVE, ADOPT, MANAGED, UPDATE }

    record Target(String name, Mode mode, String image, String digest, String config, String configSha256,
                  boolean wanted, Map<String, Object> template) {

        Optional<String> configPath() {
            return Optional.ofNullable(config);
        }
    }

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    Optional<Target> target(String name) {
        return targets.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** Parses and validates; every rule violation is an {@link IllegalArgumentException}. */
    static Manifest parse(String text) {
        Map<String, Object> root = Json.parseObject(text);
        if (!"orenta-infra".equals(root.get("manifest"))) {
            throw new IllegalArgumentException("field 'manifest' must be 'orenta-infra'");
        }
        if (!(root.get("version") instanceof Long version) || version < 1) {
            throw new IllegalArgumentException("field 'version' must be a whole number of at least 1");
        }
        String issued = root.get("issued") instanceof String s ? s : "";

        Set<String> allowed = new HashSet<>();
        if (root.get("allowed_images") instanceof Map<?, ?> images) {
            for (Map.Entry<?, ?> e : images.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> rule) || !"digest".equals(rule.get("pin"))) {
                    throw new IllegalArgumentException("allowed image '" + e.getKey() + "' must be pinned by digest");
                }
                allowed.add(String.valueOf(e.getKey()));
            }
        } else if (root.containsKey("allowed_images")) {
            throw new IllegalArgumentException("field 'allowed_images' must be an object");
        }

        if (!(root.get("targets") instanceof List<?> rawTargets)) {
            throw new IllegalArgumentException("field 'targets' must be a list");
        }
        List<Target> targets = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object raw : rawTargets) {
            Target t = target(raw, allowed);
            if (!names.add(t.name())) {
                throw new IllegalArgumentException("target '" + t.name() + "' is listed twice");
            }
            targets.add(t);
        }
        return new Manifest(version, issued, Set.copyOf(allowed), List.copyOf(targets));
    }

    @SuppressWarnings("unchecked")
    private static Target target(Object raw, Set<String> allowed) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("every target must be an object");
        }
        String name = str(m, "name");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("target has no valid name: " + name);
        }
        String modeText = str(m, "mode");
        Mode mode;
        try {
            mode = Mode.valueOf(String.valueOf(modeText).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": unknown mode '" + modeText + "'");
        }
        String image = str(m, "image");
        String digest = str(m, "digest");
        String config = str(m, "config");
        String configSha256 = str(m, "config_sha256");
        boolean wanted = Boolean.TRUE.equals(m.get("wanted"));
        Map<String, Object> template = m.get("template") instanceof Map<?, ?> tm ? (Map<String, Object>) tm : null;

        if (mode == Mode.MANAGED || mode == Mode.UPDATE) {
            if (image == null || !allowed.contains(image)) {
                throw new IllegalArgumentException(name + ": image '" + image + "' is not in allowed_images");
            }
            if (digest == null || !DIGEST.matcher(digest).matches()) {
                throw new IllegalArgumentException(name + ": digest must be sha256:<64 hex>, got '" + digest + "'");
            }
        }
        if (mode == Mode.MANAGED && template == null) {
            throw new IllegalArgumentException(name + ": mode managed needs a template");
        }
        if (config != null && (configSha256 == null || !SHA256.matcher(configSha256).matches())) {
            throw new IllegalArgumentException(name + ": config_sha256 must be 64 hex characters");
        }
        return new Target(name, mode, image, digest, config, configSha256, wanted, template);
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("field '" + key + "' must be text");
        }
        return s.isEmpty() ? null : s;
    }
}
