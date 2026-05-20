package com.streamingtracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.streamingtracker.model.Title;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JustWatchClient {

    private static final String API_BASE  = "https://apis.justwatch.com";
    private static final String LOCALE    = "en_IN";
    private static final int    PAGE_SIZE = 40;
    private static final MediaType JSON_CT = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Config config;
    private final Gson gson = new Gson();

    public JustWatchClient(OkHttpClient http, Config config) {
        this.http   = http;
        this.config = config;
    }

    // ── Genre cache ────────────────────────────────────────────────────────────

    /** Returns a map of genre ID → display name (e.g. 1 → "Action"). */
    public Map<Integer, String> fetchGenres() throws IOException {
        String url = API_BASE + "/content/genres/locale/" + LOCALE;
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Genres fetch failed: " + resp.code());
            }
            JsonArray arr = JsonParser.parseString(resp.body().string()).getAsJsonArray();
            Map<Integer, String> map = new HashMap<>();
            for (JsonElement el : arr) {
                JsonObject g = el.getAsJsonObject();
                int id = g.get("id").getAsInt();
                // prefer technical_name (e.g. "Action"), fall back to short_name ("act")
                String name = g.has("technical_name") && !g.get("technical_name").isJsonNull()
                        ? g.get("technical_name").getAsString()
                        : g.get("short_name").getAsString();
                map.put(id, name);
            }
            return map;
        }
    }

    // ── Title pagination ───────────────────────────────────────────────────────

    public List<Title> fetchAllTitles(Map<Integer, String> genreMap)
            throws IOException, InterruptedException {

        List<Title> all = new ArrayList<>();
        int page = 1;

        while (true) {
            System.out.printf("  Page %d — fetched %d titles so far…%n", page, all.size());
            JsonObject result = fetchPage(page);

            JsonArray items = result.getAsJsonArray("items");
            if (items == null || items.size() == 0) break;

            for (JsonElement el : items) {
                Title t = parseTitle(el.getAsJsonObject(), genreMap);
                if (t != null) all.add(t);
            }

            int total = result.has("total_results") ? result.get("total_results").getAsInt() : 0;
            if (all.size() >= total || items.size() < PAGE_SIZE) break;

            page++;
            Thread.sleep(400);  // polite delay to avoid rate limiting
        }

        return all;
    }

    private JsonObject fetchPage(int page) throws IOException {
        String url = API_BASE + "/content/titles/" + LOCALE + "/popular";

        JsonObject body = new JsonObject();

        JsonArray types = new JsonArray();
        config.contentTypes.forEach(types::add);
        body.add("content_types", types);

        JsonArray prov = new JsonArray();
        config.providers.forEach(prov::add);
        body.add("providers", prov);

        body.addProperty("release_year_from", config.yearFrom);
        body.addProperty("release_year_until", config.yearTo);
        body.addProperty("page", page);
        body.addProperty("page_size", PAGE_SIZE);
        body.addProperty("sort_by", "original_score");
        body.addProperty("sort_ascending", false);

        JsonArray monetize = new JsonArray();
        monetize.add("flatrate"); monetize.add("free"); monetize.add("ads");
        body.add("monetization_types", monetize);

        // Request only the fields we need to keep payloads small
        JsonArray fields = new JsonArray();
        for (String f : new String[]{
                "id", "title", "object_type", "original_release_year",
                "runtime", "genre_ids", "age_certification", "original_language",
                "scoring", "offers", "credits"}) {
            fields.add(f);
        }
        body.add("fields", fields);

        RequestBody rb = RequestBody.create(gson.toJson(body), JSON_CT);
        Request req = new Request.Builder()
                .url(url)
                .post(rb)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String raw = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("Page " + page + " failed (" + resp.code() + "): " + raw);
            }
            return JsonParser.parseString(raw).getAsJsonObject();
        }
    }

    // ── Item parsing ───────────────────────────────────────────────────────────

    private Title parseTitle(JsonObject item, Map<Integer, String> genreMap) {
        Title t = new Title();

        t.id          = item.get("id").getAsInt();
        t.name        = str(item, "title");
        t.contentType = str(item, "object_type");
        t.year        = intOrNull(item, "original_release_year");
        t.runtime     = intOrNull(item, "runtime");
        t.ageRating   = str(item, "age_certification");
        t.originalLanguage = str(item, "original_language");

        // Genres
        t.genres = new ArrayList<>();
        if (hasArray(item, "genre_ids")) {
            for (JsonElement id : item.getAsJsonArray("genre_ids")) {
                String name = genreMap.get(id.getAsInt());
                if (name != null) t.genres.add(name);
            }
        }

        // IMDB scoring
        if (hasArray(item, "scoring")) {
            for (JsonElement el : item.getAsJsonArray("scoring")) {
                JsonObject s = el.getAsJsonObject();
                String pt = s.has("provider_type") ? s.get("provider_type").getAsString() : "";
                if (pt.equals("imdb:score") && !s.get("value").isJsonNull()) {
                    t.imdbRating = s.get("value").getAsDouble();
                } else if (pt.equals("imdb:votes") && !s.get("value").isJsonNull()) {
                    t.imdbVotes = s.get("value").getAsInt();
                }
            }
        }

        // Providers from offers (deduplicated, limited to configured providers)
        t.providers = new ArrayList<>();
        Set<String> seen = new HashSet<>(config.providers);
        if (hasArray(item, "offers")) {
            for (JsonElement el : item.getAsJsonArray("offers")) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("package_short_name")) continue;
                String p = o.get("package_short_name").getAsString();
                if (seen.remove(p)) t.providers.add(p);  // remove keeps insertion order unique
            }
        }
        // Skip titles not available on any configured provider
        if (t.providers.isEmpty()) return null;

        // Credits — first DIRECTOR and first ACTOR
        if (hasArray(item, "credits")) {
            for (JsonElement el : item.getAsJsonArray("credits")) {
                JsonObject c = el.getAsJsonObject();
                String role = c.has("role") ? c.get("role").getAsString() : "";
                String name = c.has("name") ? c.get("name").getAsString() : "";
                if (name.isEmpty()) continue;
                if ("DIRECTOR".equals(role) && t.director == null)  t.director = name;
                if ("ACTOR".equals(role)    && t.topActor == null)  t.topActor  = name;
                if (t.director != null && t.topActor != null) break;
            }
        }

        return t;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static Integer intOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : null;
    }

    private static boolean hasArray(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray();
    }
}
