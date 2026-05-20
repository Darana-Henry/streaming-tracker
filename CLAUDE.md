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
./gradlew run --args="--providers=hst,prv --year-from=2015 --year-to=2024 --content-type=movies"
```

| Flag | Values | Default |
|---|---|---|
| `--providers` | comma-separated: `hst` `prv` `lgy` | `hst,prv,lgy` |
| `--year-from` | integer year | `1900` |
| `--year-to` | integer year | current year |
| `--content-type` | `movies` \| `shows` \| `both` | `both` |

### What the scraper does

1. Fetches the JustWatch genre list for `en_IN` and caches it in memory.
2. Paginates through `/content/titles/en_IN/popular` (40 per page, 400 ms delay between pages).
3. Builds a flat map of `{ titleId: titleObject }`.
4. Authenticates with Firebase using a short-lived OAuth2 token derived from the service account private key (no external JWT library — pure JDK crypto).
5. Overwrites `/titles` in Firebase with a single PUT.  `/seen` is never touched.

## Firebase data layout

```
/titles/{justwatch_id}:
  id, name, year, runtime, director, topActor,
  genres[], originalLanguage, imdbRating, imdbVotes,
  ageRating, contentType, providers[]

/seen/{justwatch_id}: true   ← written only by the frontend, never by scraper
```

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
    "seen":   {
      ".read":  "auth != null",
      ".write": "auth != null"
    }
  }
}
```

## Key design decisions

- **No browser automation**: JustWatch's `/content/titles/{locale}/popular` REST endpoint accepts plain JSON POST — no Selenium needed.
- **JWT without a library**: The service account auth uses only `java.security.Signature` (SHA256withRSA) + `Base64.getUrlEncoder()` — no JJWT or Bouncy Castle dependency.
- **PUT not PATCH for /titles**: Each scraper run is a full replacement, keeping the data consistent with the current JustWatch state.
- **Seen IDs survive scraper runs**: `/seen` keys are JustWatch IDs; a title disappearing from JustWatch doesn't lose your seen record.
