package com.craftstudio.cslauncher.notify;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal PostgREST client (no SDK): GET live rows, POST anonymous delivery events */
final class NotifyApi {
    private NotifyApi() {}

    @NonNull
    static List<JSONObject> fetch(@NonNull String table, @NonNull String query) throws Exception {
        HttpURLConnection c = open(NotifyConfig.URL + "/rest/v1/" + table + "?" + query, "GET");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            String body = read(c.getInputStream());
            JSONArray arr = new JSONArray(body);
            List<JSONObject> rows = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) rows.add(arr.getJSONObject(i));
            return rows;
        } finally {
            c.disconnect();
        }
    }

    /** Fire-and-forget delivery event; failures are ignored (stats only) */
    static void delivery(@NonNull String itemType, @NonNull String itemId, @NonNull String event,
                         @NonNull String deviceHash, int versionCode) {
        try {
            JSONObject o = new JSONObject();
            o.put("item_type", itemType);
            o.put("item_id", itemId);
            o.put("event", event);
            o.put("device_hash", deviceHash);
            o.put("version_code", versionCode);
            HttpURLConnection c = open(NotifyConfig.URL + "/rest/v1/deliveries", "POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "return=minimal");
            byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = c.getOutputStream();
            out.write(data);
            out.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    static byte[] download(@NonNull String url, int maxBytes) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            try {
                if (c.getResponseCode() / 100 != 2) return null;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) return null;
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("apikey", NotifyConfig.ANON_KEY);
        c.setRequestProperty("Authorization", "Bearer " + NotifyConfig.ANON_KEY);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
