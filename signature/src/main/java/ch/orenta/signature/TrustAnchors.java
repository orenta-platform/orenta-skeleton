package ch.orenta.signature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Public keys of the owner, compiled into the image from {@code trust/} (R45). Never read from a file of
 * the host. Additional keys come only through the signed key list.
 */
public final class TrustAnchors {

    /** Main key: signs packages, the infra manifest and the key list. */
    public static final String MAIN = "orenta_main";
    /** Emergency key, kept offline: may revoke the main key and activate a new one (R43). */
    public static final String EMERGENCY = "orenta_emergency";

    private static final String RESOURCE_DIR = "/orenta-trust/";

    private final List<TrustedKey> keys;

    private TrustAnchors(List<TrustedKey> keys) {
        this.keys = List.copyOf(keys);
    }

    public static TrustAnchors of(List<TrustedKey> keys) {
        return new TrustAnchors(keys);
    }

    /** Loads the anchors built into this artifact; fails if the main key is missing. */
    public static TrustAnchors builtIn() throws SignatureException {
        List<TrustedKey> keys = new ArrayList<>();
        for (String name : List.of(MAIN, EMERGENCY)) {
            String line = readResource(RESOURCE_DIR + name + ".pub");
            if (line != null) {
                keys.add(TrustedKey.parse(name, line));
            }
        }
        if (keys.stream().noneMatch(k -> k.name().equals(MAIN))) {
            throw new SignatureException("no main key built into this image (trust/orenta_main.pub)");
        }
        return new TrustAnchors(keys);
    }

    public List<TrustedKey> keys() {
        return keys;
    }

    public Optional<TrustedKey> find(byte[] blob) {
        return keys.stream().filter(k -> k.matches(blob)).findFirst();
    }

    private static String readResource(String path) throws SignatureException {
        try (InputStream in = TrustAnchors.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            throw new SignatureException("cannot read " + path, e);
        }
    }
}
