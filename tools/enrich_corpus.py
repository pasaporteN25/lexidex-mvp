"""
Enriquece un paquete de conocimiento con el extracto de entrada de cada articulo.

Reutiliza el fetcher acotado del backend (ADR 0003) en vez de reimplementarlo: allowlist de
hosts, https obligatorio, timeouts, tope de tamano y redirecciones revalidadas salen de ahi.

Pide de a lotes con la Action API (`prop=extracts`, hasta 20 titulos por consulta) y no de a un
articulo por vez: uno por termino son ~4.500 pedidos y Wikipedia responde 429 enseguida; en
lotes de 20 son ~230, que ademas es mucho mas cortes con un servicio ajeno.

El paquete queda inmutable una vez publicado (ADR 0001), asi que esto se corre sobre una copia
de trabajo y despues se regenera el manifiesto. Es reanudable: solo toca los terminos que
todavia no tienen contenido, de modo que cortarlo y volver a lanzarlo continua donde iba.

Uso tipico:
    py -3 tools/enrich_corpus.py <paquete>/lexidex.sqlite --dry-run --limit 100
    py -3 tools/enrich_corpus.py <paquete>/lexidex.sqlite
"""

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import time
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote, urlencode, urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402

WIKIPEDIA_HOST_PATTERN = re.compile(r"^([a-z]{2,3})\.wikipedia\.org$")
BATCH_SIZE = 20
DEFAULT_SLEEP_SECONDS = 1.0
RATE_LIMIT_BACKOFF_SECONDS = 30
MAX_RATE_LIMIT_RETRIES = 4
TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


