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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JustWatchClient {

    private static final String GRAPHQL_URL = "https://apis.justwatch.com/graphql";
    private static final String COUNTRY     = "IN";
    private static final String LANGUAGE    = "en";
    private static final int    PAGE_SIZE   = 40;
    private static final MediaType JSON_CT  = MediaType.get("application/json; charset=utf-8");

    private static final String QUERY =
        "query GetPopularTitles(" +
        "  $popularTitlesFilter: TitleFilter" +
        "  $country: Country!" +
        "  $language: Language!" +
        "  $first: Int!" +
        "  $filter: OfferFilter!" +
        "  $offset: Int" +
        ") {" +
        "  popularTitles(" +
        "    country: $country" +
        "    filter: $popularTitlesFilter" +
        "    first: $first" +
        "    sortBy: POPULAR" +
        "    sortRandomSeed: 0" +
        "    offset: $offset" +
        "  ) {" +
        "    totalCount" +
        "    edges {" +
        "      node {" +
        "        objectId" +
        "        objectType" +
        "        content(country: $country, language: $language) {" +
        "          title" +
        "          posterUrl" +
        "          externalIds { imdbId }" +
        "          originalReleaseYear" +
        "          runtime" +
        "          ... on MovieOrShowContent {" +
        "            ageCertification" +
        "          }" +
        "          genres {" +
        "            shortName" +
        "            technicalName" +
        "          }" +
        "          scoring {" +
        "            imdbScore" +
        "            imdbVotes" +
        "          }" +
        "          credits {" +
        "            role" +
        "            name" +
        "          }" +
        "        }" +
        "        offers(country: $country, platform: WEB, filter: $filter) {" +
        "          package {" +
        "            shortName" +
        "          }" +
        "          monetizationType" +
        "        }" +
        "      }" +
        "    }" +
        "  }" +
        "}";

    private final OkHttpClient http;
    private final Config config;
    private final Gson gson = new Gson();

    public JustWatchClient(OkHttpClient http, Config config) {
        this.http   = http;
        this.config = config;
    }

    public List<Title> fetchAllTitles() throws IOException, InterruptedException {
        // Query one provider at a time — JustWatch caps multi-provider queries at ~2000 results
        Map<Integer, Title> merged = new LinkedHashMap<>();
        for (String provider : config.providers) {
            if ("pva".equals(provider)) continue;  // covered by prv via offer normalisation
            System.out.printf("%n  --- Fetching provider: %s ---%n", provider);
            fetchForProvider(provider, merged);
        }
        return new ArrayList<>(merged.values());
    }

    private void fetchForProvider(String provider, Map<Integer, Title> merged)
            throws IOException, InterruptedException {
        int offset     = 0;
        int totalCount = Integer.MAX_VALUE;

        while (offset < totalCount) {
            System.out.printf("  Offset %d — total merged so far: %d%n", offset, merged.size());
            JsonObject data = fetchPage(offset, provider);

            JsonObject popularTitles = data.getAsJsonObject("popularTitles");
            totalCount = popularTitles.get("totalCount").getAsInt();

            JsonArray edges = popularTitles.getAsJsonArray("edges");
            if (edges == null || edges.size() == 0) break;

            for (JsonElement el : edges) {
                JsonObject node = el.getAsJsonObject().getAsJsonObject("node");
                Title t = parseTitle(node);
                if (t == null) continue;
                Title existing = merged.get(t.id);
                if (existing != null) {
                    // title is on multiple providers — merge the providers lists
                    for (String p : t.providers) {
                        if (!existing.providers.contains(p)) existing.providers.add(p);
                    }
                } else {
                    merged.put(t.id, t);
                }
            }

            offset += edges.size();
            Thread.sleep(400);
        }
    }

    private JsonObject fetchPage(int offset, String provider) throws IOException {
        JsonObject variables = new JsonObject();
        variables.addProperty("country",  COUNTRY);
        variables.addProperty("language", LANGUAGE);
        variables.addProperty("first",    PAGE_SIZE);
        variables.addProperty("offset",   offset);

        JsonObject titleFilter = new JsonObject();
        JsonArray packages = new JsonArray();
        packages.add(provider);
        titleFilter.add("packages", packages);
        titleFilter.addProperty("includeTitlesWithoutUrl", true);
        JsonArray objectTypes = new JsonArray();
        for (String ct : config.contentTypes) {
            objectTypes.add("movie".equals(ct) ? "MOVIE" : "SHOW");
        }
        titleFilter.add("objectTypes", objectTypes);
        JsonObject releaseYear = new JsonObject();
        releaseYear.addProperty("min", config.yearFrom);
        releaseYear.addProperty("max", config.yearTo);
        titleFilter.add("releaseYear", releaseYear);
        variables.add("popularTitlesFilter", titleFilter);

        JsonObject offerFilter = new JsonObject();
        offerFilter.addProperty("bestOnly", false);
        variables.add("filter", offerFilter);

        JsonObject body = new JsonObject();
        body.addProperty("operationName", "GetPopularTitles");
        body.addProperty("query", QUERY);
        body.add("variables", variables);

        RequestBody rb = RequestBody.create(gson.toJson(body), JSON_CT);
        Request req = new Request.Builder()
                .url(GRAPHQL_URL)
                .post(rb)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String raw = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("GraphQL request failed (" + resp.code() + "): " + raw);
            }
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            if (json.has("errors")) {
                throw new IOException("GraphQL errors: " + json.get("errors"));
            }
            return json.getAsJsonObject("data");
        }
    }

    private Title parseTitle(JsonObject node) {
        Title t = new Title();

        t.id          = node.get("objectId").getAsInt();
        t.contentType = "MOVIE".equals(str(node, "objectType")) ? "movie" : "show";



        JsonObject content = node.has("content") && !node.get("content").isJsonNull()
                ? node.getAsJsonObject("content") : null;
        if (content != null) {
            t.name             = str(content, "title");
            String rawPoster   = str(content, "posterUrl");
            if (rawPoster != null) {
                t.posterUrl = "https://images.justwatch.com" +
                    rawPoster.replace("{profile}", "s332").replace("{format}", "webp");
            }
            if (content.has("externalIds") && !content.get("externalIds").isJsonNull()) {
                t.imdbId = str(content.getAsJsonObject("externalIds"), "imdbId");
            }
            t.year             = intOrNull(content, "originalReleaseYear");
            t.runtime          = intOrNull(content, "runtime");
            t.ageRating        = str(content, "ageCertification");

            t.genres = new ArrayList<>();
            if (hasArray(content, "genres")) {
                for (JsonElement el : content.getAsJsonArray("genres")) {
                    JsonObject g    = el.getAsJsonObject();
                    String techName = str(g, "technicalName");
                    String name     = techName != null ? techName : str(g, "shortName");
                    if (name != null) t.genres.add(name);
                }
            }

            if (hasArray(content, "credits")) {
                List<String> actors = new ArrayList<>();
                for (JsonElement el : content.getAsJsonArray("credits")) {
                    JsonObject c    = el.getAsJsonObject();
                    String role     = str(c, "role");
                    String name     = str(c, "name");
                    if (name == null || name.isEmpty()) continue;
                    if ("DIRECTOR".equals(role) && t.director == null) t.director = name;
                    if ("ACTOR".equals(role) && actors.size() < 3) actors.add(name);
                    if (t.director != null && actors.size() >= 3) break;
                }
                t.actors = actors;
            }

            if (content.has("scoring") && !content.get("scoring").isJsonNull()) {
                JsonObject scoring = content.getAsJsonObject("scoring");
                if (scoring.has("imdbScore") && !scoring.get("imdbScore").isJsonNull())
                    t.imdbRating = scoring.get("imdbScore").getAsDouble();
                if (scoring.has("imdbVotes") && !scoring.get("imdbVotes").isJsonNull())
                    t.imdbVotes = scoring.get("imdbVotes").getAsInt();
            }
        }

        t.providers = new ArrayList<>();
        Set<String> seen             = new HashSet<>();
        Set<String> allowedProviders = new HashSet<>(config.providers);
        Set<String> streamingTypes   = Set.of("FLATRATE", "FREE", "ADS");
        if (hasArray(node, "offers")) {
            for (JsonElement el : node.getAsJsonArray("offers")) {
                JsonObject offer = el.getAsJsonObject();
                if (!streamingTypes.contains(str(offer, "monetizationType"))) continue;
                if (!offer.has("package") || offer.get("package").isJsonNull()) continue;
                String shortName = str(offer.getAsJsonObject("package"), "shortName");
                if (shortName == null) continue;
                if ("pva".equals(shortName)) shortName = "prv";  // Prime Video (ad-tier) → Prime
                if (allowedProviders.contains(shortName) && seen.add(shortName))
                    t.providers.add(shortName);
            }
        }

        if (t.providers.isEmpty()) return null;
        return t;
    }

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
