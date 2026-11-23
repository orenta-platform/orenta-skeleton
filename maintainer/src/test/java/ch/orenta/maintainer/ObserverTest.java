package ch.orenta.maintainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObserverTest {

    @TempDir
    Path dir;

    private static final String RUNNING = "{\"State\":{\"Status\":\"running\",\"Health\":{\"Status\":\"healthy\"}},"
            + "\"Config\":{\"Image\":\"postgres:16\"}}";
    private static final String EXITED = "{\"State\":{\"Status\":\"exited\"},\"Config\":{\"Image\":\"redis:7\"}}";

    @Test
    void reportsStateHealthAndMissing() throws Exception {
        Path socket = dir.resolve("docker.sock");
        try (FakeDocker fake = new FakeDocker(socket, Map.of(
                "/containers/postgres/json", new FakeDocker.Answer(200, RUNNING, false),
                "/containers/redis/json", new FakeDocker.Answer(200, EXITED, true)))) {
            Observer observer = new Observer(new DockerClient(socket));
            Manifest manifest = Manifest.parse("{\"manifest\":\"orenta-infra\",\"version\":1,\"targets\":["
                    + "{\"name\":\"postgres\",\"mode\":\"observe\"},"
                    + "{\"name\":\"redis\",\"mode\":\"observe\"},"
                    + "{\"name\":\"mosquitto\",\"mode\":\"observe\"}]}");
            List<Observer.Status> report = observer.observe(manifest);

            assertEquals("running", report.get(0).state());
            assertEquals("healthy", report.get(0).health());
            assertEquals("postgres:16", report.get(0).image());
            assertNull(report.get(0).problem());

            assertEquals("exited", report.get(1).state());
            assertTrue(report.get(1).problem().contains("not running"));

            assertEquals("missing", report.get(2).state());
            assertTrue(report.get(2).problem().contains("does not exist"));
            assertTrue(fake.requests.contains("GET /containers/postgres/json HTTP/1.1"));
        }
    }

    @Test
    void checksConfigHash() throws Exception {
        Path config = dir.resolve("sockguard.yaml");
        Files.writeString(config, "listen: x\n");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(config)));
        Manifest.Target ok = new Manifest.Target("s", Manifest.Mode.OBSERVE, null, null, config.toString(), sha, false, null);
        assertNull(Observer.checkConfig(ok));

        Files.writeString(config, "listen: y\n");
        assertTrue(Observer.checkConfig(ok).contains("differs"));
    }

    @Test
    void neverExec() {
        DockerClient docker = new DockerClient(dir.resolve("none.sock"));
        assertThrows(IllegalArgumentException.class, () -> docker.request("POST", "/containers/x/exec", "{}"));
    }

    @Test
    void wantedFalseManagedMissingIsFine() throws Exception {
        Path socket = dir.resolve("d.sock");
        try (FakeDocker fake = new FakeDocker(socket, Map.of())) {
            Observer observer = new Observer(new DockerClient(socket));
            Manifest.Target t = new Manifest.Target("sockguard-tenant-a", Manifest.Mode.MANAGED, "x",
                    "sha256:" + "a".repeat(64), null, null, false, Map.of());
            assertNull(observer.observe(t).problem());
        }
    }
}
