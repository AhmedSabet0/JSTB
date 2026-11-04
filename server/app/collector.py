"""Centralized LinkedIn Job Collector & Matcher for Auto Apply.

Scrapes public LinkedIn guest job listings (zero cookies/login required), extracts optimal search
keywords from candidate CVs using AI (max 4 per user), maintains a central jobs database updated
hourly, and scores job matches per user (filtering at >= 60% match). Matched jobs are synced
automatically to user records and Google Drive.
"""
import asyncio
import json
import logging
import random
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import httpx
from bs4 import BeautifulSoup

from app import ai, db, records

logger = logging.getLogger("autoapply.collector")

LINKEDIN_GUEST_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
]

DEFAULT_KEYWORDS = ["Network Engineer", "IT Support Specialist", "System Administrator", "Software Engineer"]


def clean_job_url(raw_url: str) -> str:
    """Strips query tracking parameters, preserving clean job view URL."""
    if not raw_url:
        return ""
    clean = raw_url.split("?")[0].strip()
    return clean


def extract_job_id(card: Any, url: str) -> str:
    """Extracts unique numeric or entity job ID from card attributes or URL."""
    base_card = card.find("div", class_="base-card")
    if base_card:
        urn = base_card.get("data-entity-urn", "")
        if ":" in urn:
            return urn.split(":")[-1]
    # Check data-id on the li
    card_id = card.get("data-id") or card.get("data-entity-urn")
    if card_id:
        return str(card_id).split(":")[-1]
    # Check URL
    m = re.search(r"view/(?:[^/?#\s]*-)?(\d{6,})", url)
    if m:
        return m.group(1)
    # Fallback to hash of clean url
    return str(abs(hash(clean_job_url(url))))[:10]


async def fetch_linkedin_guest_jobs(
    keywords: str,
    location: str = "Egypt",
    total_jobs: int = 25,
    max_pages: int = 2,
) -> List[Dict[str, Any]]:
    """Fetches public LinkedIn job postings without authentication or cookies.

    Uses LinkedIn's guest search endpoint with randomized delays to respect rate limits.
    """
    jobs: List[Dict[str, Any]] = []
    seen_ids = set()
    page_size = 25
    start = 0

    headers = {
        "User-Agent": random.choice(USER_AGENTS),
        "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
        "Accept": "*/*",
    }

    async with httpx.AsyncClient(headers=headers, timeout=httpx.Timeout(25.0, connect=10.0)) as client:
        for _ in range(max_pages):
            params = {
                "keywords": keywords,
                "location": location,
                "start": start,
            }
            try:
                resp = await client.get(LINKEDIN_GUEST_URL, params=params)
            except Exception as e:
                logger.warning(f"Error fetching jobs for {keywords} in {location}: {e}")
                break

            if resp.status_code == 429:
                logger.warning(f"Rate limited (429) on LinkedIn guest API for {keywords}. Pausing query.")
                break
            if resp.status_code != 200:
                logger.warning(f"LinkedIn guest API returned status {resp.status_code} for {keywords}.")
                break

            soup = BeautifulSoup(resp.text, "html.parser")
            job_cards = soup.find_all("li")
            if not job_cards:
                break

            for card in job_cards:
                title_tag = card.find("h3", class_="base-search-card__title")
                company_tag = card.find("h4", class_="base-search-card__subtitle")
                location_tag = card.find("span", class_="job-search-card__location")
                link_tag = card.find("a", class_="base-card__full-link")
                time_tag = card.find("time")

                raw_url = link_tag["href"] if link_tag and "href" in link_tag.attrs else ""
                url = clean_job_url(raw_url)
                if not url:
                    continue

                job_id = extract_job_id(card, url)
                if job_id in seen_ids:
                    continue
                seen_ids.add(job_id)

                title = title_tag.get_text(strip=True) if title_tag else ""
                company = company_tag.get_text(strip=True) if company_tag else ""
                job_loc = location_tag.get_text(strip=True) if location_tag else location
                posted_date = time_tag.get_text(strip=True) if time_tag else ""

                if not title:
                    continue

                jobs.append({
                    "id": job_id,
                    "title": title,
                    "company": company,
                    "location": job_loc,
                    "url": url,
                    "posted_date": posted_date,
                    "keywords": keywords,
                    "collected_at": time.time(),
                })

                if len(jobs) >= total_jobs:
                    break

            if len(jobs) >= total_jobs or len(job_cards) < 5:
                break

            start += page_size
            await asyncio.sleep(random.uniform(2.0, 3.5))

    return jobs


