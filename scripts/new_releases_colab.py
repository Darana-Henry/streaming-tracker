# ═══════════════════════════════════════════════════════════════════════════════
#  New Releases → CSV for Streaming Tracker
#
#  Run this in Google Colab (its US servers can reach the sources, which are
#  blocked on Indian ISPs). Then in the app: Discover → New Releases → Import CSV.
#  Re-importing the same year updates titles in place — nothing is duplicated.
#
#  Data per movie:
#    • title, year, genres, language, summary, poster, download/like counts
#    • IMDb rating + vote count      ← IMDb's free ratings file (no key)
#    • budget + worldwide box office ← TMDB if TMDB_API_KEY is set, else Wikidata
#      (Wikidata needs no key but only covers ~5% of films, mostly big ones)
# ═══════════════════════════════════════════════════════════════════════════════

import io
import time
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
import pandas as pd
from IPython.display import display

# ── Settings ───────────────────────────────────────────────────────────────────
raw  = input(f"Year to fetch (Enter = {datetime.now().year}): ").strip()
YEAR = int(raw) if raw else datetime.now().year

FETCH_POPULARITY = True   # per-movie download/like counts (one request per movie)
FETCH_MONEY      = True   # budget + box office

# Optional. Paste a TMDB key here, OR (better) add it in Colab → 🔑 Secrets as
# TMDB_API_KEY and allow notebook access. Either the v3 "API Key" or the long
# v4 "Read Access Token" works. Leave blank to use Wikidata only.
TMDB_API_KEY = ""

try:
    from google.colab import userdata
    TMDB_API_KEY = TMDB_API_KEY or (userdata.get("TMDB_API_KEY") or "")
except Exception:
    pass

UA = {"User-Agent": "streaming-tracker-notebook/1.0"}

# ── Release list (YTS API) ─────────────────────────────────────────────────────
API       = "https://yts.bz/api/v2"
PAGE_SIZE = 50

def api(path, **params):
    err = "bad status"
    for attempt in range(3):
        try:
            r = requests.get(f"{API}/{path}", params=params, timeout=20, headers=UA)
            r.raise_for_status()
            j = r.json()
            if j.get("status") == "ok":
                return j.get("data") or {}
        except (requests.RequestException, ValueError) as e:
            err = e
        time.sleep(1 + attempt)
    raise RuntimeError(f"{path} failed after 3 tries: {err}")

def list_page(page):
    d = api("list_movies.json", limit=PAGE_SIZE, page=page, sort_by="year", order_by="desc")
    return d.get("movies") or [], d.get("movie_count") or 0

def find_start_page(year):
    """The list is sorted by year (desc): binary-search the first page that reaches `year`."""
    _, count = list_page(1)
    total_pages = -(-count // PAGE_SIZE)
    lo, hi, start = 1, total_pages, None
    while lo <= hi:
        mid = (lo + hi) // 2
        movies, _ = list_page(mid)
        last_year = movies[-1]["year"] if movies else -1
        print(f"  searching… page {mid}/{total_pages} ends at {last_year}")
        if last_year <= year:
            start, hi = mid, mid - 1
        else:
            lo = mid + 1
        time.sleep(0.2)
    return start, total_pages

def get_movies_for_year(year):
    start, total_pages = find_start_page(year)
    if start is None:
        return []
    found = {}
    for page in range(start, total_pages + 1):
        movies, _ = list_page(page)
        if not movies:
            break
        for m in movies:
            if m.get("year") == year and m.get("imdb_code"):
                found[m["imdb_code"]] = m          # keyed by IMDb code → no duplicates
        print(f"Page {page} — {len(found)} movies from {year} so far...")
        if movies[-1]["year"] < year:
            break
        time.sleep(0.3)
    return list(found.values())

def run_parallel(items, fn, label, workers=4):
    """Runs fn(item) over items with a small thread pool, printing progress."""
    results, done = {}, 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fn, it): it for it in items}
        for fut in as_completed(futures):
            try:
                results[id(futures[fut])] = fut.result()
            except Exception as e:
                print(f"  {label}: skipped one ({e})")
            done += 1
            if done % 100 == 0 or done == len(items):
                print(f"  {label}: {done}/{len(items)}")
    return results

def add_popularity(movies):
    """Download/like counts are only in the per-movie details endpoint."""
    todo = [m for m in movies if m.get("download_count") is None]
    if not todo:
        return
    print(f"\nFetching popularity for {len(todo)} movies...")
    def one(m):
        d = api("movie_details.json", movie_id=m["id"]).get("movie") or {}
        m["download_count"] = d.get("download_count")
        m["like_count"]     = d.get("like_count")
        time.sleep(0.15)
    run_parallel(todo, one, "popularity")

