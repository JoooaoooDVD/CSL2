package com.craftstudio.cslauncher.multiplayer.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The session code is the only thing a host shares with friends. Everything else (the EasyTier
 * network name, the network secret and the HTTP auth token of the session directory) is derived
 * from it with SHA-256, so a code is never stored or transmitted in another form.
 * <p>
 * 12 symbols from a 32-symbol alphabet give 60 bits of entropy from {@link SecureRandom}: codes
 * cannot be guessed or enumerated. The alphabet skips 0/O/1/I to survive voice and handwriting;
 * parsing maps those back, ignores case, separators and a "CSL" prefix.
 */
public final class SessionCode {
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Canonical form: XXXX-XXXX-XXXX */
    public final String display;
    /** The 12 raw symbols */
    public final String raw;
    public final String networkName;
    public final String networkSecret;
    public final String authToken;

    private SessionCode(String raw) {
        this.raw = raw;
        this.display = raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
        this.networkName = "cslp-" + sha256Hex("cslp-net|" + raw).substring(0, 20);
        this.networkSecret = sha256Hex("cslp-sec|" + raw).substring(0, 40);
        this.authToken = sha256Hex("cslp-auth|" + raw).substring(0, 32);
    }

    public static SessionCode generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return new SessionCode(sb.toString());
    }

    /** @return the parsed code or null when the input does not contain a valid code */
    public static SessionCode parse(CharSequence input) {
        if (input == null) return null;
        String s = input.toString().toUpperCase(Locale.ROOT).trim();
        // strip an optional CSL / CSLP prefix people may type from the invitation text
        s = s.replaceAll("^(CSLP|CSL)[\\s:#\\-]*", "");
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (ALPHABET.indexOf(c) >= 0) {
                if (sb.length() == LENGTH) return null;
                sb.append(c);
            } else if (!isSeparator(c)) {
                return null;
            }
        }
        if (sb.length() != LENGTH) return null;
        return new SessionCode(sb.toString());
    }

    /** Like {@link #parse} but also finds a code embedded in a pasted invitation text */
    public static SessionCode extract(CharSequence input) {
        SessionCode direct = parse(input);
        if (direct != null || input == null) return direct;
        java.util.regex.Matcher m = EMBEDDED.matcher(input.toString().toUpperCase(Locale.ROOT));
        while (m.find()) {
            SessionCode code = parse(m.group());
            if (code != null) return code;
        }
        return null;
    }

    private static final java.util.regex.Pattern EMBEDDED =
            java.util.regex.Pattern.compile("[" + ALPHABET + "]{4}-[" + ALPHABET + "]{4}-[" + ALPHABET + "]{4}");

    /** Whether the text could still become a valid code (used to enable the Join button early) */
    public static boolean looksComplete(CharSequence input) {
        return parse(input) != null;
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '-' || c == '_' || c == '.' || c == ',' || c == '\u2011' || c == '\u2013' || c == '\u2014' || c == '\n' || c == '\t';
    }

    /** Live input formatting: uppercase, drop foreign characters, dash every 4, max 12 letters */
    public static String formatPartial(CharSequence input) {
        if (input == null) return "";
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < input.length() && raw.length() < LENGTH; i++) {
            char c = Character.toUpperCase(input.charAt(i));
            if (c == '0') c = 'O';
            if (c == '1' || c == 'I') c = 'L';
            if (ALPHABET.indexOf(c) >= 0) raw.append(c);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(raw.charAt(i));
        }
        return out.toString();
    }

    @Override public String toString() { return display; }

    @Override public boolean equals(Object o) { return o instanceof SessionCode && ((SessionCode) o).raw.equals(raw); }
    @Override public int hashCode() { return raw.hashCode(); }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
