package com.craftstudio.cslauncher.friends;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Thin RPC client for the CS friends backend (Supabase PostgREST, anon role, token in the body). */
public final class FriendsApi {
    private static final String BASE = "https://ygteavpkursyeyfthlxm.supabase.co/rest/v1/rpc/";
    private static final String ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlndGVhdnBrdXJzeWV5ZnRobHhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI2ODk5NjksImV4cCI6MjEwODI2NTk2OX0."
            + "wqCioElyk_GlIkeaiRHlls7hk-WDABhigwb1EGy8iJU";

    /** A backend refusal in plain English (wrong password, unknown code, ...). */
    public static final class ApiException extends IOException {
        public ApiException(String message) { super(message); }
    }

    private FriendsApi() {}

    /** Calls {@code fn} with {@code args}; returns the JSON result (object, or wrapped primitive). */
    @NonNull
    public static JSONObject call(@NonNull String fn, @NonNull JSONObject args) throws IOException {
        String raw = post(fn, args.toString());
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty() || "null".equals(raw)) return new JSONObject();
        try {
            if (raw.startsWith("{")) return new JSONObject(raw);
            return new JSONObject().put("value", raw.startsWith("\"") ? new org.json.JSONTokener(raw).nextValue() : raw);
        } catch (JSONException e) {
            throw new IOException("Bad reply from the friends service");
        }
    }

    @Nullable
    private static String post(String fn, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + fn).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("apikey", ANON_KEY);
            c.setRequestProperty("Authorization", "Bearer " + ANON_KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = read(in);
            if (code >= 400) {
                String msg = null;
                try {
                    JSONObject err = new JSONObject(text);
                    msg = err.optString("message", null);
                    if (msg == null || msg.isEmpty()) msg = err.optString("hint", null);
                } catch (JSONException ignored) {}
                if (msg == null || msg.isEmpty()) msg = "Friends service error (" + code + ")";
                throw new ApiException(msg);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
