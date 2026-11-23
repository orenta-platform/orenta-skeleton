package ch.orenta.signature;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;

/**
 * Verifies signatures made with {@code ssh-keygen -Y sign} (format SSHSIG, see OpenSSH PROTOCOL.sshsig).
 * Only Ed25519 keys, only hash algorithms sha512 and sha256, only keys from the given trust anchors, and
 * only for the expected namespace.
 */
public final class SshSignatureVerifier {

    private static final byte[] MAGIC = "SSHSIG".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";
    private static final String END = "-----END SSH SIGNATURE-----";

    private final TrustAnchors anchors;

    public SshSignatureVerifier(TrustAnchors anchors) {
        this.anchors = anchors;
    }

    /** Result of a successful verification. */
    public record Verified(TrustedKey key, Namespace namespace, String hashAlgorithm) {
    }

    /**
     * Verifies {@code armoredSignature} over {@code message}.
     *
     * @throws SignatureException if anything does not match; the message says what
     */
    public Verified verify(byte[] message, String armoredSignature, Namespace namespace) throws SignatureException {
        SshWire.Reader reader = new SshWire.Reader(dearmor(armoredSignature));
        if (!MessageDigest.isEqual(MAGIC, reader.raw(MAGIC.length))) {
            throw new SignatureException("not an SSHSIG signature");
        }
        int version = reader.uint32();
        if (version != VERSION) {
            throw new SignatureException("unsupported SSHSIG version " + version);
        }
        byte[] publicKey = reader.string();
        String signedNamespace = reader.utf8();
        byte[] reserved = reader.string();
        String hashAlgorithm = reader.utf8();
        byte[] signatureBlob = reader.string();
        if (!reader.done()) {
            throw new SignatureException("trailing data after signature");
        }

        if (!namespace.value().equals(signedNamespace)) {
            throw new SignatureException("signature is for namespace '" + signedNamespace
                    + "', expected '" + namespace.value() + "'");
        }
        TrustedKey key = anchors.find(publicKey)
                .orElseThrow(() -> new SignatureException("signed by a key that is not trusted"));

        byte[] hash = hash(hashAlgorithm, message);
        byte[] signedData = new SshWire.Writer()
                .raw(MAGIC)
                .string(signedNamespace)
                .string(reserved)
                .string(hashAlgorithm)
                .string(hash)
                .toByteArray();

        SshWire.Reader sig = new SshWire.Reader(signatureBlob);
        String sigType = sig.utf8();
        byte[] sigBytes = sig.string();
        if (!TrustedKey.ED25519.equals(sigType) || sigBytes.length != 64 || !sig.done()) {
            throw new SignatureException("only ssh-ed25519 signatures are accepted");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.key());
            verifier.update(signedData);
            if (!verifier.verify(sigBytes)) {
                throw new SignatureException("signature does not match the data");
            }
        } catch (GeneralSecurityException e) {
            throw new SignatureException("cannot verify signature", e);
        }
        return new Verified(key, namespace, hashAlgorithm);
    }

    private static byte[] hash(String algorithm, byte[] message) throws SignatureException {
        String jca = switch (algorithm) {
            case "sha512" -> "SHA-512";
            case "sha256" -> "SHA-256";
            default -> throw new SignatureException("unsupported hash algorithm " + algorithm);
        };
        try {
            return MessageDigest.getInstance(jca).digest(message);
        } catch (GeneralSecurityException e) {
            throw new SignatureException("cannot hash", e);
        }
    }

    private static byte[] dearmor(String armored) throws SignatureException {
        if (armored == null) {
            throw new SignatureException("no signature");
        }
        String text = armored.trim();
        if (!text.startsWith(BEGIN) || !text.endsWith(END)) {
            throw new SignatureException("signature is not armored as SSH SIGNATURE");
        }
        String body = text.substring(BEGIN.length(), text.length() - END.length()).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new SignatureException("signature is not valid base64", e);
        }
    }
}
