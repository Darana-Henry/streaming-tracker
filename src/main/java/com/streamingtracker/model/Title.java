package com.streamingtracker.model;

import java.util.List;

public class Title {
    public int id;
    public String imdbId;
    public String posterUrl;
    public String name;
    public Integer year;
    public Integer runtime;
    public String director;
    public List<String> actors;
    public List<String> genres;
    public String originalLanguage;
    public Double imdbRating;
    public Integer imdbVotes;
    public String ageRating;
    public String shortDescription;
    public String releaseDate;    // ISO "YYYY-MM-DD", earliest known release, may be null
    public String streamingDate;  // ISO "YYYY-MM-DD", most recent availableFromTime across providers, may be null
    public String firstSeen;      // ISO "YYYY-MM-DD", date title first appeared in a scraper run
    public String contentType;          // "movie" or "show"
    public List<String> providers;
    public List<String> audioLanguages; // language codes available across streaming offers
}
