package shared;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain JSON, read and written without a library: what the POS terminals send each other when they
 * sync (see sync.SyncEngine) and what the sync queue stores.
 *
 * Read: objects become {@code Map<String, Object>} (in order), arrays {@code List<Object>}, strings
 * {@code String}, numbers {@code BigDecimal} (never rounded), true/false {@code Boolean}, null null.
 * Write: the same types (plus any other Number or CharSequence) back to text.
 */
public final class Json {

    private Json() { }

    /* ------------------------------- writing ------------------------------- */

    /** {@code value} as JSON text. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Raw raw) {
            out.append(raw.json());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof BigDecimal d) {
            out.append(d.toPlainString());
        } else if (value instanceof Number n) {
            out.append(n);
        } else {
            string(value.toString(), out);
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }

    /** JSON text that is written as it is (already valid JSON, e.g. a stored payload). */
    public record Raw(String json) { }

    /* ------------------------------- reading ------------------------------- */

    /**
     * Reads one JSON value.
     *
     * @throws IllegalArgumentException the text is not valid JSON
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("No JSON");
        }
        Reader r = new Reader(text);
        r.space();
        Object value = r.value();
        r.space();
        if (r.pos != text.length()) {
            throw r.error("Unexpected text after the JSON");
        }
        return value;
    }

    /** {@link #parse} for text that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("Unexpected end of JSON");
            }
            char ch = s.charAt(pos);
            return switch (ch) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;   // {
            space();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                space();
                if (peek() != '"') {
                    throw error("Expected a name in quotes");
                }
                String name = string();
                space();
                expect(':');
                space();
                out.put(name, value());
                space();
                char next = next();
                if (next == '}') {
                    return out;
                }
                if (next != ',') {
                    throw error("Expected , or }");
                }
            }
        }

        List<Object> array() {
            List<Object> out = new ArrayList<>();
            pos++;   // [
            space();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                space();
                out.add(value());
                space();
                char next = next();
                if (next == ']') {
                    return out;
                }
                if (next != ',') {
                    throw error("Expected , or ]");
                }
            }
        }

        String string() {
            pos++;   // "
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unclosed string");
                }
                char ch = s.charAt(pos++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("Bad \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("Bad escape \\" + esc);
                }
            }
        }

        BigDecimal number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("Unexpected character '" + s.charAt(pos) + "'");
            }
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("Bad number");
            }
        }

        Object word(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("Unexpected text");
            }
            pos += word.length();
            return value;
        }

        void space() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("Unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        char next() {
            char ch = peek();
            pos++;
            return ch;
        }

        void expect(char ch) {
            if (next() != ch) {
                throw error("Expected " + ch);
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + pos);
        }
    }

    /* ------------------------------- helpers -------------------------------- */

    /** A field of a parsed object as text (numbers and booleans too); null if missing or null. */
    public static String text(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal d ? d.toPlainString() : value.toString();
    }

    /** A field of a parsed object as a whole number; {@code fallback} if missing or not a number. */
    public static long number(Map<String, Object> object, String name, long fallback) {
        Object value = object.get(name);
        try {
            if (value instanceof BigDecimal d) {
                return d.longValueExact();
            }
            if (value instanceof String t && !t.isBlank()) {
                return Long.parseLong(t.trim());
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return fallback;
        }
        return fallback;
    }

    /** A field of a parsed object that must be a list; empty if missing. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> object, String name) {
        Object value = object.get(name);
        return value instanceof List ? (List<Object>) value : List.of();
    }

    /** A field of a parsed object that must be an object; empty if missing. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> object, String name) {
        Object value = object.get(name);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
