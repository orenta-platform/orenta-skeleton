package ch.orenta.maintainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal client for the Docker Engine API over a Unix socket (HTTP/1.1, one connection per request).
 * Only what the maintainer needs; no exec, ever.
 */
final class DockerClient {

    private static final int MAX_RESPONSE = 16 * 1024 * 1024;

    private final Path socket;

    DockerClient(Path socket) {
        this.socket = socket;
    }

    record Response(int status, String body) {
    }

    /** {@code GET /containers/<name>/json}; empty if there is no such container. */
    Optional<Map<String, Object>> inspect(String name) throws IOException {
        Response r = request("GET", "/containers/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/json", null);
        if (r.status() == 404) {
            return Optional.empty();
        }
        if (r.status() != 200) {
            throw new IOException("inspect " + name + ": HTTP " + r.status());
        }
        return Optional.of(Json.parseObject(r.body()));
    }

    Response request(String method, String path, String jsonBody) throws IOException {
        if (path.contains("/exec")) {
            throw new IllegalArgumentException("exec is never used by the maintainer");
        }
        byte[] body = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: docker\r\n")
                .append("Connection: close\r\n")
                .append("Accept: application/json\r\n");
        if (jsonBody != null) {
            head.append("Content-Type: application/json\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n\r\n");

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            writeAll(channel, head.toString().getBytes(StandardCharsets.US_ASCII));
            writeAll(channel, body);
            return parse(readAll(channel));
        }
    }

    private static void writeAll(SocketChannel channel, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static byte[] readAll(SocketChannel channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            out.write(buffer.array(), 0, buffer.limit());
            buffer.clear();
            if (out.size() > MAX_RESPONSE) {
                throw new IOException("response too large");
            }
        }
        return out.toByteArray();
    }

    static Response parse(byte[] raw) throws IOException {
        int headerEnd = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        if (headerEnd < 0) {
            throw new IOException("no HTTP header in response");
        }
        String[] lines = new String(raw, 0, headerEnd, StandardCharsets.US_ASCII).split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/1.")) {
            throw new IOException("bad status line: " + lines[0]);
        }
        int status;
        try {
            status = Integer.parseInt(statusLine[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status line: " + lines[0]);
        }
        boolean chunked = false;
        int contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = lines[i].substring(0, colon).trim().toLowerCase();
            String value = lines[i].substring(colon + 1).trim();
            if (key.equals("transfer-encoding") && value.toLowerCase().contains("chunked")) {
                chunked = true;
            } else if (key.equals("content-length")) {
                contentLength = Integer.parseInt(value);
            }
        }
        int start = headerEnd + 4;
        byte[] body;
        if (chunked) {
            body = dechunk(raw, start);
        } else if (contentLength >= 0) {
            if (start + contentLength > raw.length) {
                throw new IOException("response shorter than Content-Length");
            }
            body = java.util.Arrays.copyOfRange(raw, start, start + contentLength);
        } else {
            body = java.util.Arrays.copyOfRange(raw, start, raw.length);
        }
        return new Response(status, new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] raw, int pos) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        while (true) {
            int lineEnd = indexOf(raw, crlf, pos);
            if (lineEnd < 0) {
                throw new IOException("bad chunked encoding");
            }
            String sizeText = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII).split(";")[0].trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + sizeText);
            }
            pos = lineEnd + 2;
            if (size == 0) {
                return out.toByteArray();
            }
            if (pos + size > raw.length) {
                throw new IOException("chunk longer than response");
            }
            out.write(raw, pos, size);
            pos += size + 2;
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