# ── IMDb rating + votes (official free dataset) ────────────────────────────────
def load_imdb_ratings():
    print("\nDownloading IMDb ratings file (~8 MB)...")
    r = requests.get("https://datasets.imdbws.com/title.ratings.tsv.gz", timeout=180, headers=UA)
    r.raise_for_status()
    df = pd.read_csv(io.BytesIO(r.content), sep="\t", compression="gzip",
                     dtype={"tconst": str}, na_values="\\N")
    return dict(zip(df["tconst"], zip(df["averageRating"], df["numVotes"])))

# ── Budget / box office: TMDB (key) ────────────────────────────────────────────
def tmdb_get(path, **params):
    headers = dict(UA)
    if len(TMDB_API_KEY) > 40:
        headers["Authorization"] = f"Bearer {TMDB_API_KEY}"   # v4 read-access token
    else:
        params["api_key"] = TMDB_API_KEY                      # v3 API key
    for attempt in range(4):
        r = requests.get(f"https://api.themoviedb.org/3/{path}", params=params, headers=headers, timeout=20)
        if r.status_code == 429:
            time.sleep(2 + attempt)
            continue
        if r.status_code == 401:
            raise PermissionError("TMDB rejected the key (401)")
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return r.json()
    return None

def tmdb_money(movies):
    """Returns {imdb_code: (budget_usd, revenue_usd)} — TMDB stores both in USD."""
    try:
        tmdb_get("configuration")
    except Exception as e:
        print(f"\n⚠️  TMDB unavailable ({e}) — using Wikidata only.")
        return {}
    print(f"\nLooking up budget/box office on TMDB for {len(movies)} movies...")
    out = {}
    def one(m):
        code = m["imdb_code"]
        found = tmdb_get(f"find/{code}", external_source="imdb_id") or {}
        hits = found.get("movie_results") or []
        if hits:
            d = tmdb_get(f"movie/{hits[0]['id']}") or {}
            out[code] = (d.get("budget") or None, d.get("revenue") or None)
    run_parallel(movies, one, "TMDB", workers=8)
    return out

# ── Budget / box office: Wikidata (no key) ─────────────────────────────────────
# Approximate USD conversion rates, by Wikidata currency item. Good enough for a
# "big film vs small film" filter; amounts in unlisted currencies are skipped.
WD_USD = {
    "Q4917": 1.0,       # US dollar
    "Q4916": 1.08,      # euro
    "Q25224": 1.27,     # pound sterling
    "Q80524": 0.012,    # Indian rupee
    "Q1137675": 0.012 * 1e7,  # crore (of rupees)
    "Q39099": 0.14,     # renminbi
    "Q8146": 0.0067,    # yen
    "Q202040": 0.00073, # won
    "Q41044": 0.011,    # Russian ruble
    "Q1104069": 0.73,   # Canadian dollar
    "Q25344": 1.13,     # Swiss franc
    "Q173117": 0.18,    # Brazilian real
    "Q4730": 0.055,     # Mexican peso
    "Q31015": 0.128,    # Hong Kong dollar
    "Q131016": 0.043,   # Czech koruna
    "Q132643": 0.093,   # Norwegian krone
    "Q47190": 0.0027,   # forint
    "Q81893": 0.024,    # hryvnia
}

def wikidata_money(codes):
    """Returns {imdb_code: (budget_usd, gross_usd)}, taking the largest value of each."""
    print(f"\nLooking up budget/box office on Wikidata for {len(codes)} movies...")
    out = {}
    for i in range(0, len(codes), 150):
        values = " ".join(f'"{c}"' for c in codes[i:i + 150])
        query = f"""
        SELECT ?imdb ?kind ?amount ?unit WHERE {{
          VALUES ?imdb {{ {values} }}
          ?f wdt:P345 ?imdb .
          {{ ?f p:P2130/psv:P2130 ?v . BIND("budget" AS ?kind) }}
          UNION
          {{ ?f p:P2142/psv:P2142 ?v . BIND("gross" AS ?kind) }}
          ?v wikibase:quantityAmount ?amount ; wikibase:quantityUnit ?unit .
        }}"""
        try:
            r = requests.post("https://query.wikidata.org/sparql", data={"query": query},
                              headers={**UA, "Accept": "application/sparql-results+json"}, timeout=90)
            r.raise_for_status()
            rows = r.json()["results"]["bindings"]
        except Exception as e:
            print(f"  Wikidata batch failed ({e}) — skipping it")
            continue
        for row in rows:
            rate = WD_USD.get(row["unit"]["value"].rsplit("/", 1)[-1])
            if not rate:
                continue
            usd  = float(row["amount"]["value"]) * rate
            code = row["imdb"]["value"]
            b, g = out.get(code, (None, None))
            if row["kind"]["value"] == "budget":
                b = max(b or 0, usd)
            else:
                g = max(g or 0, usd)
            out[code] = (b, g)
        time.sleep(1)
    return out

