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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Replaces /episodes completely with per-show episode lists.
     * Only shows with a non-empty episode list are included.
     */
    public void writeEpisodes(List<Title> titles) throws IOException {
        JsonObject map = new JsonObject();
        for (Title t : titles) {
            if (t.episodes == null || t.episodes.isEmpty()) continue;
            map.add(String.valueOf(t.id), gson.toJsonTree(t.episodes));
        }

        RequestBody body = RequestBody.create(gson.toJson(map), JSON_CT);
        Request req = new Request.Builder()
                .url(dbUrl + "/episodes.json")
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
        System.out.println("Wrote episodes for " + map.size() + " shows to Firebase /episodes");
    }
}