def wikipedia_target(url):
    """Devuelve (idioma, titulo) si la URL es un articulo de Wikipedia, o None."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return None
    match = WIKIPEDIA_HOST_PATTERN.match((parsed.hostname or "").lower())
    if not match or not parsed.path.startswith("/wiki/"):
        return None
    title = unquote(parsed.path[len("/wiki/"):]).replace("_", " ").strip()
    if not title or ":" in title:
        # Un prefijo con dos puntos es un espacio de nombres (Categoria:, Archivo:, Ayuda:),
        # no un articulo; pedirlos solo devuelve ruido.
        return None
    return match.group(1), title


def fetch_batch(language, titles):
    """
    Extracto y descripcion de hasta [BATCH_SIZE] titulos en una sola consulta.

    Devuelve {titulo_pedido: {"extract": str, "description": str}}. MediaWiki normaliza y sigue
    redirecciones, asi que hay que rehacer la cadena titulo_pedido -> titulo_final para poder
    devolver los datos bajo el nombre con el que se los pidio.
    """
    query = urlencode(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "prop": "extracts|description",
            "exintro": "1",
            "explaintext": "1",
            "exlimit": str(BATCH_SIZE),
            "redirects": "1",
            "titles": "|".join(titles),
        }
    )
    payload = api.fetch_knowledge_json(f"https://{language}.wikipedia.org/w/api.php?{query}")
    section = payload.get("query") or {}

    # Cadena de reescrituras: lo pedido puede normalizarse y despues redirigirse.
    rewrites = {}
    for step in ("normalized", "redirects"):
        for item in section.get(step) or []:
            rewrites[item.get("from")] = item.get("to")

    def resolve(title):
        seen = set()
        current = title
        while current in rewrites and current not in seen:
            seen.add(current)
            current = rewrites[current]
        return current

    by_final = {}
    for page in section.get("pages") or []:
        if page.get("missing") or not page.get("title"):
            continue
        by_final[page["title"]] = {
            "extract": (page.get("extract") or "").strip(),
            "description": (page.get("description") or "").strip(),
        }

    return {title: by_final[resolve(title)] for title in titles if resolve(title) in by_final}


def fetch_batch_with_backoff(language, titles, sleep_seconds):
    """Reintenta solo ante 429, que es una peticion de esperar, no un fallo del articulo."""
    for attempt in range(MAX_RATE_LIMIT_RETRIES):
        try:
            return fetch_batch(language, titles)
        except api.ApiError as error:
            if error.details.get("status") != 429:
                raise
            wait = RATE_LIMIT_BACKOFF_SECONDS * (attempt + 1)
            print(f"  limite de tasa alcanzado, esperando {wait}s")
            time.sleep(wait)
    raise api.ApiError(502, "source_unavailable", "La fuente sigue limitando la tasa.")


def pending_terms(conn, limit):
    sql = """
        SELECT id, title, source_url
        FROM terms
        WHERE content = '' AND source_url LIKE '%wikipedia.org/wiki/%'
        ORDER BY id
    """
    if limit:
        sql += f" LIMIT {int(limit)}"
    return conn.execute(sql).fetchall()


def truncate_extract(text, max_chars):
    """Corta en el limite de oracion mas cercano por debajo del tope, para no partir al medio."""
    text = (text or "").strip()
    if not max_chars or len(text) <= max_chars:
        return text
    window = text[:max_chars]
    cut = max(window.rfind(". "), window.rfind(".\n"))
    if cut > max_chars * 0.5:
        return window[: cut + 1].strip()
    return window.rstrip() + "..."


def enrich(database, limit=0, dry_run=False, sleep_seconds=DEFAULT_SLEEP_SECONDS, max_chars=0):
    conn = sqlite3.connect(database)
    conn.row_factory = sqlite3.Row
    stats = {"ok": 0, "sin_extracto": 0, "fuera_de_alcance": 0, "error": 0, "bytes": 0}
    lengths = []
    raw_extracts = []

    try:
        rows = pending_terms(conn, limit)
        by_language = defaultdict(list)
        for row in rows:
            target = wikipedia_target(row["source_url"])
            if target is None:
                stats["fuera_de_alcance"] += 1
                continue
            language, title = target
            by_language[language].append((title, row))

        batches = [
            (language, entries[start:start + BATCH_SIZE])
            for language, entries in by_language.items()
            for start in range(0, len(entries), BATCH_SIZE)
        ]
        print(f"terminos a consultar: {sum(len(v) for v in by_language.values())} en {len(batches)} lotes")

        for index, (language, entries) in enumerate(batches, start=1):
            titles = [title for title, _ in entries]
            try:
                found = fetch_batch_with_backoff(language, titles, sleep_seconds)
            except api.ApiError as error:
                stats["error"] += len(entries)
                print(f"  [lote {index}/{len(batches)}] {language}: {error.code}")
                time.sleep(sleep_seconds)
                continue

            for title, row in entries:
                article = found.get(title)
                if not article or not article["extract"]:
                    stats["sin_extracto"] += 1
                    continue

                if dry_run:
                    raw_extracts.append(article["extract"])
                content = truncate_extract(article["extract"], max_chars)
                summary = article["description"]
                encoded = content.encode("utf-8")
                lengths.append(len(encoded))
                stats["bytes"] += len(encoded) + len(summary.encode("utf-8"))
                stats["ok"] += 1

                if not dry_run:
                    conn.execute(
                        """
                        UPDATE terms
                        SET summary = ?, content = ?, content_sha256 = ?, status = 'enriched',
                            revision = revision + 1, updated_at = strftime(?, 'now')
                        WHERE id = ?
                        """,
                        (summary, content, hashlib.sha256(encoded).hexdigest(), TIMESTAMP_FORMAT, row["id"]),
                    )

            if not dry_run:
                conn.commit()
            if index % 10 == 0 or index == len(batches):
                print(f"  [lote {index}/{len(batches)}] acumulado: {stats['ok']} con extracto")
            time.sleep(sleep_seconds)

        if not dry_run:
            conn.commit()
    finally:
        conn.close()

    if lengths:
        ordered = sorted(lengths)
        stats["promedio_bytes"] = round(sum(ordered) / len(ordered))
        stats["mediana_bytes"] = ordered[len(ordered) // 2]
        stats["p90_bytes"] = ordered[int(len(ordered) * 0.9)]
        stats["max_bytes"] = ordered[-1]

    # En dry-run se proyecta cada tope sobre la misma muestra: elegir el recorte no deberia
    # costar otra tanda de pedidos a un servicio ajeno.
    if raw_extracts:
        projections = {}
        for cap in (0, 400, 600, 800, 1200, 2000):
            sizes = [len(truncate_extract(text, cap).encode("utf-8")) for text in raw_extracts]
            average = sum(sizes) / len(sizes)
            label = "sin corte" if cap == 0 else f"{cap} caracteres"
            projections[label] = {
                "promedio_bytes": round(average),
                "texto_mb_4472": round(average * 4472 / 1_048_576, 1),
            }
        stats["proyeccion_por_tope"] = projections
    return stats


SEED_LIMITATION = "This is a seed catalog; article bodies and summaries were not fetched."


def finalize_package(database, package_version=None):
    """
    Cierra el paquete despues de enriquecer: compacta la base y rehace el manifiesto.

    Hace falta porque el manifiesto describe artefactos por checksum (ADR 0001) y el .sqlite
    acaba de cambiar; dejarlo desactualizado haria que la verificacion fail-closed rechace el
    paquete al abrirlo, tanto en el backend como en Android.
    """
    database = Path(database)
    conn = sqlite3.connect(database)
    try:
        enriched = conn.execute("SELECT COUNT(*) FROM terms WHERE content <> ''").fetchone()[0]
        conn.execute("VACUUM")
    finally:
        conn.close()

    manifest_path = database.parent / "manifest.json"
    if not manifest_path.exists():
        return {"enriquecidos": enriched, "manifiesto": "no encontrado"}

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    payload = database.read_bytes()
    manifest["artifacts"]["database"] = {
        "file": database.name,
        "bytes": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }
    manifest["capabilities"]["rag_ready_terms"] = enriched
    manifest["limitations"] = [l for l in manifest.get("limitations", []) if l != SEED_LIMITATION]
    manifest["limitations"].append(
        "Los extractos provienen de Wikipedia (CC BY-SA); cada termino conserva su URL de origen "
        "como atribucion. Se guarda solo la introduccion, recortada en limite de oracion."
    )
    if package_version:
        manifest["package_version"] = package_version
    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8"
    )
    return {
        "enriquecidos": enriched,
        "base_mb": round(len(payload) / 1_048_576, 2),
        "version": manifest["package_version"],
    }


def main():
    # Los titulos traen acentos y alfabetos no latinos; la consola de Windows es cp1252 por
    # defecto y cortaria el proceso entero al intentar imprimirlos.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database")
    parser.add_argument("--limit", type=int, default=0, help="solo los primeros N pendientes")
    parser.add_argument("--dry-run", action="store_true", help="consulta y mide, no escribe")
    parser.add_argument("--sleep", type=float, default=DEFAULT_SLEEP_SECONDS)
    parser.add_argument(
        "--max-chars",
        type=int,
        default=0,
        help="corta el extracto en la oracion mas cercana por debajo de N caracteres (0 = sin corte)",
    )
    parser.add_argument(
        "--package-version",
        default=None,
        help="version nueva a escribir en el manifiesto al terminar",
    )
    args = parser.parse_args()

    stats = enrich(
        args.database,
        limit=args.limit,
        dry_run=args.dry_run,
        sleep_seconds=args.sleep,
        max_chars=args.max_chars,
    )
    if not args.dry_run:
        stats["paquete"] = finalize_package(args.database, args.package_version)
    print(json.dumps(stats, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
