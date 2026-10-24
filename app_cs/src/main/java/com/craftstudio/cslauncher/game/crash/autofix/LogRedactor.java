package com.craftstudio.cslauncher.game.crash.autofix;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips personal data from log lines before they leave the device (AI provider, share).
 * Launch command lines carry the session token, the player name and uuid, e-mails show up in
 * account errors and the storage path contains the Android user id.
 */
public final class LogRedactor {
    private LogRedactor() {}

    private static final Pattern P_TOKEN_ARG = Pattern.compile("(--accessToken|--clientId|--xuid|--session|--password|--token)\\s+\\S+");
    private static final Pattern P_USER_ARG = Pattern.compile("(--username|--uuid)\\s+\\S+");
    private static final Pattern P_JWT = Pattern.compile("ey[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}");
    private static final Pattern P_EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern P_UUID = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b|\\b[0-9a-fA-F]{32}\\b");
    private static final Pattern P_IPV4 = Pattern.compile("\\b(?!127\\.0\\.0\\.1\\b)(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{2,5})?\\b");
    private static final Pattern P_STORAGE = Pattern.compile("/storage/emulated/\\d+/");
    private static final Pattern P_DATA = Pattern.compile("/data/(?:user/\\d+|data)/[\\w.]+/");
    private static final Pattern P_SETTING_USER = Pattern.compile("(?i)(Setting user: )\\S+");
    private static final Pattern P_BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]+");
    private static final Pattern P_KEY_EQ = Pattern.compile("(?i)((?:api[_-]?key|token|secret|password)[\"'=:\\s]+)[A-Za-z0-9._\\-$/]{6,}");

    public static String redact(String line) {
        if (line == null || line.isEmpty()) return "";
        String s = line;
        s = P_TOKEN_ARG.matcher(s).replaceAll("$1 <redacted>");
        s = P_USER_ARG.matcher(s).replaceAll("$1 <redacted>");
        s = P_SETTING_USER.matcher(s).replaceAll("$1<redacted>");
        s = P_BEARER.matcher(s).replaceAll("$1<redacted>");
        s = P_KEY_EQ.matcher(s).replaceAll("$1<redacted>");
        s = P_JWT.matcher(s).replaceAll("<redacted-token>");
        s = P_EMAIL.matcher(s).replaceAll("<redacted-email>");
        s = P_UUID.matcher(s).replaceAll("<redacted-id>");
        s = P_IPV4.matcher(s).replaceAll("<redacted-ip>");
        s = P_STORAGE.matcher(s).replaceAll("/storage/emulated/<user>/");
        s = P_DATA.matcher(s).replaceAll("/data/<app>/");
        return s;
    }

    public static List<String> redact(List<String> lines) {
        List<String> out = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines != null) for (String l : lines) out.add(redact(l));
        return out;
    }
}
