package com.streamingtracker.model;

public class Episode {
    public String id;              // JustWatch episode id, e.g. "tse10522860"
    public int seasonNumber;
    public int episodeNumber;
    public String title;
    public String airDate;         // ISO "YYYY-MM-DD", from originalReleaseDate, may be null
    public Integer runtime;
    public String shortDescription;
}