async def extract_keywords_from_cv(cv_text: str) -> List[str]:
    """Uses AI to extract up to 4 high-value job search keywords/titles from candidate's CV."""
    if not cv_text or len(cv_text.strip()) < 100:
        return []

    settings = {r["key"]: r["value"] for r in db.conn().execute("SELECT key, value FROM settings").fetchall()}
    key = settings.get("openrouter_key", "").strip()
    model = settings.get("model", "openai/gpt-4o-mini").strip()

    prompt = (
        "You are an expert technical recruiter and career counselor.\n"
        "Analyze this candidate's CV text and extract 2 to 4 of the most accurate, high-demand job titles "
        "to search for on LinkedIn (e.g. ['Network Engineer', 'IT Support Specialist', 'System Administrator']).\n\n"
        "Rules:\n"
        "1. Return AT MOST 4 job titles.\n"
        "2. Keep each title concise (1-3 words, standard job market naming).\n"
        "3. Output MUST be valid JSON array of strings only, like: [\"Title 1\", \"Title 2\"].\n"
        "4. No markdown formatting, no code fences, no extra text.\n\n"
        f"Candidate CV:\n{cv_text[:4000]}"
    )

    if key:
        try:
            res = await ai.chat(
                key=key,
                model=model,
                messages=[{"role": "user", "content": prompt}],
                max_tokens=200,
                temperature=0.2,
            )
            content = res["choices"][0]["message"]["content"].strip()
            # Clean up potential code block markers
            if "```" in content:
                content = re.sub(r"^```[a-z]*\s*", "", content)
                content = re.sub(r"\s*```$", "", content)
            parsed = json.loads(content)
            if isinstance(parsed, list):
                clean_titles = []
                for item in parsed:
                    if isinstance(item, str) and item.strip():
                        clean_titles.append(item.strip()[:60])
                if clean_titles:
                    return clean_titles[:4]
        except Exception as e:
            logger.warning(f"AI keyword extraction failed: {e}. Falling back to heuristic.")

    # Heuristic fallback if AI unavailable or fails
    fallback_titles = []
    text_lower = cv_text.lower()
    common_roles = [
        "network engineer", "systems administrator", "system administrator",
        "it support", "it specialist", "devops engineer", "soc analyst",
        "frontend developer", "backend developer", "full stack developer",
        "software engineer", "python developer", "data analyst",
        "quality assurance", "cybersecurity specialist"
    ]
    for role in common_roles:
        if role in text_lower and len(fallback_titles) < 4:
            fallback_titles.append(role.title())

    return fallback_titles if fallback_titles else ["Software Engineer"]


