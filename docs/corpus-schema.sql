PRAGMA foreign_keys = ON;

CREATE TABLE package_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE imports (
  uid TEXT PRIMARY KEY,
  source_name TEXT NOT NULL,
  source_sha256 TEXT NOT NULL,
  source_bytes INTEGER NOT NULL,
  source_lines INTEGER NOT NULL,
  source_encoding TEXT NOT NULL,
  parser_version TEXT NOT NULL,
  imported_at TEXT NOT NULL
);

CREATE TABLE terms (
  id INTEGER PRIMARY KEY,
  uid TEXT NOT NULL UNIQUE,
  slug TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  normalized_title TEXT NOT NULL,
  language TEXT NOT NULL DEFAULT 'und',
  kind TEXT NOT NULL CHECK (kind IN ('article', 'reference', 'query', 'invalid_source')),
  status TEXT NOT NULL DEFAULT 'seed' CHECK (status IN ('seed', 'enriched', 'reviewed', 'archived')),
  summary TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  content_format TEXT NOT NULL DEFAULT 'plain_text',
  source_url TEXT NOT NULL DEFAULT '',
  content_sha256 TEXT NOT NULL DEFAULT '',
  revision INTEGER NOT NULL DEFAULT 1,
  is_public INTEGER NOT NULL DEFAULT 0 CHECK (is_public IN (0, 1)),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX idx_terms_language_title ON terms(language, normalized_title);
CREATE INDEX idx_terms_status ON terms(status);
CREATE INDEX idx_terms_public ON terms(is_public) WHERE is_public = 1;

CREATE TABLE sources (
  id INTEGER PRIMARY KEY,
  uid TEXT NOT NULL UNIQUE,
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  source_kind TEXT NOT NULL,
  url TEXT NOT NULL,
  canonical_url TEXT NOT NULL,
  host TEXT NOT NULL,
  language TEXT NOT NULL DEFAULT 'und',
  license_name TEXT NOT NULL DEFAULT '',
  retrieved_at TEXT,
  content_sha256 TEXT NOT NULL DEFAULT '',
  UNIQUE (term_id, canonical_url)
);

CREATE INDEX idx_sources_term ON sources(term_id);
CREATE INDEX idx_sources_host ON sources(host);

CREATE TABLE source_occurrences (
  id INTEGER PRIMARY KEY,
  import_uid TEXT NOT NULL REFERENCES imports(uid) ON DELETE CASCADE,
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  source_id INTEGER REFERENCES sources(id) ON DELETE SET NULL,
  line_number INTEGER NOT NULL,
  item_index INTEGER NOT NULL,
  group_number INTEGER NOT NULL,
  raw_line TEXT NOT NULL,
  raw_value TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  UNIQUE (import_uid, line_number, item_index)
);

CREATE INDEX idx_occurrences_term ON source_occurrences(term_id);
CREATE INDEX idx_occurrences_group ON source_occurrences(import_uid, group_number);

CREATE TABLE aliases (
  id INTEGER PRIMARY KEY,
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  alias TEXT NOT NULL,
  normalized_alias TEXT NOT NULL,
  language TEXT NOT NULL DEFAULT 'und',
  origin TEXT NOT NULL DEFAULT 'curated',
  UNIQUE (term_id, normalized_alias, language)
);

CREATE TABLE categories (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE tags (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE term_categories (
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY (term_id, category_id)
);

CREATE TABLE term_tags (
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (term_id, tag_id)
);

CREATE TABLE term_relations (
  id INTEGER PRIMARY KEY,
  uid TEXT NOT NULL UNIQUE,
  source_term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  target_term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  relation_type TEXT NOT NULL,
  origin TEXT NOT NULL CHECK (origin IN ('curated', 'source_list', 'extracted', 'inferred')),
  confidence REAL NOT NULL CHECK (confidence >= 0.0 AND confidence <= 1.0),
  bidirectional INTEGER NOT NULL DEFAULT 0 CHECK (bidirectional IN (0, 1)),
  evidence_occurrence_id INTEGER REFERENCES source_occurrences(id) ON DELETE SET NULL,
  created_at TEXT NOT NULL,
  UNIQUE (source_term_id, target_term_id, relation_type, origin)
);

CREATE INDEX idx_relations_source ON term_relations(source_term_id);
CREATE INDEX idx_relations_target ON term_relations(target_term_id);

CREATE VIRTUAL TABLE terms_fts USING fts5(
  title,
  normalized_title,
  summary,
  content,
  slug UNINDEXED,
  language UNINDEXED,
  content='terms',
  content_rowid='id',
  tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER terms_ai AFTER INSERT ON terms BEGIN
  INSERT INTO terms_fts(rowid, title, normalized_title, summary, content, slug, language)
  VALUES (new.id, new.title, new.normalized_title, new.summary, new.content, new.slug, new.language);
END;

CREATE TRIGGER terms_ad AFTER DELETE ON terms BEGIN
  INSERT INTO terms_fts(terms_fts, rowid, title, normalized_title, summary, content, slug, language)
  VALUES ('delete', old.id, old.title, old.normalized_title, old.summary, old.content, old.slug, old.language);
END;

CREATE TRIGGER terms_au AFTER UPDATE ON terms BEGIN
  INSERT INTO terms_fts(terms_fts, rowid, title, normalized_title, summary, content, slug, language)
  VALUES ('delete', old.id, old.title, old.normalized_title, old.summary, old.content, old.slug, old.language);
  INSERT INTO terms_fts(rowid, title, normalized_title, summary, content, slug, language)
  VALUES (new.id, new.title, new.normalized_title, new.summary, new.content, new.slug, new.language);
END;

PRAGMA user_version = 2;
