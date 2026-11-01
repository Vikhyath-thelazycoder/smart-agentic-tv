package io.github.smartagentictv.tv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The phone's shows and movies: search, which of this TV's apps has a title, opening it, and the saved list. Lives on
 * the TV so the keys stay here, and so the saved list doesn't depend on one phone.
 */
final class Titles {
    private static final String TMDB = "https://api.themoviedb.org/3", WATCHMODE = "https://api.watchmode.com/v1";
    private static final String SAVED_FILE = "saved-titles.json", WATCHED_FILE = "watched-titles.json";
    private static final int MAX_RESULTS = 12, MAX_SAVED = 200, MAX_TEXT = 200, MAX_WATCHED = 20, WORDS_TRIED = 3, LOOKUPS = 4;
    private static final long LOOKUP_WAIT_MS = 8000, NO_LINK_KEEP_MS = 14 * 24 * 3600_000L, LINK_KEEP_MS = 90 * 24 * 3600_000L;

    /** A guess at what a misheard title really is, for when the plain searches find nothing. */
    interface Guesser { List<String> guess(String words); }

    private static final String[] OFFER_KINDS = {"flatrate", "free", "ads"};

    private final Context context;
    private final SharedPreferences links;
    private final Object savedLock = new Object();
    private final Map<String, JSONObject> known = new ConcurrentHashMap<>();

    Titles(Context c) {
        context = c;
        links = c.getSharedPreferences("title-links", Context.MODE_PRIVATE);
    }

