package ch.orenta.maintainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer without dependencies. Objects become {@link LinkedHashMap}, arrays
 * {@link ArrayList}, numbers {@link Long} or {@link BigDecimal}. Duplicate keys are rejected, because
 * a signed manifest must mean exactly one thing.
 */
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.value(0);
        json.skipWhitespace();
        if (json.pos != text.length()) {
            throw json.error("unexpected data after value");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON: expected an object");
        }
        return (Map<String, Object>) value;
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw error("nesting too deep");
        }
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw error("expected a key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (map.containsKey(key)) {
                throw error("duplicate key '" + key + "'");
            }
            map.put(key, value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("short unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    pos += 4;
                }
                default -> throw error("bad escape '\\" + e + "'");
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String raw = text.substring(start, pos);
        try {
            if (raw.matches("-?(0|[1-9][0-9]*)")) {
                return Long.parseLong(raw);
            }
            if (!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw error("bad number '" + raw + "'");
            }
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw error("bad number '" + raw + "'");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected literal");
        }
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON at " + pos + ": " + message);
    }

    /** Writes compact JSON; keys in insertion order. */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