# ═══ Run ═══════════════════════════════════════════════════════════════════════
print(f"Fetching {YEAR} movies...\n")
movies = get_movies_for_year(YEAR)
print(f"\n✅ Total {YEAR} movies found: {len(movies)}")
if not movies:
    raise SystemExit("Nothing to save.")

if FETCH_POPULARITY:
    add_popularity(movies)

ratings = load_imdb_ratings()

codes = [m["imdb_code"] for m in movies]
tmdb  = tmdb_money(movies) if (FETCH_MONEY and TMDB_API_KEY) else {}
wd    = wikidata_money(codes) if FETCH_MONEY else {}

# ── Build rows (metadata only) ─────────────────────────────────────────────────
rows = []
for m in movies:
    code = m["imdb_code"]
    imdb_rating, imdb_votes = ratings.get(code, (None, None))
    tb, tr = tmdb.get(code, (None, None))
    wb, wg = wd.get(code, (None, None))
    budget = tb or wb
    box    = tr or wg
    sources = sorted({s for s, used in (("tmdb", (tb and tb == budget) or (tr and tr == box)),
                                         ("wikidata", (wb and wb == budget and not tb) or (wg and wg == box and not tr)))
                      if used})
    trailer = f"https://www.youtube.com/watch?v={m['yt_trailer_code']}" if m.get("yt_trailer_code") else ""
    rows.append({
        "ID":              m.get("id"),
        "Title":           m.get("title_english") or m.get("title", ""),
        "Title_Long":      m.get("title_long", ""),
        "Year":            m.get("year"),
        "Rating":          m.get("rating"),
        "IMDb_Rating":     imdb_rating,
        "IMDb_Votes":      imdb_votes,
        "Budget_USD":      round(budget) if budget else None,
        "Box_Office_USD":  round(box) if box else None,
        "Money_Source":    "+".join(sources),
        "Runtime_mins":    m.get("runtime"),
        "Genres":          ", ".join(m.get("genres") or []),
        "Language":        m.get("language", ""),
        "MPA_Rating":      m.get("mpa_rating", ""),
        "Summary":         " ".join((m.get("summary") or "").split()),
        "IMDB_Code":       code,
        "IMDB_URL":        f"https://www.imdb.com/title/{code}",
        "Trailer_YouTube": trailer,
        "Cover_Image":     m.get("large_cover_image", ""),
        "Date_Uploaded":   m.get("date_uploaded", ""),
        "Download_Count":  m.get("download_count"),
        "Like_Count":      m.get("like_count"),
    })

df = pd.DataFrame(rows)
for col in ["ID", "Year", "Runtime_mins", "IMDb_Votes", "Budget_USD", "Box_Office_USD", "Download_Count", "Like_Count"]:
    df[col] = pd.to_numeric(df[col], errors="coerce").astype("Int64")   # 154321, not 154321.0
df = df.sort_values(["Box_Office_USD", "Download_Count"], ascending=False, na_position="last")

n = len(df)
print(f"\nCoverage for {n} movies:")
print(f"  IMDb votes:  {df['IMDb_Votes'].notna().sum()}")
print(f"  Budget:      {df['Budget_USD'].notna().sum()}")
print(f"  Box office:  {df['Box_Office_USD'].notna().sum()}")
print(f"  Downloads:   {df['Download_Count'].notna().sum()}")
display(df)

# ── Save ───────────────────────────────────────────────────────────────────────
csv_name, tsv_name = f"yts_{YEAR}_movies.csv", f"yts_{YEAR}_movies.tsv"
df.to_csv(csv_name, index=False)
df.to_csv(tsv_name, index=False, sep="\t")
print(f"\n✅ Saved {csv_name} and {tsv_name} — download from the Files panel on the left,")
print("   then in the app: Discover → New Releases → Import CSV.")
