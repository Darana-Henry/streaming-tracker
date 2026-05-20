package com.streamingtracker.model;

import java.util.List;

public class Title {
    public int id;
    public String name;
    public Integer year;
    public Integer runtime;
    public String director;
    public String topActor;
    public List<String> genres;
    public String originalLanguage;
    public Double imdbRating;
    public Integer imdbVotes;
    public String ageRating;
    public String contentType;   // "movie" or "show"
    public List<String> providers;
}
