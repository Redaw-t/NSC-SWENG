package barcode.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Code 128 barcode encoder (the barcode printed on receipts, so a scanner can
 * type the invoice number back in). Pure logic: no printing, no screen.
 *
 * Handles any printable ASCII text (letters, digits, dash, space, ...). Runs of
 * digits are packed two per symbol (Code Set C) to keep the barcode short, which
 * matters on 58 mm paper: {@code T01-2026-000123} is 178 modules wide.
 *
 * {@link #modules(String)} gives the bars as a row of modules (true = black,
 * false = white), starting and ending with a bar. Add a quiet zone (blank paper
 * of about 10 modules) on each side; paper margins normally provide it.
 */
public final class Code128 {

    private static final int CODE_C = 99;
    private static final int CODE_B = 100;
    private static final int START_B = 104;
    private static final int START_C = 105;
    private static final int STOP = 106;

    /** Bar/space widths of each symbol, starting with a bar. Index = symbol value. */
    private static final String[] PATTERNS = {
            "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
            "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
            "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
            "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
            "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
            "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
            "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
            "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
            "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
            "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
            "114131", "311141", "411131", "211412", "211214", "211232", "2331112"
    };

    private static final Map<String, Integer> BY_PATTERN = new HashMap<>();

    static {
        for (int i = 0; i < PATTERNS.length; i++) {
            BY_PATTERN.put(PATTERNS[i], i);
        }
    }

    private Code128() { }

    /** True if {@code value} is not empty and holds only printable ASCII (space to ~). */
    public static boolean canEncode(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) {
                return false;
            }
        }
        return true;
    }

    /** Number of modules (the barcode's width in narrowest-bar units), without quiet zones. */
    public static int moduleCount(String value) {
        return modules(value).length;
    }

    /** The barcode as modules: true = black bar, false = white. */
    public static boolean[] modules(String value) {
        List<Integer> symbols = symbols(value);
        List<Boolean> out = new ArrayList<>();
        for (int symbol : symbols) {
            boolean bar = true;
            for (char w : PATTERNS[symbol].toCharArray()) {
                for (int k = 0; k < w - '0'; k++) {
                    out.add(bar);
                }
                bar = !bar;
            }
        }
        boolean[] modules = new boolean[out.size()];
        for (int i = 0; i < modules.length; i++) {
            modules[i] = out.get(i);
        }
        return modules;
    }

    /** Symbol values: start, data (with set switches), check symbol, stop. */
    static List<Integer> symbols(String value) {
        if (!canEncode(value)) {
            throw new IllegalArgumentException("Code 128 here supports printable ASCII text only: \"" + value + "\"");
        }
        int len = value.length();
        List<Integer> symbols = new ArrayList<>();
        boolean setC;
        int lead = digitRun(value, 0);
        if ((lead >= 4 || (lead == len && lead >= 2)) && lead % 2 == 0) {
            symbols.add(START_C);
            setC = true;
        } else {
            symbols.add(START_B);
            setC = false;
        }
        int i = 0;
        while (i < len) {
            int run = digitRun(value, i);
            if (setC) {
                if (run >= 2) {
                    symbols.add((value.charAt(i) - '0') * 10 + (value.charAt(i + 1) - '0'));
                    i += 2;
                } else {
                    symbols.add(CODE_B);
                    setC = false;
                }
            } else {
                boolean runEndsString = i + run == len;
                int worthIt = (i == 0 || runEndsString) ? 4 : 6;   // a switch costs 2 symbols
                if (run >= worthIt) {
                    if (run % 2 == 1) {          // odd run: first digit in set B, the rest in pairs
                        symbols.add(value.charAt(i) - 32);
                        i++;
                    }
                    symbols.add(CODE_C);
                    setC = true;
                } else {
                    symbols.add(value.charAt(i) - 32);
                    i++;
                }
            }
        }
        int sum = symbols.get(0);
        for (int k = 1; k < symbols.size(); k++) {
            sum += symbols.get(k) * k;
        }
        symbols.add(sum % 103);
        symbols.add(STOP);
        return symbols;
    }

    private static int digitRun(String s, int from) {
        int n = 0;
        while (from + n < s.length() && s.charAt(from + n) >= '0' && s.charAt(from + n) <= '9') {
            n++;
        }
        return n;
    }

    /**
     * Reads a barcode made by {@link #modules(String)} back into its text and
     * checks start, check symbol and stop. Used by the self-tests to prove the
     * bars really say what they should.
     *
     * @throws IllegalArgumentException if the bars are not a valid Code 128
     */
    public static String decode(boolean[] modules) {
        List<Integer> widths = new ArrayList<>();
        int run = 1;
        for (int i = 1; i < modules.length; i++) {
            if (modules[i] == modules[i - 1]) {
                run++;
            } else {
                widths.add(run);
                run = 1;
            }
        }
        widths.add(run);
        if (!modules[0] || widths.size() < 6 + 6 + 7 || (widths.size() - 7) % 6 != 0) {
            throw new IllegalArgumentException("Not a Code 128 barcode");
        }
        List<Integer> symbols = new ArrayList<>();
        int at = 0;
        while (at < widths.size()) {
            int count = (widths.size() - at == 7) ? 7 : 6;
            StringBuilder key = new StringBuilder();
            for (int k = 0; k < count; k++) {
                key.append(widths.get(at + k));
            }
            Integer symbol = BY_PATTERN.get(key.toString());
            if (symbol == null) {
                throw new IllegalArgumentException("Unknown bar pattern " + key);
            }
            symbols.add(symbol);
            at += count;
        }
        int n = symbols.size();
        if (symbols.get(n - 1) != STOP || (symbols.get(0) != START_B && symbols.get(0) != START_C)) {
            throw new IllegalArgumentException("Missing start or stop");
        }
        int sum = symbols.get(0);
        for (int k = 1; k < n - 2; k++) {
            sum += symbols.get(k) * k;
        }
        if (sum % 103 != symbols.get(n - 2)) {
            throw new IllegalArgumentException("Check symbol does not match");
        }
        StringBuilder text = new StringBuilder();
        boolean setC = symbols.get(0) == START_C;
        for (int k = 1; k < n - 2; k++) {
            int s = symbols.get(k);
            if (setC) {                  // in set C, 0-99 are digit pairs; 100 switches to set B
                if (s == CODE_B) {
                    setC = false;
                } else if (s < 100) {
                    text.append(String.format("%02d", s));
                } else {
                    throw new IllegalArgumentException("Unsupported symbol " + s);
                }
            } else if (s == CODE_C) {    // in set B, 99 switches to set C
                setC = true;
            } else if (s < 96) {
                text.append((char) (s + 32));
            } else {
                throw new IllegalArgumentException("Unsupported symbol " + s);
            }
        }
        return text.toString();
    }
}
