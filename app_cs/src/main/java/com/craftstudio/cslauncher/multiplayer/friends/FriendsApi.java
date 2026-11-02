package com.craftstudio.cslauncher.multiplayer.friends;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * CS Friends backend: Supabase RPC functions (Postgres, SECURITY DEFINER) behind the project's
 * publishable key. Every call carries the device's secret friend token; the tables themselves are
 * not readable with this key. Blocking: call from a worker thread.
 */
public final class FriendsApi {
    private static final String URL_BASE = "https://ygteavpkursyeyfthlxm.supabase.co/rest/v1/rpc/";
    private static final String KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlndGVhdnBrdXJzeWV5ZnRobHhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI2ODk5NjksImV4cCI6MjEwODI2NTk2OX0.wqCioElyk_GlIkeaiRHlls7hk-WDABhigwb1EGy8iJU";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private FriendsApi() {}

    public static final class Friend {
        public String id, name, code, status, address, world, engine;
        public boolean online() { return !"offline".equals(status); }
        public boolean hosting() { return "hosting".equals(status) && address != null && !address.isEmpty(); }
    }

    public static final class Request {
        public String id, name, code;
        public boolean incoming;
    }

    public static final class Invite {
        public long id;
        public String from, fromId, address, world, engine;
    }

    /** One heartbeat answer: who I am, my friends, pending requests and new invites. */
    public static final class Snapshot {
        public String myId, myCode, myName;
        public final List<Friend> friends = new ArrayList<>();
        public final List<Request> requests = new ArrayList<>();
        public final List<Invite> invites = new ArrayList<>();
    }

    public static final class ApiException extends IOException {
        public ApiException(String message) { super(message); }
    }

    @NonNull
    public static JSONObject register(@NonNull String token, @NonNull String name) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("p_token", token);
            p.put("p_name", name);
        } catch (JSONException ignored) {}
        return object(call("cs_register", p));
    }

    @NonNull
    public static Snapshot heartbeat(@NonNull String token, @NonNull String status, @Nullable String address,
                                     @Nullable String world, @Nullable String engine) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("p_token", token);
            p.put("p_status", status);
            p.put("p_address", address == null ? "" : address);
            p.put("p_world", world == null ? "" : world);
            p.put("p_engine", engine == null ? "" : engine);
        } catch (JSONException ignored) {}
        JSONObject o = object(call("cs_heartbeat", p));
        Snapshot s = new Snapshot();
        JSONObject me = o.optJSONObject("me");
        if (me != null) {
            s.myId = me.optString("id");
            s.myCode = me.optString("code");
            s.myName = me.optString("name");
        }
        JSONArray friends = o.optJSONArray("friends");
        for (int i = 0; friends != null && i < friends.length(); i++) {
            JSONObject f = friends.optJSONObject(i);
            if (f == null) continue;
            Friend fr = new Friend();
            fr.id = f.optString("id");
            fr.name = f.optString("name");
            fr.code = f.optString("code");
            fr.status = f.optString("status", "offline");
            fr.address = f.isNull("address") ? null : f.optString("address");
            fr.world = f.isNull("world") ? null : f.optString("world");
            fr.engine = f.isNull("engine") ? null : f.optString("engine");
            s.friends.add(fr);
        }
        JSONArray requests = o.optJSONArray("requests");
        for (int i = 0; requests != null && i < requests.length(); i++) {
            JSONObject r = requests.optJSONObject(i);
            if (r == null) continue;
            Request rq = new Request();
            rq.id = r.optString("id");
            rq.name = r.optString("name");
            rq.code = r.optString("code");
            rq.incoming = r.optBoolean("incoming");
            s.requests.add(rq);
        }
        JSONArray invites = o.optJSONArray("invites");
        for (int i = 0; invites != null && i < invites.length(); i++) {
            JSONObject v = invites.optJSONObject(i);
            if (v == null) continue;
            Invite in = new Invite();
            in.id = v.optLong("id");
            in.from = v.optString("from");
            in.fromId = v.optString("from_id");
            in.address = v.optString("address");
            in.world = v.isNull("world") ? null : v.optString("world");
            in.engine = v.isNull("engine") ? null : v.optString("engine");
            s.invites.add(in);
        }
        return s;
    }

    /** Sends a request to the owner of {@code code}; answers requested / accepted / already. */
    @NonNull
    public static JSONObject addFriend(@NonNull String token, @NonNull String code) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("p_token", token);
            p.put("p_code", code.trim().toUpperCase());
        } catch (JSONException ignored) {}
        return object(call("cs_add_friend", p));
    }

    public static void respond(@NonNull String token, @NonNull String otherId, boolean accept) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("p_token", token);
            p.put("p_other", otherId);
            p.put("p_accept", accept);
        } catch (JSONException ignored) {}
        call("cs_respond", p);
    }

    public static void removeFriend(@NonNull String token, @NonNull String otherId) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("p_token", token);
            p.put("p_other", otherId);
        } catch (JSONException ignored) {}
        call("cs_remove_friend", p);
    }

    /** Invites one friend ({@code toId}) or every friend ({@code null}) to {@code address}. Returns how many. */
    public static int invite(@NonNull String token, @Nullable String toId, @NonNull String address,
                             @Nullable String world, @Nullable String engine) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("p_token", token);
            p.put("p_to", toId == null ? JSONObject.NULL : toId);
            p.put("p_address", address);
            p.put("p_world", world == null ? "" : world);
            p.put("p_engine", engine == null ? "" : engine);
        } catch (JSONException ignored) {}
        return object(call("cs_invite", p)).optInt("sent");
    }

    // ---- transport ----

    @NonNull
    private static JSONObject object(@NonNull String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new ApiException("Bad answer: " + body);
        }
    }

    @NonNull
    private static String call(@NonNull String fn, @NonNull JSONObject params) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + fn).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("apikey", KEY);
        c.setRequestProperty("Authorization", "Bearer " + KEY);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        byte[] payload = params.toString().getBytes(UTF8);
        OutputStream out = c.getOutputStream();
        try {
            out.write(payload);
        } finally {
            out.close();
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(in);
        c.disconnect();
        if (status >= 400) {
            String message = body;
            try {
                JSONObject err = new JSONObject(body);
                message = err.optString("message", body);
            } catch (JSONException ignored) {}
            throw new ApiException(message == null || message.isEmpty() ? "HTTP " + status : message);
        }
        return body;
    }

    @NonNull
    private static String read(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        try {
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        } finally {
            in.close();
        }
        return new String(buf.toByteArray(), UTF8);
    }
}
