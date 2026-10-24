package com.craftstudio.cslauncher.game.crash.autofix.ai;

import com.craftstudio.cslauncher.game.crash.autofix.CrashCategory;
import com.craftstudio.cslauncher.game.crash.autofix.PathGuard;
import com.craftstudio.cslauncher.game.crash.autofix.RepairStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict parser for provider answers. Anything that is not exactly the documented shape is
 * rejected with a reason, and every action target is checked against the whitelist the request
 * carried. The validator never looks at the file system.
 */
public final class AiResponseValidator {
    public static final class InvalidResponse extends Exception {
        public InvalidResponse(String msg) { super(msg); }
    }

    private static final int MAX_ACTIONS = 3;
    private static final int MAX_TEXT = 400;

    private AiResponseValidator() {}

    public static AiDiagnosis validate(String raw, AiRequest request) throws InvalidResponse {
        if (raw == null || raw.trim().isEmpty()) throw new InvalidResponse("empty response");
        String text = raw.trim();
        // Tolerate a fenced block, nothing else.
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (nl < 0 || end <= nl) throw new InvalidResponse("malformed fence");
            text = text.substring(nl + 1, end).trim();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (Exception e) {
            throw new InvalidResponse("not JSON");
        }
        if (!root.isJsonObject()) throw new InvalidResponse("root is not an object");
        JsonObject o = root.getAsJsonObject();
        for (String k : o.keySet()) {
            if (!(k.equals("category") || k.equals("confidence") || k.equals("explanation") || k.equals("component") || k.equals("actions")))
                throw new InvalidResponse("unexpected key " + k);
        }
        CrashCategory category = CrashCategory.parse(str(o, "category", true));
        if (category == null) throw new InvalidResponse("unknown category");
        float conf;
        try {
            conf = o.get("confidence").getAsFloat();
        } catch (Exception e) {
            throw new InvalidResponse("confidence missing");
        }
        if (Float.isNaN(conf) || conf < 0f || conf > 1f) throw new InvalidResponse("confidence out of range");
        String explanation = clip(str(o, "explanation", true));
        String component = clip(str(o, "component", false));
        List<RepairStep> steps = new ArrayList<>();
        if (o.has("actions")) {
            if (!o.get("actions").isJsonArray()) throw new InvalidResponse("actions is not an array");
            JsonArray arr = o.getAsJsonArray("actions");
            if (arr.size() > MAX_ACTIONS) throw new InvalidResponse("too many actions");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) throw new InvalidResponse("action is not an object");
                JsonObject a = el.getAsJsonObject();
                String kindName = str(a, "kind", true);
                RepairStep.Kind kind;
                try {
                    kind = RepairStep.Kind.valueOf(kindName.trim().toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    throw new InvalidResponse("unknown action kind " + kindName);
                }
                String target = clip(str(a, "target", false));
                String detail = clip(str(a, "detail", false));
                checkTarget(kind, target, request);
                steps.add(new RepairStep(kind, target, detail));
            }
        }
        return new AiDiagnosis(category, conf, explanation, component, steps);
    }

    private static void checkTarget(RepairStep.Kind kind, String target, AiRequest req) throws InvalidResponse {
        String t = target.toLowerCase(Locale.ROOT);
        switch (kind) {
            case DISABLE_MOD:
            case ENABLE_MOD:
                if (!containsIgnoreCase(req.installedModIds, t)) throw new InvalidResponse("mod target not installed: " + target);
                break;
            case DISABLE_RESOURCE_PACK:
                if (!t.isEmpty() && !containsIgnoreCase(req.enabledResourcePacks, t) && !containsIgnoreCase(req.enabledResourcePacks, "file/" + t))
                    throw new InvalidResponse("resource pack target not enabled: " + target);
                break;
            case RESET_CONFIG:
                if (t.isEmpty() || !containsIgnoreCase(req.configFiles, t)) throw new InvalidResponse("config target unknown: " + target);
                if (PathGuard.resolveRelative(new java.io.File("/"), target) == null && target.contains("..")) throw new InvalidResponse("config path not allowed");
                break;
            case INSTALL_DEPENDENCY:
                if (!t.matches("[a-z0-9_\\-.]{2,64}")) throw new InvalidResponse("dependency id not allowed: " + target);
                break;
            case PRUNE_JVM_ARGS:
                if (!target.startsWith("-") || target.length() > 80 || target.contains(" ")) throw new InvalidResponse("jvm token not allowed: " + target);
                break;
            case SELECT_RUNTIME:
                if (!t.matches("\\d{1,2}")) throw new InvalidResponse("runtime target must be a major version");
                break;
            default:
                // kinds without a meaningful target: target must not try to smuggle a path
                if (target.contains("/") || target.contains("\\")) throw new InvalidResponse("path not allowed for " + kind);
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        for (String s : list) if (s != null && s.toLowerCase(Locale.ROOT).equals(v)) return true;
        return false;
    }

    private static String str(JsonObject o, String key, boolean required) throws InvalidResponse {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (required) throw new InvalidResponse(key + " missing");
            return "";
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new InvalidResponse(key + " is not a string");
        return e.getAsString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }
}
