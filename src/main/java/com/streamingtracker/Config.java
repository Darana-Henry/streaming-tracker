package com.streamingtracker;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class Config {

    public final List<String> providers;
    public final int yearFrom;
    public final int yearTo;
    public final List<String> contentTypes;  // "movie", "show", or both
    public final String databaseUrl;

    private Config(List<String> providers, int yearFrom, int yearTo, List<String> contentTypes, String databaseUrl) {
        this.providers = providers;
        this.yearFrom = yearFrom;
        this.yearTo = yearTo;
        this.contentTypes = contentTypes;
        this.databaseUrl = databaseUrl;
    }

    public static Config parse(String[] args) {
        List<String> providers = Arrays.asList("hst", "prv", "lgy");
        int yearFrom = 1900;
        int yearTo = Calendar.getInstance().get(Calendar.YEAR);
        List<String> contentTypes = Arrays.asList("movie", "show");
        String databaseUrl = System.getenv("FIREBASE_DATABASE_URL");

        for (String arg : args) {
            if (arg.startsWith("--providers=")) {
                providers = Arrays.asList(arg.substring("--providers=".length()).split(","));
            } else if (arg.startsWith("--year-from=")) {
                yearFrom = Integer.parseInt(arg.substring("--year-from=".length()).trim());
            } else if (arg.startsWith("--year-to=")) {
                yearTo = Integer.parseInt(arg.substring("--year-to=".length()).trim());
            } else if (arg.startsWith("--content-type=")) {
                String ct = arg.substring("--content-type=".length()).trim();
                switch (ct) {
                    case "movies": contentTypes = Arrays.asList("movie"); break;
                    case "shows":  contentTypes = Arrays.asList("show");  break;
                    default:       contentTypes = Arrays.asList("movie", "show");
                }
            } else if (arg.startsWith("--database-url=")) {
                databaseUrl = arg.substring("--database-url=".length()).trim();
            }
        }

        if (databaseUrl == null || databaseUrl.isEmpty()) {
            throw new IllegalArgumentException(
                "Firebase database URL required: pass --database-url=<url> or set FIREBASE_DATABASE_URL");
        }

        System.out.printf("Config: providers=%s  years=%d-%d  types=%s%n",
                providers, yearFrom, yearTo, contentTypes);
        return new Config(providers, yearFrom, yearTo, contentTypes, databaseUrl);
    }
}