    /** Movies and shows matching the words, as a JSON array. */
    private JSONArray search(String words) throws IOException, JSONException {
        JSONObject o = tmdb("/search/multi?include_adult=false&language=en-IN&region=" + Keys.region()
                + "&query=" + URLEncoder.encode(words, "UTF-8"));
        JSONArray in = o.optJSONArray("results"), out = new JSONArray();
        for (int i = 0; in != null && i < in.length() && out.length() < MAX_RESULTS; i++) {
            JSONObject r = in.getJSONObject(i);
            String kind = r.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv")) continue;
            String date = r.optString(kind.equals("movie") ? "release_date" : "first_air_date");
            out.put(new JSONObject().put("id", r.optInt("id")).put("kind", kind)
                    .put("title", r.optString(kind.equals("movie") ? "title" : "name"))
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "")
                    .put("poster", r.isNull("poster_path") ? "" : r.optString("poster_path")));
        }
        return out;
    }

    private static boolean anyRelated(JSONArray found, String asked) {
        for (int i = 0; i < found.length(); i++) if (Words.related(found.optJSONObject(i).optString("title"), asked)) return true;
        return false;
    }

    /**
     * Search that forgives wrong words: the whole phrase, then without words like "movie", then each word alone, then
     * the AI's guess. Each step only runs if the one before found nothing like what was asked, so most searches cost
     * one request. Every result says which of this TV's apps has it.
     */
    JSONArray smartFind(String words, Guesser guesser) throws IOException, JSONException {
        JSONArray found = search(words);
        JSONArray firstTry = found;
        if (!anyRelated(found, words)) {
            String trimmed = Words.withoutFillers(words);
            if (!trimmed.equals(Words.normal(words))) found = search(trimmed);
        }
        if (!anyRelated(found, words)) {
            JSONArray merged = new JSONArray();
            for (String w : Words.singleWords(words, WORDS_TRIED)) add(merged, search(w), words);
            found = merged;
        }
        if (found.length() == 0 && guesser != null) {
            for (String guess : guesser.guess(words)) {
                JSONArray hits = search(guess);
                for (int i = 0; i < hits.length() && i < 3; i++) found.put(hits.get(i));
            }
        }
        return withApps(found.length() > 0 ? found : firstTry);
    }

    private static void add(JSONArray into, JSONArray hits, String asked) throws JSONException {
        for (int i = 0; i < hits.length(); i++) {
            JSONObject t = hits.getJSONObject(i);
            if (Words.related(t.optString("title"), asked) && !has(into, t)) into.put(t);
        }
    }

    private static boolean has(JSONArray list, JSONObject t) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o.optInt("id") == t.optInt("id") && o.optString("kind").equals(t.optString("kind"))) return true;
        }
        return false;
    }

    /** The first result that is what was asked for, or null if none is close enough to open without asking. */
    static JSONObject best(JSONArray found, String asked, String kind, String year) {
        JSONObject first = null;
        for (int i = 0; i < found.length(); i++) {
            JSONObject t = found.optJSONObject(i);
            if (!Words.sameTitle(t.optString("title"), asked)) continue;
            if (first == null) first = t;
            boolean kindFits = kind.isEmpty() || kind.equals(t.optString("kind")), yearFits = year.isEmpty() || year.equals(t.optString("year"));
            if (kindFits && yearFits) return t;
        }
        return first;
    }

    /** One title per name, for suggestions: those the TV can open come first, and names TMDB doesn't know are dropped. */
    JSONArray resolve(List<String> names) throws JSONException {
        JSONArray found = new JSONArray();
        for (String name : names) {
            try {
                JSONObject b = bestOrFirst(search(name), name);
                if (b != null && !has(found, b)) found.put(b);
            } catch (IOException e) {
                AppLog.w("suggestion lookup: " + e.getMessage());
            }
        }
        JSONArray withApps = withApps(found), open = new JSONArray(), rest = new JSONArray();
        for (int i = 0; i < withApps.length(); i++) {
            JSONObject t = withApps.getJSONObject(i);
            (t.getJSONArray("apps").length() > 0 ? open : rest).put(t);
        }
        for (int i = 0; i < rest.length(); i++) open.put(rest.get(i));
        return open;
    }

    private static JSONObject bestOrFirst(JSONArray found, String name) {
        JSONObject b = best(found, name, "", "");
        return b != null || found.length() == 0 || !Words.related(found.optJSONObject(0).optString("title"), name) ? b : found.optJSONObject(0);
    }

    /** Adds "apps" to each title, asking TMDB for several at once. A slow answer just leaves that row without apps. */
    private JSONArray withApps(JSONArray titles) throws JSONException {
        ExecutorService pool = Executors.newFixedThreadPool(LOOKUPS);
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.getJSONObject(i);
            pool.execute(() -> {
                try {
                    JSONArray apps = details(t.optString("kind"), t.optInt("id")).optJSONArray("apps");
                    synchronized (t) { t.put("apps", apps == null ? new JSONArray() : apps); }
                } catch (IOException | JSONException e) {
                    AppLog.w("where: " + e.getMessage());
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(LOOKUP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.getJSONObject(i);
            synchronized (t) { if (!t.has("apps")) t.put("apps", new JSONArray()); }
        }
        return titles;
    }

    /** The story and apps from TMDB, remembered until the service restarts. */
    private JSONObject details(String kind, int id) throws IOException, JSONException {
        String key = kind + id;
        JSONObject have = known.get(key);
        if (have != null) return have;
        JSONObject o = tmdb("/" + kind + "/" + id + "?language=en-IN&append_to_response=watch/providers");
        JSONObject results = o.optJSONObject("watch/providers") == null ? null : o.getJSONObject("watch/providers").optJSONObject("results");
        JSONObject here = results == null ? null : results.optJSONObject(Keys.region());
        Map<String, String> apps = new LinkedHashMap<>();
        JSONArray elsewhere = new JSONArray();
        for (String offer : OFFER_KINDS) {
            JSONArray list = here == null ? null : here.optJSONArray(offer);
            for (int i = 0; list != null && i < list.length(); i++) {
                String name = list.getJSONObject(i).optString("provider_name");
                String pkg = installedApp(name);
                if (pkg != null) apps.put(pkg, label(pkg));
                else if (!contains(elsewhere, name)) elsewhere.put(name);
            }
        }
        JSONArray appList = new JSONArray();
        for (Map.Entry<String, String> e : apps.entrySet()) appList.put(new JSONObject().put("pkg", e.getKey()).put("name", e.getValue()));
        JSONObject out = new JSONObject().put("overview", o.optString("overview")).put("poster", o.isNull("poster_path") ? "" : o.optString("poster_path"))
                .put("apps", appList).put("elsewhere", elsewhere);
        known.put(key, out);
        return out;
    }

    /** The story, the TV's apps that have it (TMDB's list and the title links, which often know more), and services this TV doesn't have. */
    String where(String kind, int id) throws IOException, JSONException {
        JSONObject out = new JSONObject(details(kind, id).toString());
        JSONArray apps = out.getJSONArray("apps");
        Set<String> listed = new HashSet<>();
        for (int i = 0; i < apps.length(); i++) listed.add(apps.getJSONObject(i).optString("pkg"));
        for (String pkg : links(kind, id).keySet()) {
            if (listed.add(pkg)) apps.put(new JSONObject().put("pkg", pkg).put("name", label(pkg)));
        }
        return out.toString();
    }

    /** Opens the title in the app if there's a link to it. False if there isn't one. */
    boolean open(String kind, int id, String pkg) {
        String link = links(kind, id).get(pkg);
        if (link != null) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                AppLog.w("opened a title in " + pkg);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w(pkg + " doesn't take title links: " + e.getMessage());
            }
        }
        return false;
    }

    /** Which installed app a service is, from the home screen's list. */
    private String installedApp(String provider) {
        String name = provider.toLowerCase(Locale.ROOT);
        for (String[] s : Recs.SERVICES) {
            if (name.contains(s[0]) && installed(s[2])) return s[2];
        }
        return null;
    }

    private boolean installed(String pkg) {
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static boolean contains(JSONArray a, String s) {
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    // MARK: - Links to a title (2 requests of Watchmode's free 2,500 a month, then remembered)

    /** This TV's apps with a link to the title. Subscription links win over rentals, because they are what plays. */
    private Map<String, String> links(String kind, int id) {
        String key = "all-" + kind + id;
        Map<String, String> out = new LinkedHashMap<>();
        try {
            String known = links.getString(key, null);
            if (known != null) {
                JSONObject o = new JSONObject(known);
                JSONObject stored = o.getJSONObject("links");
                long age = System.currentTimeMillis() - o.optLong("at");
                if (age < (stored.length() == 0 ? NO_LINK_KEEP_MS : LINK_KEEP_MS)) {
                    for (java.util.Iterator<String> i = stored.keys(); i.hasNext(); ) {
                        String pkg = i.next();
                        out.put(pkg, stored.getString(pkg));
                    }
                    return out;
                }
            }
            if (Keys.watchmode().isEmpty()) return out;
            Set<String> paid = new HashSet<>();
            JSONArray titles = new JSONObject(watchmode("/search/?search_field=tmdb_" + kind + "_id&search_value=" + id)).optJSONArray("title_results");
            if (titles != null && titles.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + titles.getJSONObject(0).getInt("id") + "/sources/?regions=" + Keys.region()));
                for (int i = 0; i < sources.length(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String url = s.optString("web_url"), name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    if (!url.startsWith("https://")) continue;
                    boolean included = s.optString("type").equals("sub") || s.optString("type").equals("free");
                    for (String[] service : Recs.SERVICES) {
                        if (!name.contains(service[1]) || !installed(service[2])) continue;
                        if (!out.containsKey(service[2]) || (included && paid.contains(service[2]))) {
                            out.put(service[2], url);
                            if (included) paid.remove(service[2]);
                            else paid.add(service[2]);
                        }
                    }
                }
            }
            links.edit().putString(key, new JSONObject().put("at", System.currentTimeMillis()).put("links", new JSONObject(out)).toString()).apply();
        } catch (IOException | JSONException e) {
            AppLog.w("title link: " + e.getMessage()); // not remembered: it can work next time
        }
        return out;
    }

    // MARK: - Continue watching: what was opened from the phone. The apps themselves don't tell other apps what's been watched.

    void remember(String kind, int id, String title, String pkg) {
        try {
            JSONObject entry = new JSONObject().put("id", id).put("kind", checkedKind(kind)).put("title", cut(title)).put("pkg", pkg).put("name", label(pkg))
                    .put("at", System.currentTimeMillis());
            JSONObject d = known.get(kind + id);
            if (d != null) entry.put("poster", d.optString("poster"));
            synchronized (savedLock) {
                JSONArray next = new JSONArray().put(entry), old = without(readList(WATCHED_FILE), kind, id);
                for (int i = 0; i < old.length() && next.length() < MAX_WATCHED; i++) next.put(old.get(i));
                writeList(WATCHED_FILE, next);
            }
        } catch (IOException | JSONException e) {
            AppLog.w("couldn't note what was opened");
        }
    }

    void forgetWatched() {
        synchronized (savedLock) {
            new File(context.getFilesDir(), WATCHED_FILE).delete();
        }
    }

    String watched() {
        synchronized (savedLock) {
            return readList(WATCHED_FILE).toString();
        }
    }

    // MARK: - Saved

    String saved() {
        synchronized (savedLock) {
            return readList(SAVED_FILE).toString();
        }
    }

    /** Adds a title from the phone. Only the known fields are kept. */
    void save(String json) throws JSONException, IOException {
        JSONObject in = new JSONObject(json);
        String kind = checkedKind(in.optString("kind"));
        int id = in.getInt("id");
        JSONObject clean = new JSONObject().put("id", id).put("kind", kind).put("title", cut(in.optString("title")))
                .put("year", cut(in.optString("year"))).put("poster", cut(in.optString("poster")));
        synchronized (savedLock) {
            JSONArray now = without(readList(SAVED_FILE), kind, id);
            if (now.length() >= MAX_SAVED) throw new IOException("the saved list is full");
            JSONArray next = new JSONArray().put(clean);   // newest first
            for (int i = 0; i < now.length(); i++) next.put(now.get(i));
            writeList(SAVED_FILE, next);
        }
    }

    void unsave(String kind, int id) throws JSONException, IOException {
        synchronized (savedLock) {
            writeList(SAVED_FILE, without(readList(SAVED_FILE), checkedKind(kind), id));
        }
    }

    static String checkedKind(String kind) throws JSONException {
        if (!kind.equals("movie") && !kind.equals("tv")) throw new JSONException("bad kind");
        return kind;
    }

    private static String cut(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static JSONArray without(JSONArray list, String kind, int id) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if (!(o.optInt("id") == id && kind.equals(o.optString("kind")))) out.put(o);
        }
        return out;
    }

    private JSONArray readList(String name) {
        File f = new File(context.getFilesDir(), name);
        if (!f.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONArray(read(in));
        } catch (IOException | JSONException e) {
            AppLog.w(name + " unreadable");
            return new JSONArray();
        }
    }

    private void writeList(String name, JSONArray list) throws IOException {
        File tmp = new File(context.getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(list.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), name))) throw new IOException("couldn't save");
    }

    // MARK: - Network

    private static JSONObject tmdb(String path) throws IOException, JSONException {
        String key = Keys.tmdb();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = TMDB + path + (bearer ? "" : "&api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url.contains("?") ? url : url.replaceFirst("&", "?")).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (c.getResponseCode() != 200) throw new IOException("search service answered " + c.getResponseCode());
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(WATCHMODE + path + "&apiKey="
                + URLEncoder.encode(Keys.watchmode(), "UTF-8")).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("link service answered " + c.getResponseCode());
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
