-- Legacy MVP schema. The canonical package schema is corpus-schema.sql.

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

CREATE VIRTUAL TABLE IF NOT EXISTS terms_fts USING fts5(
  title,
  summary,
  content,
  slug UNINDEXED,
  content='terms',
  content_rowid='id'
);
