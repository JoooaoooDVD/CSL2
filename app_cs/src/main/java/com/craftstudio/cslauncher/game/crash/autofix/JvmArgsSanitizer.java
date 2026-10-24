package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Removes only the JVM options the JVM itself rejected. Everything else in the user's argument
 * string is kept verbatim (including quoting, order and unrelated -XX flags).
 */
public final class JvmArgsSanitizer {
    private JvmArgsSanitizer() {}

    public static final class Result {
        public final String args;
        public final List<String> removed;
        Result(String args, List<String> removed) { this.args = args; this.removed = removed; }
        public boolean changed() { return !removed.isEmpty(); }
    }

    /**
     * @param args the raw argument string (may be null/empty)
     * @param token the offending token as reported by the JVM: "-Xfoo", "-XX:+UseFoo", "-XX:Foo=1",
     *              a bare option name "UseFoo" / "Foo=1", or for heap problems "-Xmx" / "-Xms"
     *              (prefix match). For "gc-conflict" the caller passes "-XX:+Use" with detail
     *              "gc-conflict" which removes every explicit collector selection.
     */
    public static Result prune(String args, String token, String detail) {
        List<String> parts = split(args == null ? "" : args);
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        String t = token == null ? "" : token.trim();
        for (String p : parts) {
            if (matches(p, t, detail)) removed.add(p); else kept.add(p);
        }
        return new Result(join(kept), removed);
    }

    /** True when the argument string still carries something that matches the token. */
    public static boolean contains(String args, String token, String detail) {
        for (String p : split(args == null ? "" : args)) if (matches(p, token == null ? "" : token.trim(), detail)) return true;
        return false;
    }

    static boolean matches(String part, String token, String detail) {
        if (part.isEmpty() || token.isEmpty()) return false;
        String p = part;
        String pLow = p.toLowerCase(Locale.ROOT);
        if ("gc-conflict".equals(detail)) {
            return pLow.startsWith("-xx:+use") && pLow.endsWith("gc");
        }
        if ("heap".equals(detail)) {
            return pLow.startsWith(token.toLowerCase(Locale.ROOT));
        }
        if (p.equals(token)) return true;
        // Bare names reported by HotSpot ("Unrecognized VM option 'UseFoo'") → -XX:+UseFoo / -XX:-UseFoo / -XX:UseFoo=..
        String bare = token.startsWith("-XX:") ? token.substring(4) : token;
        if (bare.startsWith("+") || bare.startsWith("-")) bare = bare.substring(1);
        int eq = bare.indexOf('=');
        String bareName = eq >= 0 ? bare.substring(0, eq) : bare;
        if (bareName.isEmpty()) return false;
        if (p.startsWith("-XX:")) {
            String body = p.substring(4);
            if (body.startsWith("+") || body.startsWith("-")) body = body.substring(1);
            int e2 = body.indexOf('=');
            String name = e2 >= 0 ? body.substring(0, e2) : body;
            return name.equals(bareName);
        }
        // "Unrecognized option: -Xfoo=bar" → token has the full form; also match by name before '='
        int te = token.indexOf('=');
        if (te > 0) {
            String tn = token.substring(0, te);
            int pe = p.indexOf('=');
            String pn = pe > 0 ? p.substring(0, pe) : p;
            return pn.equals(tn);
        }
        return false;
    }

    /** Shell-like split: whitespace separated, quotes keep groups and are preserved in the token. */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c; cur.append(c);
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (sb.length() > 0) sb.append(' '); sb.append(p); }
        return sb.toString();
    }
}
