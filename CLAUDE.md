# Streaming Tracker — Claude context

## Project structure

```
streaming-tracker/
├── build.gradle                   # Gradle build — OkHttp + Gson only, no JWT lib
├── settings.gradle
├── gradle/wrapper/                # Gradle wrapper properties (run `gradle wrapper` to generate jar)
├── firebase-service-account.json  # GITIGNORED — obtain from Firebase Console > Service accounts
├── src/main/java/com/streamingtracker/
│   ├── Main.java                  # Entry point — orchestrates the four steps
│   ├── Config.java                # CLI argument parser
│   ├── JustWatchClient.java       # JustWatch REST API scraper
│   ├── FirebaseClient.java        # Firebase REST PUT /titles
│   ├── auth/
│   │   └── ServiceAccountAuth.java  # JWT → OAuth2 access token (no external JWT lib)
│   └── model/
│       └── Title.java             # Data model serialised to Firebase
└── docs/
    └── index.html                 # GitHub Pages frontend (plain HTML/CSS/JS + Firebase SDK CDN)
```

## Running the scraper

### Prerequisites

- Java 11+
- The `firebase-service-account.json` in the project root (never commit)
- Internet access to `apis.justwatch.com` and Firebase

### Generate Gradle wrapper (first time only)

```sh
gradle wrapper          # requires local Gradle installation
# or if you already have ./gradlew:
./gradlew wrapper
```

### Run with defaults (all providers, all years, movies + shows)

```sh
./gradlew run
# or after building the fat jar:
./gradlew jar
java -jar build/libs/streaming-tracker-1.0.jar
```

### Run with options

```sh
./gradlew run --args="--providers=jhs,prv --year-from=2015 --year-to=2024 --content-type=movies"
```

| Flag | Values | Default |
|---|---|---|
| `--providers` | comma-separated: `jhs` `prv` `lgp` `snl` `snx` `zee` `atp` `nfx` | all eight |
| `--year-from` | integer year | `1900` |
| `--year-to` | integer year | current year |
| `--content-type` | `movies` \| `shows` \| `both` | `both` |

### What the scraper does

1. Fetches the JustWatch genre list for `en_IN` and caches it in memory.
2. Paginates through `/content/titles/en_IN/popular` (up to 40 per page, 400 ms delay between pages; the page size is halved and the page retried whenever a show's nested season data pushes a query over JustWatch's GraphQL complexity limit).
3. Builds a flat map of `{ titleId: titleObject }`.
4. Authenticates with Firebase using a short-lived OAuth2 token derived from the service account private key (no external JWT library — pure JDK crypto).
5. Overwrites `/titles` in Firebase with a single PUT.  `/seen` is never touched.

## Firebase data layout

```
/titles/{justwatch_id}:          ← written by scraper
  id, imdbId, name, year, runtime, director,
  actors[], genres[], originalLanguage, audioLanguages[],
  imdbRating, imdbVotes, ageRating, shortDescription,
  releaseDate, streamingDate, firstSeen,
  posterUrl, backdropUrl,
  contentType, providers[],
  numberOfSeasons, totalSeasons   ← shows only. numberOfSeasons = seasons actually out (any JustWatch offer in IN, or a past release date; everything up to the last such season counts). totalSeasons = every season JustWatch lists, incl. renewed-but-unreleased ones. Episode air dates are NOT used — JustWatch often leaves them blank for fully released recent seasons.

/users/{uid}/seen/{justwatch_id}: true          ← written by frontend only
/users/{uid}/watchlist/{justwatch_id}: { order }  ← written by frontend only; order is a sortable float used by "My order"
/users/{uid}/dismissed/{justwatch_id}: true     ← written by frontend only
/users/{uid}/watchedSeasons/{justwatch_id}: []  ← written by frontend only. May include announced seasons (> numberOfSeasons) the user ticked by hand. Pending tab = some released season unwatched; Coming Soon = all released seasons watched but an announced one isn't; otherwise the show appears in neither.
/users/{uid}/tracking/{id}: { name, year, posterUrl, contentType, addedAt, fullPath, backdropUrl, genres, director, actors, shortDescription, runtime, ageRating, imdbRating, imdbVotes }  ← written by frontend only. fullPath is the JustWatch URL path (e.g. "/in/movie/some-title"), kept so the entry can be reliably re-fetched later; the rest are optional JustWatch metadata fetched at add-time so "Coming to Streaming" cards can look like Discover cards even pre-release. Entries tracked before this metadata existed are missing these fields and get opportunistically backfilled client-side (best-effort slug guess + name/year sanity check) the next time the tracking tab renders.
/users/{uid}/customTitles/{id}: { id, name, year, contentType, posterUrl, addedAt, source }  ← written by frontend only; denormalized copy for titles marked seen that aren't in /titles (e.g. watched in a theater, or on an untracked service). id is either the JustWatch objectId (if resolved via URL) or "custom_<timestamp>"
/users/{uid}/watchedDates/{id}: "YYYY-MM-DD" | "unknown"    ← written by frontend only; local-date a title was watched, powers the Movie Log tab (reverse-chronological list + calendar heatmap). Set to today when a title is marked seen (editable after the fact); removed when a title is un-marked seen. Titles marked seen before this feature existed have no entry until backfilled via the Movie Log tab's prompt, and are excluded from the Movie Log view until then. The sentinel value "unknown" means "I know I watched it, but not when" — set via the Movie Log tab's backfill prompt or by editing an entry's date; these titles appear in the Movie Log list under a "Date unknown" group below the dated entries, but are excluded from the calendar heatmap. Movie Log only ever shows contentType "movie" entries — shows are written here too when marked seen, but are filtered out of every Movie Log view (list, backfill prompt, calendar); their watch history lives in watchedSeasons / the Pending tab instead.
```

Provider codes: `jhs` JioHotstar, `prv` Prime Video, `lgp` Lionsgate Play, `snl` SonyLIV, `snx` Sun NXT, `zee` ZEE5, `atp` Apple TV, `nfx` Netflix

## GitHub Pages deployment

1. Push the repo to GitHub.
2. In **Settings → Pages**, set source to `main` branch, `/docs` folder.
3. Add your Firebase `apiKey` in `docs/index.html` (search for `YOUR_API_KEY`).
4. Configure Firebase Database Rules (see README for recommended rules).

## Firebase security rules (recommended)

```json
{
  "rules": {
    "titles": { ".read": true, ".write": false },
    "users": {
      "$uid": {
        ".read":  "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    }
  }
}
```

## Key design decisions

- **No browser automation**: JustWatch's `/content/titles/{locale}/popular` REST endpoint accepts plain JSON POST — no Selenium needed.
- **JWT without a library**: The service account auth uses only `java.security.Signature` (SHA256withRSA) + `Base64.getUrlEncoder()` — no JJWT or Bouncy Castle dependency.
- **PUT not PATCH for /titles**: Each scraper run is a full replacement, keeping the data consistent with the current JustWatch state.
- **Seen IDs survive scraper runs**: `/seen` keys are JustWatch IDs; a title disappearing from JustWatch doesn't lose your seen record.
