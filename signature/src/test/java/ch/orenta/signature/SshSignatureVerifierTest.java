package ch.orenta.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fixtures in src/test/resources/sshsig were made with OpenSSH 9.6 using throwaway keys (private parts not
 * kept), e.g. {@code ssh-keygen -Y sign -f main -n orenta-package message.txt}.
 */
class SshSignatureVerifierTest {

    private TrustedKey main;
    private SshSignatureVerifier verifier;
    private byte[] message;

    @BeforeEach
    void setUp() throws Exception {
        main = TrustedKey.parse("test_main", text("main.pub"));
        verifier = new SshSignatureVerifier(TrustAnchors.of(List.of(main)));
        message = bytes("message.txt");
    }

    @Test
    void acceptsSha512Signature() throws Exception {
        SshSignatureVerifier.Verified result =
                verifier.verify(message, text("main-package-sha512.sig"), Namespace.PACKAGE);
        assertEquals(main, result.key());
        assertEquals("sha512", result.hashAlgorithm());
    }

    @Test
    void acceptsSha256Signature() throws Exception {
        SshSignatureVerifier.Verified result =
                verifier.verify(message, text("main-package-sha256.sig"), Namespace.PACKAGE);
        assertEquals("sha256", result.hashAlgorithm());
    }

    @Test
    void fingerprintMatchesSshKeygen() {
        assertEquals("SHA256:PgAO4y8jshlesBMsHwyLp5b2DVZuQz02wigiGG6KghQ", main.fingerprint());
    }

    @Test
    void rejectsOtherNamespace() {
        SignatureException e = assertThrows(SignatureException.class,
                () -> verifier.verify(message, text("main-package-sha512.sig"), Namespace.INFRA));
        assertTrue(e.getMessage().contains("namespace"));
    }

    @Test
    void rejectsChangedMessage() {
        byte[] changed = "hello orenta!\n".getBytes(StandardCharsets.UTF_8);
        SignatureException e = assertThrows(SignatureException.class,
                () -> verifier.verify(changed, text("main-package-sha512.sig"), Namespace.PACKAGE));
        assertTrue(e.getMessage().contains("does not match"));
    }

    @Test
    void rejectsUntrustedKey() {
        SignatureException e = assertThrows(SignatureException.class,
                () -> verifier.verify(message, text("other-package-sha512.sig"), Namespace.PACKAGE));
        assertTrue(e.getMessage().contains("not trusted"));
    }

    @Test
    void acceptsSecondTrustedKey() throws Exception {
        TrustedKey other = TrustedKey.parse("test_other", text("other.pub"));
        SshSignatureVerifier both = new SshSignatureVerifier(TrustAnchors.of(List.of(main, other)));
        assertEquals(other, both.verify(message, text("other-package-sha512.sig"), Namespace.PACKAGE).key());
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        String sig = text("main-package-sha512.sig");
        int pos = sig.indexOf('\n', sig.indexOf("-----\n") + 6) - 2;
        char c = sig.charAt(pos);
        String tampered = sig.substring(0, pos) + (c == 'A' ? 'B' : 'A') + sig.substring(pos + 1);
        assertThrows(SignatureException.class, () -> verifier.verify(message, tampered, Namespace.PACKAGE));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(SignatureException.class, () -> verifier.verify(message, null, Namespace.PACKAGE));
        assertThrows(SignatureException.class, () -> verifier.verify(message, "nonsense", Namespace.PACKAGE));
        assertThrows(SignatureException.class, () -> verifier.verify(message,
                "-----BEGIN SSH SIGNATURE-----\nAAAA\n-----END SSH SIGNATURE-----", Namespace.PACKAGE));
    }

    @Test
    void rejectsNonEd25519Key() {
        assertThrows(SignatureException.class,
                () -> TrustedKey.parse("rsa", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ== x"));
    }

    @Test
    void builtInFailsWithoutMainKey() {
        // trust/ holds no keys yet; the image must refuse to start without its anchor
        assertThrows(SignatureException.class, TrustAnchors::builtIn);
    }

    private static String text(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = SshSignatureVerifierTest.class.getResourceAsStream("/sshsig/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            return in.readAllBytes();
        }
    }
}