async def score_job_for_user(cv_text: str, job: Dict[str, Any]) -> Tuple[int, str]:
    """Scores match between candidate CV and job posting (0-100%). Returns (score, why)."""
    settings = {r["key"]: r["value"] for r in db.conn().execute("SELECT key, value FROM settings").fetchall()}
    key = settings.get("openrouter_key", "").strip()
    model = settings.get("model", "openai/gpt-4o-mini").strip()

    title = job.get("title", "")
    company = job.get("company", "")
    loc = job.get("location", "")

    if key and cv_text:
        prompt = (
            "You are an AI recruiter. Evaluate candidate CV fit for this job on a scale of 0 to 100.\n\n"
            f"Job Title: {title}\n"
            f"Company: {company}\n"
            f"Location: {loc}\n\n"
            f"Candidate CV Summary:\n{cv_text[:2500]}\n\n"
            "Return JSON only: {\"score\": 85, \"why\": \"1-2 concise sentences in English explaining the match reason.\"}\n"
            "Score must be an integer between 0 and 100."
        )
        try:
            res = await ai.chat(
                key=key,
                model=model,
                messages=[{"role": "user", "content": prompt}],
                max_tokens=150,
                temperature=0.2,
            )
            content = res["choices"][0]["message"]["content"].strip()
            if "```" in content:
                content = re.sub(r"^```[a-z]*\s*", "", content)
                content = re.sub(r"\s*```$", "", content)
            parsed = json.loads(content)
            score = int(parsed.get("score", 50))
            why = str(parsed.get("why", "")).strip()[:240]
            score = max(0, min(100, score))
            return score, why
        except Exception as e:
            logger.debug(f"AI scoring failed: {e}. Using heuristic scoring.")

    # Fast heuristic scoring based on keyword overlap
    words_title = set(re.findall(r"\w+", title.lower()))
    cv_lower = cv_text.lower()
    matches = sum(1 for w in words_title if len(w) > 2 and w in cv_lower)
    score = min(95, max(40, 50 + matches * 15))
    why = f"Matches {matches} core technical keywords from your CV for '{title}'."
    return score, why


def get_user_cv_text(user_id: int) -> str:
    """Retrieves the latest CV text stored for this user."""
    with db.tx() as t:
        rec = records.current(t, user_id, "cv", "main")
        if rec and rec.get("data") and isinstance(rec["data"], dict):
            return str(rec["data"].get("text", ""))
    return ""


async def process_user_cv(user_id: int, cv_text: Optional[str] = None) -> List[str]:
    """Called when user uploads or updates CV. Extracts keywords, updates user_keywords,

    and performs immediate search & matching for the user.
    """
    if cv_text is None:
        cv_text = get_user_cv_text(user_id)
    if not cv_text:
        return []

    # Get location from settings or default to Egypt
    loc = "Egypt"
    with db.tx() as t:
        cfg = records.current(t, user_id, "settings", "LOCATION")
        if cfg and cfg.get("data") and cfg["data"].get("v"):
            loc = cfg["data"]["v"]

    keywords = await extract_keywords_from_cv(cv_text)
    if not keywords:
        return []

    now = time.time()
    with db.tx() as t:
        # Save up to 4 keywords for this user
        t.execute("DELETE FROM user_keywords WHERE user_id = ?", (user_id,))
        for kw in keywords[:4]:
            t.execute(
                "INSERT INTO user_keywords(user_id, keyword, location, created_at) VALUES (?, ?, ?, ?)",
                (user_id, kw, loc, now),
            )

    # Immediately fetch jobs for these keywords and match
    for kw in keywords[:4]:
        try:
            jobs = await fetch_linkedin_guest_jobs(kw, loc, total_jobs=20)
            await save_and_match_jobs(jobs, kw, loc)
        except Exception as e:
            logger.warning(f"Error fetching initial jobs for user {user_id} kw {kw}: {e}")

    return keywords


