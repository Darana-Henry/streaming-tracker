package com.streamingtracker;

import com.streamingtracker.auth.ServiceAccountAuth;
import com.streamingtracker.model.Title;
import okhttp3.OkHttpClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        // ── Step 1: scrape titles ─────────────────────────────────────────────
        System.out.println("\n=== Scraping JustWatch ===");
        JustWatchClient jw = new JustWatchClient(http, config);
        List<Title> titles = jw.fetchAllTitles();
        System.out.println("Total titles collected: " + titles.size());

        if (titles.isEmpty()) {
            System.out.println("Nothing to write — exiting.");
            return;
        }

        // ── Step 2: authenticate with Firebase ────────────────────────────────
        System.out.println("\n=== Authenticating with Firebase ===");
        ServiceAccountAuth auth = new ServiceAccountAuth("firebase-service-account.json", http);
        String token = auth.getAccessToken();
        System.out.println("Access token obtained");

        // ── Step 3: overwrite /titles ─────────────────────────────────────────
        System.out.println("\n=== Writing to Firebase ===");
        FirebaseClient fb = new FirebaseClient(http, token, config.databaseUrl);
        fb.writeTitles(titles);

        System.out.println("\nDone.");
    }
}
