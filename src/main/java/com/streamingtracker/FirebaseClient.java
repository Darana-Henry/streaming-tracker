package com.streamingtracker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.streamingtracker.model.Title;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.gson.JsonNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FirebaseClient {

    private static final MediaType JSON_CT = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String accessToken;
    private final String dbUrl;
    private final Gson gson = new Gson();

    public FirebaseClient(OkHttpClient http, String accessToken, String dbUrl) {
        this.http        = http;
        this.accessToken = accessToken;
        this.dbUrl       = dbUrl;
    }

    /** Returns a map of id → firstSeen for all titles currently in Firebase. */
    public Map<String, String> fetchExistingFirstSeen() throws IOException {
        Request req = new Request.Builder()
                .url(dbUrl + "/titles.json?shallow=false")
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        Map<String, String> result = new HashMap<>();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return result;
            String raw = resp.body().string();
            if (raw == null || raw.equals("null")) return result;
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonObject()) return result;
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                JsonElement val = entry.getValue();
                if (val.isJsonObject()) {
                    JsonElement fs = val.getAsJsonObject().get("firstSeen");
                    if (fs != null && !fs.isJsonNull()) {
                        result.put(entry.getKey(), fs.getAsString());
                    }
                }
            }
        }
        System.out.println("Fetched firstSeen for " + result.size() + " existing titles");
        return result;
    }

    /**
     * Replaces /titles completely with the given list.
     * /seen is never touched.
     */
    public void writeTitles(List<Title> titles) throws IOException {
        JsonObject map = new JsonObject();
        for (Title t : titles) {
            map.add(String.valueOf(t.id), gson.toJsonTree(t));
        }

        RequestBody body = RequestBody.create(gson.toJson(map), JSON_CT);
        Request req = new Request.Builder()
                .url(dbUrl + "/titles.json")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .put(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Firebase write failed ("
                        + resp.code() + "): " + resp.body().string());
            }
        }
        System.out.println("Wrote " + titles.size() + " titles to Firebase /titles");
    }

    // Stay well under Firebase's single-request write size limit.
    private static final int MAX_EPISODES_BATCH_BYTES = 1_000_000;

    /**
     * Replaces /episodes completely with per-show episode lists.
     * Only shows with a non-empty episode list are included. Writing everything in one PUT can
     * exceed Firebase's single-request size limit once enough shows are tracked, so removals and
     * additions are both sent as several smaller PATCH requests instead of one full-tree
     * DELETE + PUT (a single DELETE of the whole node hits the same size limit once /episodes
     * has accumulated enough shows across runs).
     */
    public void writeEpisodes(List<Title> titles) throws IOException {
        Set<String> existingKeys = fetchExistingEpisodeKeys();
        Set<String> newKeys      = new HashSet<>();

        JsonObject batch     = new JsonObject();
        int        batchBytes = 2; // "{}"
        int        written    = 0;
        for (Title t : titles) {
            if (t.episodes == null || t.episodes.isEmpty()) continue;
            String key = String.valueOf(t.id);
            newKeys.add(key);
            JsonElement value = gson.toJsonTree(t.episodes);
            int entryBytes = key.length() + gson.toJson(value).length();
            if (batch.size() > 0 && batchBytes + entryBytes > MAX_EPISODES_BATCH_BYTES) {
                patchEpisodes(batch);
                written += batch.size();
                batch = new JsonObject();
                batchBytes = 2;
            }
            batch.add(key, value);
            batchBytes += entryBytes;
        }

        // Shows that no longer have episode data (e.g. dropped from JustWatch) get nulled out,
        // batched the same way so a single request never touches more than the size limit allows.
        int removed = 0;
        for (String key : existingKeys) {
            if (newKeys.contains(key)) continue;
            int entryBytes = key.length() + 4; // "null"
            if (batch.size() > 0 && batchBytes + entryBytes > MAX_EPISODES_BATCH_BYTES) {
                patchEpisodes(batch);
                written += batch.size();
                batch = new JsonObject();
                batchBytes = 2;
            }
            batch.add(key, JsonNull.INSTANCE);
            batchBytes += entryBytes;
            removed++;
        }

        if (batch.size() > 0) {
            patchEpisodes(batch);
            written += batch.size();
        }
        System.out.println("Wrote episodes for " + newKeys.size() + " shows to Firebase /episodes ("
                + removed + " removed)");
    }

    private Set<String> fetchExistingEpisodeKeys() throws IOException {
        Request req = new Request.Builder()
                .url(dbUrl + "/episodes.json?shallow=true")
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        Set<String> keys = new HashSet<>();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Firebase read failed ("
                        + resp.code() + "): " + resp.body().string());
            }
            String raw = resp.body().string();
            if (raw == null || raw.equals("null")) return keys;
            JsonElement root = JsonParser.parseString(raw);
            if (root.isJsonObject()) keys.addAll(root.getAsJsonObject().keySet());
        }
        return keys;
    }

    private void patchEpisodes(JsonObject batch) throws IOException {
        RequestBody body = RequestBody.create(gson.toJson(batch), JSON_CT);
        Request req = new Request.Builder()
                .url(dbUrl + "/episodes.json")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .patch(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Firebase write failed ("
                        + resp.code() + "): " + resp.body().string());
            }
        }
    }
}
