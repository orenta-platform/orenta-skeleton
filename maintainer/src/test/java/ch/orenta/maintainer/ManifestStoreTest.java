package ch.orenta.maintainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.orenta.signature.SshSignatureVerifier;
import ch.orenta.signature.TrustAnchors;
import ch.orenta.signature.TrustedKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fixtures in src/test/resources/manifest are signed with a throwaway key (private part not kept). */
class ManifestStoreTest {

    @TempDir
    Path dir;

    private Path config;
    private Path state;
    private ManifestStore store;

    @BeforeEach
    void setUp() throws Exception {
        TrustedKey key = TrustedKey.parse("test_main", new String(fixture("main.pub")));
        config = Files.createDirectories(dir.resolve("config"));
        state = dir.resolve("state");
        store = new ManifestStore(new SshSignatureVerifier(TrustAnchors.of(List.of(key))),
                config.resolve("infra-manifest.json"), config.resolve("infra-manifest.json.sig"), state);
    }

    @Test
    void acceptsSignedManifestAndKeepsIt() throws Exception {
        place("v1.json", "v1.json.sig");
        ManifestStore.Result first = store.load();
        assertEquals(1, first.manifest().orElseThrow().version());
        assertEquals("config", first.source());
        assertTrue(Files.exists(state.resolve(ManifestStore.STATE_FILE)));

        Files.delete(config.resolve("infra-manifest.json"));
        ManifestStore.Result second = store.load();
        assertEquals(1, second.manifest().orElseThrow().version());
        assertEquals("state", second.source());
    }

    @Test
    void doesNothingWithoutValidManifest() throws Exception {
        assertTrue(store.load().manifest().isEmpty());
        place("v1.json", "v2.json.sig");
        ManifestStore.Result r = store.load();
        assertTrue(r.manifest().isEmpty());
        assertTrue(r.note().contains("rejected"));
    }

    @Test
    void rejectsSignatureForOtherNamespace() throws Exception {
        place("v1-package.json", "v1-package.json.sig");
        assertTrue(store.load().note().contains("namespace"));
    }

    @Test
    void neverGoesBackToAnOlderVersion() throws Exception {
        place("v2.json", "v2.json.sig");
        assertEquals(2, store.load().manifest().orElseThrow().version());
        place("v1.json", "v1.json.sig");
        ManifestStore.Result r = store.load();
        assertEquals(2, r.manifest().orElseThrow().version());
        assertTrue(r.note().contains("lower"));
    }

    @Test
    void rejectsSameVersionWithOtherContent() throws Exception {
        place("v1.json", "v1.json.sig");
        store.load();
        place("v1-other.json", "v1-other.json.sig");
        ManifestStore.Result r = store.load();
        assertEquals("postgres", r.manifest().orElseThrow().targets().get(0).name());
        assertTrue(r.note().contains("same version"));
    }

    @Test
    void rejectsEmptyDigestEvenWhenSigned() throws Exception {
        place("v1.json", "v1.json.sig");
        store.load();
        place("v3-empty-digest.json", "v3-empty-digest.json.sig");
        ManifestStore.Result r = store.load();
        assertEquals(1, r.manifest().orElseThrow().version());
        assertTrue(r.note().contains("digest"));
    }

    @Test
    void ignoresDamagedStateFile() throws Exception {
        Files.createDirectories(state);
        Files.writeString(state.resolve(ManifestStore.STATE_FILE), "{\"manifest\":\"AAAA\",\"signature\":\"x\"}");
        place("v1.json", "v1.json.sig");
        assertEquals(1, store.load().manifest().orElseThrow().version());
    }

    @Test
    void manifestRules() {
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(
                "{\"manifest\":\"orenta-infra\",\"version\":1,\"targets\":[{\"name\":\"a\",\"mode\":\"observe\"},"
                        + "{\"name\":\"a\",\"mode\":\"observe\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(
                "{\"manifest\":\"orenta-infra\",\"version\":1,\"targets\":[{\"name\":\"a\",\"mode\":\"delete\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(
                "{\"manifest\":\"orenta-infra\",\"version\":1,\"targets\":[{\"name\":\"a\",\"mode\":\"update\","
                        + "\"image\":\"traefik\",\"digest\":\"sha256:" + "a".repeat(64) + "\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(
                "{\"manifest\":\"orenta-infra\",\"version\":1,\"targets\":[{\"name\":\"a\",\"mode\":\"observe\","
                        + "\"config\":\"/x.yaml\",\"config_sha256\":\"\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(
                "{\"manifest\":\"other\",\"version\":1,\"targets\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(
                "{\"manifest\":\"orenta-infra\",\"version\":0,\"targets\":[]}"));
    }

    private void place(String manifest, String sig) throws IOException {
        Files.write(config.resolve("infra-manifest.json"), fixture(manifest));
        Files.write(config.resolve("infra-manifest.json.sig"), fixture(sig));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ManifestStoreTest.class.getResourceAsStream("/manifest/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            return in.readAllBytes();
        }
    }
}
