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
    "titles": {
      ".read":  true,
      ".write": false
    },
    "seen": {
      ".read":  "auth != null",
      ".write": "auth != null"
    }
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
./gradlew run --args="--providers=hst,prv --year-from=2015 --year-to=2024 --content-type=movies"
```

| Flag | Values | Default |
|------|--------|---------|
| `--providers` | `hst`, `prv`, `lgy` (comma-separated) | all three |
| `--year-from` | any year integer | `1900` |
| `--year-to` | any year integer | current year |
| `--content-type` | `movies` · `shows` · `both` | `both` |

### Build a runnable jar

```sh
./gradlew jar
java -jar build/libs/streaming-tracker-1.0.jar --providers=hst
```

### What each scraper run does

1. Fetches the JustWatch genre list for `en_IN` (cached in memory, not in Firebase).
2. Paginates through JustWatch results (40 titles/page, 400 ms between pages).
3. Overwrites **`/titles`** completely in Firebase.
4. **Never touches `/seen`**.

---

## 5 — Frontend features

- **Provider filter** — Hotstar, Prime Video, Lionsgate Play checkboxes.
- **Year range** — dual-thumb slider, built from actual data range.
- **Content type** — Movies / Shows / Both toggle.
- **Genre multi-select** — dynamically built from data.
- **Language dropdown** — dynamically built from data, common language codes resolved to full names.
- **Seen tracking** — "Mark as seen" / "Undo seen" on each card, written to `/seen` in Firebase and persists across devices.
- **Hide seen** — toggle to hide all seen titles from the grid.
- **Google sign-in** — only authenticated users can write to `/seen`; reading titles is always public.

---

## 6 — Data model

### `/titles/{id}`

| Field | Type | Notes |
|-------|------|-------|
| `id` | int | JustWatch title ID |
| `name` | string | |
| `year` | int? | original release year |
| `runtime` | int? | minutes |
| `director` | string? | first credited director |
| `topActor` | string? | first credited actor |
| `genres` | string[] | resolved from JustWatch genre IDs |
| `originalLanguage` | string? | ISO 639-1 code |
| `imdbRating` | double? | |
| `imdbVotes` | int? | |
| `ageRating` | string? | e.g. `U`, `UA`, `A` |
| `contentType` | string | `"movie"` or `"show"` |
| `providers` | string[] | e.g. `["hst","prv"]` |

### `/seen/{id}`

Value is always `true`.  Key is the JustWatch title ID as a string.  
Written only by the frontend; never by the scraper.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `Token exchange failed (400)` | Check `firebase-service-account.json` is valid and matches your project. |
| `Firebase write failed (401)` | Ensure the service account has the **Firebase Realtime Database Admin** role in IAM. |
| `Page 1 failed (429)` | JustWatch rate-limited you. Increase the `Thread.sleep` delay in `JustWatchClient.java`. |
| Sign-in popup blocked | Ensure your Pages URL is in Firebase's authorised domains list. |
| `YOUR_API_KEY` still in the page | Replace it in `docs/index.html` and redeploy. |
