# Streaming Tracker

Browse Indian streaming content (Hotstar, Prime Video, Lionsgate Play), track what you've seen, and keep the data fresh with a scheduled Java scraper — all backed by Firebase.

## Architecture

| Component | Tech |
|-----------|------|
| **Scraper** | Java 11, Gradle, OkHttp 4, Gson |
| **Database** | Firebase Realtime Database (REST API) |
| **Frontend** | Single-file HTML/CSS/JS, Firebase JS SDK CDN |
| **Hosting** | GitHub Pages (`/docs` folder) |

---

## 1 — Initial Firebase setup

### 1.1 Service account

1. Open [Firebase Console](https://console.firebase.google.com) → Project settings → **Service accounts**.
2. Click **Generate new private key** → download the JSON.
3. Save it as `firebase-service-account.json` in the project root.  
   It is gitignored — never commit it.

### 1.2 Database rules

In Firebase Console → Realtime Database → Rules, paste:

```json
{
  "rules": {
    "titles":    { ".read": true,          ".write": false         },
    "seen":      { ".read": "auth != null", ".write": "auth != null" },
    "watchlist": { ".read": "auth != null", ".write": "auth != null" },
    "dismissed": { ".read": "auth != null", ".write": "auth != null" },
    "tracking":  { ".read": "auth != null", ".write": "auth != null" }
  }
}
```

### 1.3 Enable Google Auth

Firebase Console → Authentication → Sign-in method → Enable **Google**.

---

## 2 — Frontend setup

1. Open `docs/index.html` and replace `YOUR_API_KEY` with your Firebase Web API key  
   (Firebase Console → Project settings → Your apps → Web API key).
2. Commit and push.

---

## 3 — GitHub Pages

1. Push the repository to GitHub.
2. GitHub repository → **Settings → Pages**.
3. Source: **Deploy from a branch** → branch `main`, folder `/docs`.
4. Your frontend will be live at `https://<username>.github.io/<repo>/`.

> **Authorised domain**: add your GitHub Pages URL in  
> Firebase Console → Authentication → Settings → Authorised domains.

---

## 4 — Running the scraper

### Prerequisites

- Java 11 or newer (`java -version`)
- `firebase-service-account.json` in the project root

### First-time Gradle wrapper setup

If you don't have `./gradlew` yet (e.g. fresh clone), either:

```sh
# Option A — you have Gradle installed locally
gradle wrapper

# Option B — run directly without the wrapper
gradle run
```

### Default run (all providers, all years)

```sh
./gradlew run
```

### With options

```sh
./gradlew run --args="--providers=jhs,prv --year-from=2015 --year-to=2024 --content-type=movies"
```

| Flag | Values | Default |
|------|--------|---------|
| `--providers` | `jhs`, `prv`, `lgp`, `snl`, `snx`, `zee`, `vim` (comma-separated) | all seven |
| `--year-from` | any year integer | `1900` |
| `--year-to` | any year integer | current year |
| `--content-type` | `movies` · `shows` · `both` | `both` |

### Build a runnable jar

```sh
./gradlew jar
java -jar build/libs/streaming-tracker-1.0.jar --providers=jhs
```

### What each scraper run does

1. Fetches the JustWatch genre list for `en_IN` (cached in memory, not in Firebase).
2. Paginates through JustWatch results (40 titles/page, 400 ms between pages).
3. Overwrites **`/titles`** completely in Firebase.
4. **Never touches `/seen`**.

---

## 5 — Frontend features

### Tabs

| Tab | Contents |
|-----|---------|
| **Discover** | Filtered title grid + Latest Releases carousel |
| **My Library** | Watchlist, custom Tracking list, Top Picks carousel |
| **Analytics** | Stats on seen titles — watch time, avg rating, breakdown by platform / genre / decade / rating bracket |

### Filters (sidebar)

- **Provider icon toggles** — JioHotstar, Prime Video, Lionsgate Play, SonyLIV, Sun NXT, ZEE5, Voot.
- **Content type** — Movies / Shows / Both segmented button.
- **Year range** — min/max number inputs, bounded to data range.
- **Genre multi-select** — live search, dynamically built from data.
- **Language dropdown** — dynamically built; ISO codes resolved to full names.
- **Hide seen / Hide watchlisted / Hide dismissed** — toggle switches.

### Discover — title cards

Each card shows poster (or coloured fallback), title, year, rating, logline, genre chips, and provider badges.  Actions: **Mark as seen / Undo**, **Add to watchlist**, **Dismiss** (Not Interested).

### Latest Releases carousel

Split-panel carousel (backdrop image left, metadata right) showing recently added titles, auto-advancing with prev/next arrows and dot indicators.

### My Library

- **Watchlist** — save titles to watch later; filterable by content type and provider.
- **Tracking** — add arbitrary titles (not in the database) with poster, year, and content type; stored under `/tracking`.
- **Top Picks carousel** — curated high-rated titles (≥ 7.5 IMDB, ≥ 3 000 votes) not yet seen, watchlisted, or dismissed.

### Analytics dashboard

Headline stats (titles watched, hours, avg rating, top platform) plus bar charts for: by platform, by genre, by IMDB rating bracket, by decade.

### Auth

Google Sign-In required to write `/seen`, `/watchlist`, `/tracking`, `/dismissed`.  Reading `/titles` is always public.

---

## 6 — Data model

### `/titles/{id}` — written by scraper

| Field | Type | Notes |
|-------|------|-------|
| `id` | int | JustWatch title ID |
| `imdbId` | string? | IMDb ID (e.g. `tt1234567`) |
| `name` | string | |
| `year` | int? | original release year |
| `runtime` | int? | minutes |
| `director` | string? | first credited director |
| `actors` | string[] | credited cast members |
| `genres` | string[] | resolved from JustWatch genre IDs |
| `originalLanguage` | string? | ISO 639-1 code |
| `audioLanguages` | string[] | language codes available across streaming offers |
| `imdbRating` | double? | |
| `imdbVotes` | int? | |
| `ageRating` | string? | e.g. `U`, `UA`, `A` |
| `shortDescription` | string? | logline / plot summary |
| `releaseDate` | string? | ISO `YYYY-MM-DD`, earliest known release |
| `streamingDate` | string? | ISO `YYYY-MM-DD`, most recent `availableFromTime` across providers |
| `firstSeen` | string? | ISO `YYYY-MM-DD`, date title first appeared in a scraper run |
| `posterUrl` | string? | JustWatch poster image URL |
| `backdropUrl` | string? | JustWatch backdrop/still image URL |
| `contentType` | string | `"movie"` or `"show"` |
| `providers` | string[] | e.g. `["jhs","prv"]` — see provider codes below |

**Provider codes:** `jhs` JioHotstar · `prv` Prime Video · `lgp` Lionsgate Play · `snl` SonyLIV · `snx` Sun NXT · `zee` ZEE5 · `vim` Voot

### `/seen/{id}` — written by frontend

Value is always `true`.  Key is the JustWatch title ID as a string.

### `/watchlist/{id}` — written by frontend

Value is always `true`.  Titles the user wants to watch later.

### `/dismissed/{id}` — written by frontend

Value is always `true`.  Titles the user has marked "Not Interested".

### `/tracking/{id}` — written by frontend

Custom titles added manually by the user (not necessarily in `/titles`).

| Field | Type | Notes |
|-------|------|-------|
| `name` | string | |
| `year` | int? | |
| `posterUrl` | string? | |
| `contentType` | string | `"movie"` or `"show"` |
| `addedAt` | int | Unix timestamp (ms) |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `Token exchange failed (400)` | Check `firebase-service-account.json` is valid and matches your project. |
| `Firebase write failed (401)` | Ensure the service account has the **Firebase Realtime Database Admin** role in IAM. |
| `Page 1 failed (429)` | JustWatch rate-limited you. Increase the `Thread.sleep` delay in `JustWatchClient.java`. |
| Sign-in popup blocked | Ensure your Pages URL is in Firebase's authorised domains list. |
| `YOUR_API_KEY` still in the page | Replace it in `docs/index.html` and redeploy. |
