package com.craftstudio.cslauncher.modding.modpacks.api;

import android.util.ArrayMap;
import android.util.Log;

import com.google.gson.Gson;

import com.craftstudio.cslauncher.Tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("unused")
public class ApiHandler {
    public final String baseUrl;
    public final Map<String, String> additionalHeaders;

    public ApiHandler(String url) {
        baseUrl = url;
        additionalHeaders = null;
    }

    public ApiHandler(String url, String apiKey) {
        baseUrl = url;
        additionalHeaders = new ArrayMap<>();
        additionalHeaders.put("x-api-key", apiKey);
    }

    public <T> T get(String endpoint, Class<T> tClass) {
        return getFullUrl(additionalHeaders, baseUrl + "/" + endpoint, tClass);
    }

    public <T> T get(String endpoint, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(additionalHeaders, baseUrl + "/" + endpoint, query, tClass);
    }

    public <T> T post(String endpoint, T body, Class<T> tClass) {
        return postFullUrl(additionalHeaders, baseUrl + "/" + endpoint, body, tClass);
    }

    public <T> T post(String endpoint, HashMap<String, Object> query, T body, Class<T> tClass) {
        return postFullUrl(additionalHeaders, baseUrl + "/" + endpoint, query, body, tClass);
    }

    //Make a get request and return the response as a raw string;
    private static final String USER_AGENT = "CSLauncherPlus/1.4 (by Craft Studio)";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    public static String getRaw(String url) {
        return getRaw(null, url);
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 700L;
    /** HTTP status of the last request made on this thread (0 = transport failure), for diagnostics */
    private static final ThreadLocal<Integer> sLastStatus = new ThreadLocal<>();

    /** @return the HTTP status code of the last request issued from the calling thread, 0 if none/transport error */
    public static int getLastStatus() {
        Integer status = sLastStatus.get();
        return status == null ? 0 : status;
    }

    /**
     * GET with bounded retries. Temporary failures (I/O, 408, 429, 5xx) are retried with a short
     * backoff; permanent ones (401/403 bad key, 404, 400) are logged once and returned as null.
     * Only the URL's host+path and the status are logged, never headers or keys.
     */
    public static String getRaw(Map<String, String> headers, String url) {
        String safeUrl = sanitizeUrlForLog(url);
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "application/json");
                addHeaders(conn, headers);
                int status = conn.getResponseCode();
                sLastStatus.set(status);
                if (status / 100 == 2) {
                    try (InputStream inputStream = conn.getInputStream()) {
                        return Tools.read(inputStream);
                    }
                }
                String body = readErrorBody(conn);
                boolean temporary = status == 408 || status == 429 || status >= 500;
                Log.w("ApiHandler", "GET " + safeUrl + " -> HTTP " + status
                        + (body.isEmpty() ? "" : " (" + body + ")")
                        + (temporary && attempt < MAX_ATTEMPTS ? ", retrying" : ""));
                if (!temporary) return null;
                lastError = new IOException("HTTP " + status);
            } catch (IOException e) {
                sLastStatus.set(0);
                lastError = e;
                Log.w("ApiHandler", "GET " + safeUrl + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + (attempt < MAX_ATTEMPTS ? ", retrying" : ""));
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (attempt < MAX_ATTEMPTS) {
                try { Thread.sleep(RETRY_BASE_DELAY_MS * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        if (lastError != null) Log.w("ApiHandler", "GET " + safeUrl + " gave up: " + lastError.getMessage());
        return null;
    }

    /** host + path only: query strings can carry search text, never log credentials by accident */
    private static String sanitizeUrlForLog(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getHost() + parsed.getPath();
        } catch (Exception e) {
            return "<url>";
        }
    }

    /** First ~200 chars of the error body, single line, for the log */
    private static String readErrorBody(HttpURLConnection conn) {
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) return "";
            String body = Tools.read(err);
            body = body.replaceAll("\\s+", " ").trim();
            return body.length() > 200 ? body.substring(0, 200) + "…" : body;
        } catch (Exception e) {
            return "";
        }
    }

    public static String postRaw(String url, String body) {
        return postRaw(null, url, body);
    }

    public static String postRaw(Map<String, String> headers, String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            addHeaders(conn, headers);
            conn.setDoOutput(true);

            OutputStream outputStream = conn.getOutputStream();
            byte[] input = body.getBytes(StandardCharsets.UTF_8);
            outputStream.write(input, 0, input.length);
            outputStream.close();

            InputStream inputStream = conn.getInputStream();
            String data = Tools.read(inputStream);
            inputStream.close();

            conn.disconnect();
            return data;
        } catch (IOException e) {
            Log.w("ApiHandler", "POST " + url + " failed: " + e.getMessage());
        }
        return null;
    }

    private static void addHeaders(HttpURLConnection connection, Map<String, String> headers) {
        if(headers != null) {
            for(String key : headers.keySet())
                connection.addRequestProperty(key, headers.get(key));
        }
    }

    private static String parseQueries(HashMap<String, Object> query) {
        StringBuilder params = new StringBuilder("?");
        for (String param : query.keySet()) {
            String value = Objects.toString(query.get(param));
            params.append(urlEncodeUTF8(param))
                    .append("=")
                    .append(urlEncodeUTF8(value))
                    .append("&");
        }
        return params.substring(0, params.length() - 1);
    }

    public static <T> T getFullUrl(String url, Class<T> tClass) {
        return getFullUrl(null, url, tClass);
    }

    public static <T> T getFullUrl(String url, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(null, url, query, tClass);
    }

    public static <T> T postFullUrl(String url, T body, Class<T> tClass) {
        return postFullUrl(null, url, body, tClass);
    }

    public static <T> T postFullUrl(String url, HashMap<String, Object> query, T body, Class<T> tClass) {
        return postFullUrl(null, url, query, body, tClass);
    }

    public static <T> T getFullUrl(Map<String, String> headers, String url, Class<T> tClass) {
        return new Gson().fromJson(getRaw(headers, url), tClass);
    }

    public static <T> T getFullUrl(Map<String, String> headers, String url, HashMap<String, Object> query, Class<T> tClass) {
        return getFullUrl(headers, url + parseQueries(query), tClass);
    }

    public static <T> T postFullUrl(Map<String, String> headers, String url, T body, Class<T> tClass) {
        return new Gson().fromJson(postRaw(headers, url, body.toString()), tClass);
    }

    public static <T> T postFullUrl(Map<String, String> headers, String url, HashMap<String, Object> query, T body, Class<T> tClass) {
        return new Gson().fromJson(postRaw(headers, url + parseQueries(query), body.toString()), tClass);
    }

    private static String urlEncodeUTF8(String input) {
        try {
            return URLEncoder.encode(input, "UTF-8");
        }catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 is required");
        }
    }
}
