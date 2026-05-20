package com.streamingtracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.streamingtracker.model.Title;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;

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
}
