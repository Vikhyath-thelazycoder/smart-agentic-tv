package io.github.smartagentictv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The AI services, called from the TV so the keys never go on a phone. Gemini first, Groq if that fails. Models get
 * retired, so when one is gone the service's own list is asked for a replacement, which is then remembered.
 */
final class Ai {
    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/", GROQ = "https://api.groq.com/openai/v1/";
    private static final String GEMINI_FIRST = "gemini-3.5-flash-lite";
    private static final String[] GROQ_ORDER = {"openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b"};
    private static final int CONNECT_MS = 5000, GEMINI_MS = 12_000, GROQ_MS = 10_000, GONE = 404;

    /** Who answered, and what they said. */
    static final class Answer {
        final String service, text;
        Answer(String service, String text) { this.service = service; this.text = text; }
    }

    private static final class Refused extends IOException {
        final int code;
        Refused(int code) { super("answered " + code); this.code = code; }
    }

    private final SharedPreferences remembered;

    Ai(Context c) {
        remembered = c.getSharedPreferences("ai-models", Context.MODE_PRIVATE);
    }

    /** `only` names one service, for testing the fallback; null tries Gemini and then Groq. */
    Answer ask(String system, String question, String only) throws IOException {
        IOException last = null;
        if (only == null || only.equals("gemini")) {
            try {
                return new Answer("gemini", gemini(system, question));
            } catch (IOException | JSONException e) {
                last = new IOException("gemini: " + e.getMessage());
                AppLog.w(last.getMessage());
            }
        }
        if (only == null || only.equals("groq")) {
            try {
                return new Answer("groq", groq(system, question));
            } catch (IOException | JSONException e) {
                last = new IOException("groq: " + e.getMessage());
                AppLog.w(last.getMessage());
            }
        }
        throw last != null ? last : new IOException("no such service");
    }

    /**
     * A conversation: `turns` holds earlier {said, answered} pairs. `json` asks for one JSON object back. A picture goes
     * to Gemini only, because Groq's models here don't take one.
     */
    Answer chat(String system, List<String[]> turns, String question, boolean json, String jpegBase64) throws IOException {
        IOException last;
        try {
            return new Answer("gemini", gemini(system, turns, question, json, jpegBase64));
        } catch (IOException | JSONException e) {
            last = new IOException("gemini: " + e.getMessage());
            AppLog.w(last.getMessage());
        }
        if (jpegBase64 != null) throw last;
        try {
            return new Answer("groq", groq(system, turns, question, json));
        } catch (IOException | JSONException e) {
            last = new IOException("groq: " + e.getMessage());
            AppLog.w(last.getMessage());
        }
        throw last;
    }

    private String gemini(String system, String question) throws IOException, JSONException {
        return gemini(system, new ArrayList<>(), question, false, null);
    }

    private String gemini(String system, List<String[]> turns, String question, boolean json, String jpegBase64) throws IOException, JSONException {
        String key = Keys.gemini();
        if (key.isEmpty()) throw new IOException("no key");
        String model = remembered.getString("gemini", Config.GEMINI_MODEL.trim().isEmpty() ? GEMINI_FIRST : Config.GEMINI_MODEL.trim());
        JSONArray contents = new JSONArray();
        for (String[] t : turns) {
            contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", t[0]))));
            contents.put(new JSONObject().put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", t[1]))));
        }
        JSONArray parts = new JSONArray().put(new JSONObject().put("text", question));
        if (jpegBase64 != null) {
            parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", jpegBase64)));
        }
        contents.put(new JSONObject().put("role", "user").put("parts", parts));
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", contents);
        if (json) body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json"));
        try {
            return geminiCall(model, body, key);
        } catch (Refused e) {
            if (e.code != GONE) throw e;
            String next = newGeminiModel(key);
            remembered.edit().putString("gemini", next).apply();
            AppLog.w("gemini model replaced: " + next);
            return geminiCall(next, body, key);
        }
    }

    private static String geminiCall(String model, JSONObject body, String key) throws IOException, JSONException {
        JSONObject o = new JSONObject(request(GEMINI + "models/" + model + ":generateContent", "x-goog-api-key", key, body, GEMINI_MS));
        return o.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
    }

    /** A plain Flash model the service still offers. */
    private static String newGeminiModel(String key) throws IOException, JSONException {
        JSONArray models = new JSONObject(request(GEMINI + "models?pageSize=100", "x-goog-api-key", key, null, GEMINI_MS)).getJSONArray("models");
        List<String> flash = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            String name = m.getString("name").replaceFirst("^models/", "").toLowerCase(Locale.ROOT);
            if (name.contains("flash") && !name.matches(".*(image|tts|audio|live|preview).*")
                    && m.optJSONArray("supportedGenerationMethods") != null && m.getJSONArray("supportedGenerationMethods").toString().contains("generateContent")) {
                flash.add(name);
            }
        }
        if (flash.isEmpty()) throw new IOException("no model left");
        for (String name : flash) if (name.contains("lite")) return name; // the free plan allows the most of these
        return flash.get(0);
    }

    private String groq(String system, String question) throws IOException, JSONException {
        return groq(system, new ArrayList<>(), question, false);
    }

    private String groq(String system, List<String[]> turns, String question, boolean json) throws IOException, JSONException {
        String key = Keys.groq();
        if (key.isEmpty()) throw new IOException("no key");
        String model = remembered.getString("groq", GROQ_ORDER[0]);
        try {
            return groqCall(model, messages(system, turns, question), json, key);
        } catch (Refused e) {
            if (e.code != GONE && e.code != 400) throw e;
            String next = newGroqModel(key);
            remembered.edit().putString("groq", next).apply();
            AppLog.w("groq model replaced: " + next);
            return groqCall(next, messages(system, turns, question), json, key);
        }
    }

    private static JSONArray messages(String system, List<String[]> turns, String question) throws JSONException {
        JSONArray m = new JSONArray().put(new JSONObject().put("role", "system").put("content", system));
        for (String[] t : turns) {
            m.put(new JSONObject().put("role", "user").put("content", t[0]));
            m.put(new JSONObject().put("role", "assistant").put("content", t[1]));
        }
        return m.put(new JSONObject().put("role", "user").put("content", question));
    }

    private static String groqCall(String model, JSONArray messages, boolean json, String key) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("model", model).put("messages", messages);
        if (json) body.put("response_format", new JSONObject().put("type", "json_object"));
        return new JSONObject(request(GROQ + "chat/completions", "Authorization", "Bearer " + key, body, GROQ_MS))
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    }

    private static String newGroqModel(String key) throws IOException, JSONException {
        JSONArray data = new JSONObject(request(GROQ + "models", "Authorization", "Bearer " + key, null, GROQ_MS)).getJSONArray("data");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) ids.add(data.getJSONObject(i).getString("id"));
        for (String wanted : GROQ_ORDER) if (ids.contains(wanted)) return wanted;
        for (String id : ids) if (!id.matches(".*(whisper|orpheus|guard|tts).*")) return id;
        throw new IOException("no model left");
    }

    private static String request(String url, String header, String value, JSONObject body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty(header, value);
        c.setRequestProperty("User-Agent", "my-tv");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new Refused(code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toString("UTF-8");
    }
}
