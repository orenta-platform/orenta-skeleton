package ch.orenta.signature;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * An Ed25519 public key in OpenSSH format ({@code ssh-ed25519 AAAA… comment}).
 *
 * @param name    label, e.g. {@code orenta_main}
 * @param blob    key in SSH wire format, compared byte by byte with the key inside a signature
 * @param key     the same key for {@link java.security.Signature}
 */
public record TrustedKey(String name, byte[] blob, PublicKey key) {

    static final String ED25519 = "ssh-ed25519";

    /** SubjectPublicKeyInfo prefix for a raw 32 byte Ed25519 key (RFC 8410). */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    public TrustedKey {
        blob = blob.clone();
    }

    /** Parses one line of an OpenSSH {@code .pub} file. */
    public static TrustedKey parse(String name, String openSshLine) throws SignatureException {
        String[] parts = openSshLine.trim().split("\\s+");
        if (parts.length < 2 || !ED25519.equals(parts[0])) {
            throw new SignatureException("only ssh-ed25519 keys are accepted: " + name);
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new SignatureException("key is not valid base64: " + name, e);
        }
        return fromBlob(name, blob);
    }

    static TrustedKey fromBlob(String name, byte[] blob) throws SignatureException {
        SshWire.Reader reader = new SshWire.Reader(blob);
        String type = reader.utf8();
        byte[] raw = reader.string();
        if (!ED25519.equals(type) || raw.length != 32 || !reader.done()) {
            throw new SignatureException("not an ssh-ed25519 key: " + name);
        }
        byte[] encoded = new byte[X509_PREFIX.length + raw.length];
        System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, encoded, X509_PREFIX.length, raw.length);
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            return new TrustedKey(name, blob, key);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new SignatureException("cannot load key: " + name, e);
        }
    }

    @Override
    public byte[] blob() {
        return blob.clone();
    }

    boolean matches(byte[] otherBlob) {
        return MessageDigest.isEqual(blob, otherBlob);
    }

    /** Fingerprint as printed by {@code ssh-keygen -l}, e.g. {@code SHA256:PgAO4y8j…}. */
    public String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TrustedKey that && name.equals(that.name) && Arrays.equals(blob, that.blob);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(blob);
    }

    @Override
    public String toString() {
        return name + " " + fingerprint();
    }
}