async def save_and_match_jobs(jobs: List[Dict[str, Any]], keyword: str, location: str) -> int:
    """Saves collected jobs into server_jobs and evaluates matches against all interested users.

    Jobs matching with score >= 60% are recorded in user_job_matches AND added as copilot records
    so they seamlessly sync to the Android app and Google Drive!
    """
    if not jobs:
        return 0

    now = time.time()
    new_jobs_count = 0

    # 1. Insert jobs into server_jobs
    with db.tx() as t:
        for j in jobs:
            t.execute(
                """
                INSERT INTO server_jobs(id, title, company, location, url, posted_date, collected_at, keywords)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  collected_at = excluded.collected_at,
                  posted_date = excluded.posted_date
                """,
                (j["id"], j["title"], j["company"], j["location"], j["url"], j["posted_date"], now, keyword),
            )
            new_jobs_count += 1

    # 2. Find all users who have this keyword or relevant CV
    with db.tx() as t:
        users = t.execute(
            """
            SELECT DISTINCT u.id, u.email
            FROM users u
            JOIN user_keywords uk ON uk.user_id = u.id
            WHERE LOWER(uk.keyword) = LOWER(?)
            """,
            (keyword,),
        ).fetchall()

    for u in users:
        uid = u["id"]
        cv_text = get_user_cv_text(uid)
        if not cv_text:
            continue

        copilot_changes = []
        for j in jobs:
            job_id = j["id"]
            # Check if user already scored or has this match
            existing = db.conn().execute(
                "SELECT score, status FROM user_job_matches WHERE user_id = ? AND job_id = ?",
                (uid, job_id),
            ).fetchone()

            if existing is not None:
                continue

            score, why = await score_job_for_user(cv_text, j)
            if score >= 60:
                with db.tx() as t:
                    t.execute(
                        """
                        INSERT INTO user_job_matches(user_id, job_id, score, why, status, updated_at)
                        VALUES (?, ?, ?, ?, 'new', ?)
                        ON CONFLICT(user_id, job_id) DO UPDATE SET
                          score = excluded.score,
                          why = excluded.why,
                          updated_at = excluded.updated_at
                        """,
                        (uid, job_id, score, why, now),
                    )

                # Format as a copilot record for mobile sync & Google Drive
                copilot_record_data = {
                    "id": f"li:{job_id}",
                    "url": j["url"],
                    "title": j["title"],
                    "company": j["company"],
                    "channel": "linkedin",
                    "source": "server_match",
                    "added": now,
                    "state": "new",
                    "stage": "",
                    "fit": max(1, min(10, round(score / 10))),
                    "score": score,
                    "why": why,
                    "location": j.get("location", ""),
                    "posted_date": j.get("posted_date", ""),
                }
                copilot_changes.append(("copilot", f"li:{job_id}", copilot_record_data, now))

        if copilot_changes:
            with db.tx() as t:
                records.write(t, uid, copilot_changes, "server")

    return new_jobs_count


async def run_collection_cycle() -> int:
    """Runs the periodic collection cycle for all distinct keywords in the database.

    Runs hourly in the background.
    """
    logger.info("Starting hourly LinkedIn guest job collection cycle...")
    c = db.conn()
    queries = c.execute(
        """
        SELECT DISTINCT keyword, location
        FROM user_keywords
        LIMIT 50
        """
    ).fetchall()

    if not queries:
        # Default seed queries if no users have set keywords yet
        queries = [{"keyword": kw, "location": "Egypt"} for kw in DEFAULT_KEYWORDS]

    total_collected = 0
    for q in queries:
        kw = q["keyword"]
        loc = q["location"] or "Egypt"
        try:
            logger.info(f"Collecting jobs for [{kw}] in [{loc}]...")
            jobs = await fetch_linkedin_guest_jobs(kw, loc, total_jobs=25)
            count = await save_and_match_jobs(jobs, kw, loc)
            total_collected += count
            # Polite pause between keyword searches to avoid rate limiting
            await asyncio.sleep(random.uniform(4.0, 7.0))
        except Exception as e:
            logger.error(f"Error during job collection cycle for '{kw}': {e}", exc_info=True)

    logger.info(f"Job collection cycle finished. Processed {total_collected} jobs.")
    return total_collected


async def periodic_job_collector_task():
    """Background standing worker that runs the job collection and scoring cycle every hour."""
    # Brief initial startup delay to let the app fully initialize
    await asyncio.sleep(10.0)
    while True:
        try:
            await run_collection_cycle()
        except Exception as e:
            logger.error(f"Unexpected error in periodic job collector: {e}", exc_info=True)
        # Sleep for 1 hour (3600 seconds)
        await asyncio.sleep(3600)
