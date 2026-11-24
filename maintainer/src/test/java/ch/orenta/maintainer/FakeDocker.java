package ch.orenta.maintainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A Unix socket that answers like the Docker API with fixed responses per path. */
final class FakeDocker implements AutoCloseable {

    record Answer(int status, String body, boolean chunked) {
    }

    final List<String> requests = new CopyOnWriteArrayList<>();
    private final ServerSocketChannel server;
    private final Thread thread;

    FakeDocker(Path socket, Map<String, Answer> answers) throws IOException {
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        thread = new Thread(() -> {
            while (server.isOpen()) {
                try (SocketChannel client = server.accept()) {
                    String request = readHead(client);
                    String requestLine = request.substring(0, request.indexOf("\r\n"));
                    requests.add(requestLine);
                    String path = requestLine.split(" ")[1];
                    Answer a = answers.getOrDefault(path, new Answer(404, "{\"message\":\"no such container\"}", false));
                    client.write(ByteBuffer.wrap(response(a).getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static String readHead(SocketChannel client) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (!out.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            one.clear();
            if (client.read(one) < 0) {
                break;
            }
            out.write(one.array(), 0, 1);
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static String response(Answer a) {
        byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + a.status() + " X\r\nContent-Type: application/json\r\n";
        if (a.chunked()) {
            int half = body.length / 2;
            String first = new String(body, 0, half, StandardCharsets.UTF_8);
            String second = new String(body, half, body.length - half, StandardCharsets.UTF_8);
            return head + "Transfer-Encoding: chunked\r\n\r\n"
                    + Integer.toHexString(first.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + first + "\r\n"
                    + Integer.toHexString(second.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + second + "\r\n"
                    + "0\r\n\r\n";
        }
        return head + "Content-Length: " + body.length + "\r\n\r\n" + a.body();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
