package sync.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tiny bit of JSON the terminals send each other: flat objects of text values only, e.g.
 * {"terminalId":"T02"}. Enough for this API without adding a JSON library.
 */
final class Json {

    private static final Pattern STRING_FIELD = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private Json() { }

    /** {"a":"1","b":"2"} from pairs of names and values (null values are left out). */
    static String object(String... namesAndValues) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] == null) {
                continue;
            }
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(escape(namesAndValues[i])).append("\":\"")
                    .append(escape(namesAndValues[i + 1])).append('"');
        }
        return out.append('}').toString();
    }

    /** The text fields of a flat JSON object (anything else is ignored). */
    static Map<String, String> fields(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        Matcher m = STRING_FIELD.matcher(json);
        while (m.find()) {
            out.put(unescape(m.group(1)), unescape(m.group(2)));
        }
        return out;
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
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
        return out.toString();
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                out.append(ch);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(next);   // \" \\ \/
            }
        }
        return out.toString();
    }
}
