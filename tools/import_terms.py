import argparse
import csv
import sqlite3
from pathlib import Path


SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS terms (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  slug TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  summary TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  source_url TEXT NOT NULL DEFAULT '',
  language TEXT NOT NULL DEFAULT 'es',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS tags (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS term_categories (
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY (term_id, category_id)
);

CREATE TABLE IF NOT EXISTS term_tags (
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (term_id, tag_id)
);

CREATE TABLE IF NOT EXISTS term_relations (
  source_term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  target_term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  relation_type TEXT NOT NULL DEFAULT 'related_to',
  PRIMARY KEY (source_term_id, target_term_id, relation_type)
);
"""


FTS_SCHEMA = """
CREATE VIRTUAL TABLE IF NOT EXISTS terms_fts USING fts5(
  title,
  summary,
  content,
  slug UNINDEXED,
  content='terms',
  content_rowid='id'
);
"""


def split_pipe(value):
    return [item.strip() for item in (value or "").split("|") if item.strip()]


def get_or_create(conn, table, name):
    conn.execute(f"INSERT OR IGNORE INTO {table} (name) VALUES (?)", (name,))
    row = conn.execute(f"SELECT id FROM {table} WHERE name = ?", (name,)).fetchone()
    return row[0]


def rebuild_fts(conn):
    try:
        conn.executescript(FTS_SCHEMA)
        conn.execute("INSERT INTO terms_fts(terms_fts) VALUES ('rebuild')")
    except sqlite3.OperationalError:
        # Some SQLite builds are compiled without FTS5. Search still works via LIKE.
        pass


def import_csv(csv_path, db_path):
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(SCHEMA)

    rows = []
    with Path(csv_path).open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            rows.append(row)
            conn.execute(
                """
                INSERT INTO terms (slug, title, summary, content, source_url, language, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(slug) DO UPDATE SET
                  title = excluded.title,
                  summary = excluded.summary,
                  content = excluded.content,
                  source_url = excluded.source_url,
                  language = excluded.language,
                  updated_at = CURRENT_TIMESTAMP
                """,
                (
                    row["slug"].strip(),
                    row["title"].strip(),
                    row.get("summary", "").strip(),
                    row.get("content", "").strip(),
                    row.get("source_url", "").strip(),
                    row.get("language", "es").strip() or "es",
                ),
            )

    conn.execute("DELETE FROM term_categories")
    conn.execute("DELETE FROM term_tags")
    conn.execute("DELETE FROM term_relations")

    slug_to_id = {
        slug: term_id
        for term_id, slug in conn.execute("SELECT id, slug FROM terms").fetchall()
    }

    for row in rows:
        term_id = slug_to_id[row["slug"].strip()]

        for category in split_pipe(row.get("categories", "")):
            category_id = get_or_create(conn, "categories", category)
            conn.execute(
                "INSERT OR IGNORE INTO term_categories (term_id, category_id) VALUES (?, ?)",
                (term_id, category_id),
            )

        for tag in split_pipe(row.get("tags", "")):
            tag_id = get_or_create(conn, "tags", tag)
            conn.execute(
                "INSERT OR IGNORE INTO term_tags (term_id, tag_id) VALUES (?, ?)",
                (term_id, tag_id),
            )

        for target_slug in split_pipe(row.get("relations", "")):
            target_id = slug_to_id.get(target_slug)
            if target_id:
                conn.execute(
                    """
                    INSERT OR IGNORE INTO term_relations
                    (source_term_id, target_term_id, relation_type)
                    VALUES (?, ?, 'related_to')
                    """,
                    (term_id, target_id),
                )

    rebuild_fts(conn)
    conn.commit()
    count = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    conn.close()
    return count


def main():
    parser = argparse.ArgumentParser(description="Import Lexidex CSV into SQLite.")
    parser.add_argument("csv_path")
    parser.add_argument("db_path")
    args = parser.parse_args()
    count = import_csv(args.csv_path, args.db_path)
    print(f"Imported {count} terms into {args.db_path}")


if __name__ == "__main__":
    main()
