const PAGE_SIZE = 75;

const state = {
  terms: [],
  total: 0,
  offset: 0,
  activeSlug: null,
  activeTerm: null,
  editingSlug: null,
  requestId: 0,
  stats: null,
  snackbarTimer: null
};

const elements = {
  addTermButton: document.querySelector("#addTermButton"),
  libraryButton: document.querySelector("#libraryButton"),
  libraryCount: document.querySelector("#libraryCount"),
  dailyButton: document.querySelector("#dailyButton"),
  randomButton: document.querySelector("#randomButton"),
  packageLabel: document.querySelector("#packageLabel"),
  personalCount: document.querySelector("#personalCount"),
  stats: document.querySelector("#stats"),
  themeToggle: document.querySelector("#themeToggle"),
  searchInput: document.querySelector("#searchInput"),
  languageFilter: document.querySelector("#languageFilter"),
  originFilter: document.querySelector("#originFilter"),
  kindFilter: document.querySelector("#kindFilter"),
  statusFilter: document.querySelector("#statusFilter"),
  sourceFilter: document.querySelector("#sourceFilter"),
  sortFilter: document.querySelector("#sortFilter"),
  filterPanel: document.querySelector("#filterPanel"),
  clearFiltersButton: document.querySelector("#clearFiltersButton"),
  activeFilterCount: document.querySelector("#activeFilterCount"),
  resultStatus: document.querySelector("#resultStatus"),
  termList: document.querySelector("#termList"),
  detail: document.querySelector("#detail"),
  previousPageButton: document.querySelector("#previousPageButton"),
  nextPageButton: document.querySelector("#nextPageButton"),
  pageStatus: document.querySelector("#pageStatus"),
  termDialog: document.querySelector("#termDialog"),
  termDialogTitle: document.querySelector("#termDialogTitle"),
  termForm: document.querySelector("#termForm"),
  termFormError: document.querySelector("#termFormError"),
  saveTermButton: document.querySelector("#saveTermButton"),
  cancelTermButton: document.querySelector("#cancelTermButton"),
  lookupQuery: document.querySelector("#lookupQuery"),
  lookupButton: document.querySelector("#lookupButton"),
  lookupStatus: document.querySelector("#lookupStatus"),
  lookupResults: document.querySelector("#lookupResults"),
  collectionsButton: document.querySelector("#collectionsButton"),
  collectionsCount: document.querySelector("#collectionsCount"),
  collectionsDialog: document.querySelector("#collectionsDialog"),
  collectionsHint: document.querySelector("#collectionsHint"),
  collectionsList: document.querySelector("#collectionsList"),
  collectionsError: document.querySelector("#collectionsError"),
  newCollectionName: document.querySelector("#newCollectionName"),
  createCollectionButton: document.querySelector("#createCollectionButton"),
  closeCollectionsButton: document.querySelector("#closeCollectionsButton"),
  deleteDialog: document.querySelector("#deleteDialog"),
  deleteForm: document.querySelector("#deleteForm"),
  deleteMessage: document.querySelector("#deleteMessage"),
  cancelDeleteButton: document.querySelector("#cancelDeleteButton"),
  snackbar: document.querySelector("#snackbar")
};

const labels = {
  kinds: {
    article: "Articulo",
    reference: "Referencia",
    query: "Consulta",
    invalid_source: "Fuente invalida"
  },
  statuses: {
    seed: "Semilla",
    enriched: "Enriquecido",
    reviewed: "Revisado",
    archived: "Archivado"
  },
  sources: {
    wikipedia: "Wikipedia",
    wiktionary: "Wiktionary",
    web: "Web",
    manual: "Manual",
    none: "Sin fuente"
  },
  origins: {
    package: "Paquete",
    personal: "Personal"
  }
};

async function api(path, options = {}) {
  const request = { ...options, headers: { ...(options.headers || {}) } };
  if (request.body) {
    request.headers["Content-Type"] = "application/json";
  }
  const response = await fetch(path, request);
  let payload = {};
  try {
    payload = await response.json();
  } catch (_error) {
    payload = {};
  }
  if (!response.ok) {
    const error = new Error(payload.message || `Error HTTP ${response.status}`);
    error.payload = payload;
    error.status = response.status;
    throw error;
  }
  return payload;
}

function escapeHtml(value = "") {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;"
  })[character]);
}

function safeExternalUrl(value) {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) ? url.href : "";
  } catch (_error) {
    return "";
  }
}

function hostFromUrl(value) {
  try {
    return new URL(value).hostname.replace(/^www\./, "");
  } catch (_error) {
    return "";
  }
}

function formatNumber(value) {
  return new Intl.NumberFormat("es-AR").format(value || 0);
}

function labelFor(group, value) {
  return labels[group]?.[value] || value || "Sin definir";
}

function renderStats(data) {
  state.stats = data;
  elements.libraryCount.textContent = formatNumber(data.terms);
  elements.personalCount.textContent = `${formatNumber(data.personal_terms)} personales`;
  elements.packageLabel.textContent = data.package?.package_version
    ? `Paquete ${data.package.package_version}`
    : "Archivo local";
  elements.stats.innerHTML = `
    <div><dt>Paquete</dt><dd>${formatNumber(data.package_terms)}</dd></div>
    <div><dt>Personales</dt><dd>${formatNumber(data.personal_terms)}</dd></div>
    <div><dt>Fuentes</dt><dd>${formatNumber(data.sources)}</dd></div>
    <div><dt>Relaciones</dt><dd>${formatNumber(data.relations)}</dd></div>
  `;
}

function populateSelect(select, items, group, defaultLabel) {
  const current = select.value;
  select.innerHTML = `<option value="">${escapeHtml(defaultLabel)}</option>` + items
    .map((item) => {
      const label = group === "languages"
        ? item.value.toUpperCase()
        : labelFor(group, item.value);
      return `<option value="${escapeHtml(item.value)}">${escapeHtml(label)} (${formatNumber(item.count)})</option>`;
    })
    .join("");
  if ([...select.options].some((option) => option.value === current)) {
    select.value = current;
  }
}

function renderFacets(facets) {
  populateSelect(elements.languageFilter, facets.languages || [], "languages", "Todos");
  populateSelect(elements.kindFilter, facets.kinds || [], "kinds", "Todos");
  populateSelect(elements.statusFilter, facets.statuses || [], "statuses", "Todos");
  populateSelect(elements.sourceFilter, facets.sources || [], "sources", "Todas");

  const originCounts = Object.fromEntries(
    (facets.origins || []).map((item) => [item.value, item.count])
  );
  [...elements.originFilter.options].forEach((option) => {
    if (option.value) {
      option.textContent = `${labelFor("origins", option.value)} (${formatNumber(originCounts[option.value])})`;
    }
  });
}

function activeFilterValues() {
  return [
    elements.languageFilter.value,
    elements.originFilter.value,
    elements.kindFilter.value,
    elements.statusFilter.value,
    elements.sourceFilter.value
  ].filter(Boolean);
}

function renderFilterCount() {
  const count = activeFilterValues().length;
  elements.activeFilterCount.textContent = count
    ? `${count} ${count === 1 ? "filtro" : "filtros"}`
    : "Sin filtros";
}

function buildCatalogQuery() {
  const query = new URLSearchParams({
    limit: String(PAGE_SIZE),
    offset: String(state.offset),
    sort: elements.sortFilter.value
  });
  const values = {
    search: elements.searchInput.value.trim(),
    language: elements.languageFilter.value,
    origin: elements.originFilter.value,
    kind: elements.kindFilter.value,
    status: elements.statusFilter.value,
    source: elements.sourceFilter.value
  };
  Object.entries(values).forEach(([key, value]) => {
    if (value) {
      query.set(key, value);
    }
  });
  return query;
}

function termSubtitle(term) {
  if (term.summary) {
    return term.summary;
  }
  if (term.source_url) {
    return hostFromUrl(term.source_url);
  }
  return term.origin === "personal" ? "Registro personal" : "Entrada semilla";
}

function renderTermList() {
  if (!state.terms.length) {
    elements.termList.innerHTML = `
      <div class="empty-list">
        <strong>No encontramos registros</strong>
        <p>Proba con otros terminos o limpia los filtros activos.</p>
      </div>
    `;
    return;
  }
  elements.termList.innerHTML = state.terms.map((term) => `
    <button
      class="term-row ${term.slug === state.activeSlug ? "active" : ""}"
      data-slug="${escapeHtml(term.slug)}"
      type="button"
    >
      <span class="term-row-main">
        <strong>${escapeHtml(term.title)}</strong>
        <span>${escapeHtml(termSubtitle(term))}</span>
      </span>
      <span class="term-row-meta">
        <span>${escapeHtml((term.language || "und").toUpperCase())}</span>
        <span class="origin-mark ${escapeHtml(term.origin)}">${escapeHtml(labelFor("origins", term.origin))}</span>
      </span>
    </button>
  `).join("");
}

function renderPagination() {
  const first = state.total ? state.offset + 1 : 0;
  const last = Math.min(state.offset + state.terms.length, state.total);
  const page = Math.floor(state.offset / PAGE_SIZE) + 1;
  const pages = Math.max(1, Math.ceil(state.total / PAGE_SIZE));
  elements.resultStatus.textContent = `${formatNumber(first)}-${formatNumber(last)} de ${formatNumber(state.total)} registros`;
  elements.pageStatus.textContent = `Pagina ${page} de ${pages}`;
  elements.previousPageButton.disabled = state.offset === 0;
  elements.nextPageButton.disabled = state.offset + PAGE_SIZE >= state.total;
}

function renderLoadingList() {
  elements.resultStatus.textContent = "Consultando archivo...";
  elements.termList.innerHTML = Array.from({ length: 7 }, () => `
    <div class="loading-row"><span></span><span></span></div>
  `).join("");
}

async function loadCatalog({ selectFirst = true } = {}) {
  const requestId = ++state.requestId;
  renderLoadingList();
  renderFilterCount();
  const data = await api(`/api/terms?${buildCatalogQuery()}`);
  if (requestId !== state.requestId) {
    return;
  }
  state.terms = data.items || [];
  state.total = data.total || 0;
  renderTermList();
  renderPagination();

  const activeVisible = state.terms.some((term) => term.slug === state.activeSlug);
  if (selectFirst && !activeVisible && state.terms[0]) {
    await selectTerm(state.terms[0].slug, { scroll: false });
  } else if (!state.terms.length) {
    state.activeSlug = null;
    state.activeTerm = null;
    renderEmptyDetail();
  }
}

function renderEmptyDetail() {
  elements.detail.innerHTML = `
    <div class="empty-state">
      <h2>Sin registro activo</h2>
      <p>Selecciona un termino o agrega uno a tu archivo personal.</p>
    </div>
  `;
}

function chipList(items, className = "") {
  return (items || []).map(
    (item) => `<span class="chip ${className}">${escapeHtml(item)}</span>`
  ).join("");
}

function renderSources(term) {
  const sources = term.sources?.length
    ? term.sources
    : term.source_url
      ? [{ source_kind: term.source_kind, canonical_url: term.source_url, host: hostFromUrl(term.source_url) }]
      : [];
  if (!sources.length) {
    return '<p class="quiet">Este registro no tiene una fuente vinculada.</p>';
  }
  return sources.map((source) => {
    const href = safeExternalUrl(source.canonical_url || source.url);
    const host = source.host || hostFromUrl(href) || "Fuente";
    return `
      <div class="source-row">
        <span>${escapeHtml(labelFor("sources", source.source_kind))}</span>
        ${href
          ? `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(host)}</a>`
          : `<strong>${escapeHtml(host)}</strong>`}
      </div>
    `;
  }).join("");
}

function renderRelations(related) {
  if (!related.length) {
    return '<p class="quiet">No hay relaciones explicitas para este registro.</p>';
  }
  return related.map((term) => `
    <button class="relation-row" data-slug="${escapeHtml(term.slug)}" type="button">
      <span>
        <strong>${escapeHtml(term.title)}</strong>
        <small>${escapeHtml(term.relation_type || "related_to")}</small>
      </span>
      <span>${escapeHtml(term.origin || "curated")}</span>
    </button>
  `).join("");
}

function renderDetail(term, related) {
  state.activeTerm = term;
  const registryId = term.display_id || `#${String(term.id || 0).padStart(4, "0")}`;
  const summary = term.summary || "Referencia catalogada; contenido pendiente de enriquecimiento.";
  const content = term.content
    ? `<p class="record-content">${escapeHtml(term.content)}</p>`
    : '<p class="quiet">La identidad y la procedencia estan disponibles, pero este paquete todavia no incluye el cuerpo del articulo.</p>';
  const notes = (term.notes || []).length
    ? `<section><h3>Notas privadas</h3><div class="note-block">${term.notes.map((note) => `<p>${escapeHtml(note)}</p>`).join("")}</div></section>`
    : "";
  // Agrupar en colecciones aplica a cualquier termino, no solo a los propios: el sentido es
  // juntar lo del paquete con lo tuyo bajo un mismo tema.
  const editActions = term.editable
    ? `<button data-action="edit" type="button">Editar</button>
       <button data-action="delete" class="danger-text" type="button">Eliminar</button>`
    : "";
  const actions = `<div class="record-actions">
       <button data-action="collections" type="button">Colecciones</button>
       ${editActions}
     </div>`;

  elements.detail.innerHTML = `
    <article class="record">
      <header class="record-header">
        <div class="record-title-row">
          <h2>${escapeHtml(term.title)}</h2>
          ${actions}
        </div>
        <p class="record-summary">${escapeHtml(summary)}</p>
        <div class="record-badges">
          <span class="registry-id">${escapeHtml(registryId)}</span>
          <span class="status-mark ${escapeHtml(term.status || "seed")}">${escapeHtml(labelFor("statuses", term.status || "seed"))}</span>
          <span class="origin-mark ${escapeHtml(term.origin)}">${escapeHtml(labelFor("origins", term.origin))}</span>
        </div>
        <div class="chip-row">${chipList(term.categories, "category")}${chipList(term.tags)}</div>
      </header>

      <dl class="record-meta">
        <div><dt>Idioma</dt><dd>${escapeHtml((term.language || "und").toUpperCase())}</dd></div>
        <div><dt>Tipo</dt><dd>${escapeHtml(labelFor("kinds", term.kind || "reference"))}</dd></div>
        <div><dt>Apariciones</dt><dd>${formatNumber(term.occurrence_count || 1)}</dd></div>
        <div><dt>Revision</dt><dd>${formatNumber(term.revision || 1)}</dd></div>
        <div><dt>Origen</dt><dd>${escapeHtml(labelFor("origins", term.origin))}</dd></div>
      </dl>

      <div class="record-sections">
        <section>
          <h3>${term.content ? "Contenido" : "Contenido pendiente"}</h3>
          ${content}
        </section>
        <section>
          <h3>Fuentes</h3>
          <div class="source-list">${renderSources(term)}</div>
        </section>
        ${notes}
        <section>
          <h3>Relaciones</h3>
          <div class="relation-list">${renderRelations(related)}</div>
        </section>
      </div>
    </article>
  `;
}

async function selectTerm(slug, { scroll = true } = {}) {
  state.activeSlug = slug;
  renderTermList();
  elements.detail.innerHTML = '<div class="empty-state"><p>Abriendo registro...</p></div>';
  const [term, related] = await Promise.all([
    api(`/api/terms/${encodeURIComponent(slug)}`),
    api(`/api/terms/${encodeURIComponent(slug)}/related`)
  ]);
  renderDetail(term, related.items || []);
  if (scroll && window.matchMedia("(max-width: 720px)").matches) {
    elements.detail.scrollIntoView({ behavior: "smooth", block: "start" });
  }
}

async function jumpTo(endpoint) {
  const term = await api(endpoint);
  if (term.slug) {
    state.activeSlug = term.slug;
    renderTermList();
    await selectTerm(term.slug);
  }
}

function formField(name) {
  return elements.termForm.elements.namedItem(name);
}

function setFormValue(name, value) {
  formField(name).value = value ?? "";
}

// --- Colecciones ---
// El mismo dialogo sirve para administrarlas y para asignar un termino: cuando se abre desde una
// ficha, cada fila trae una casilla; cuando se abre del menu lateral, cada fila navega.
const collectionsState = { target: null, items: [], memberOf: new Set() };

async function refreshCollectionsCount() {
  try {
    const payload = await api("/api/collections");
    collectionsState.items = payload.items || [];
    elements.collectionsCount.textContent = formatNumber(collectionsState.items.length);
  } catch (_error) {
    elements.collectionsCount.textContent = "0";
  }
}

async function openCollections(target = null) {
  collectionsState.target = target;
  elements.collectionsError.textContent = "";
  elements.newCollectionName.value = "";
  elements.collectionsHint.textContent = target
    ? `Marca las colecciones que agrupan "${target.title}".`
    : "Abri una para ver los terminos que agrupa.";
  await loadCollections();
  elements.collectionsDialog.showModal();
}

async function loadCollections() {
  try {
    const payload = await api("/api/collections");
    collectionsState.items = payload.items || [];
    elements.collectionsCount.textContent = formatNumber(collectionsState.items.length);

    if (collectionsState.target) {
      const term = collectionsState.target;
      const memberships = await Promise.all(
        collectionsState.items.map(async (collection) => {
          const detail = await api(`/api/collections/${encodeURIComponent(collection.uid)}`);
          const has = (detail.items || []).some(
            (item) => item.slug === term.slug && item.origin === term.origin
          );
          return has ? collection.uid : null;
        })
      );
      collectionsState.memberOf = new Set(memberships.filter(Boolean));
    }
    renderCollections();
  } catch (error) {
    elements.collectionsError.textContent = error.message;
  }
}

function renderCollections() {
  if (!collectionsState.items.length) {
    elements.collectionsList.replaceChildren();
    return;
  }
  const nodes = collectionsState.items.map((collection) => {
    const entry = document.createElement("li");
    entry.className = "collection-row";

    if (collectionsState.target) {
      const label = document.createElement("label");
      const box = document.createElement("input");
      box.type = "checkbox";
      box.checked = collectionsState.memberOf.has(collection.uid);
      box.addEventListener("change", () => toggleMembership(collection.uid, box.checked));
      label.append(box, document.createTextNode(` ${collection.name}`));
      entry.append(label);
    } else {
      const open = document.createElement("button");
      open.type = "button";
      open.className = "collection-open";
      open.textContent = collection.name;
      open.addEventListener("click", () => showCollection(collection));
      entry.append(open);
    }

    const count = document.createElement("span");
    count.className = "collection-count";
    count.textContent = formatNumber(collection.term_count);
    entry.append(count);

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "danger-text";
    remove.textContent = "Eliminar";
    remove.addEventListener("click", () => deleteCollection(collection));
    entry.append(remove);

    return entry;
  });
  elements.collectionsList.replaceChildren(...nodes);
}

async function toggleMembership(uid, member) {
  const term = collectionsState.target;
  if (!term) return;
  try {
    if (member) {
      await api(`/api/collections/${encodeURIComponent(uid)}/terms`, {
        method: "POST",
        body: JSON.stringify({ slug: term.slug, origin: term.origin }),
      });
    } else {
      await api(
        `/api/collections/${encodeURIComponent(uid)}/terms/${encodeURIComponent(term.slug)}` +
          `?origin=${encodeURIComponent(term.origin)}`,
        { method: "DELETE" }
      );
    }
    await loadCollections();
  } catch (error) {
    elements.collectionsError.textContent = error.message;
  }
}

async function createCollection() {
  const name = elements.newCollectionName.value.trim();
  if (!name) {
    elements.collectionsError.textContent = "Escribi un nombre.";
    return;
  }
  try {
    const created = await api("/api/collections", {
      method: "POST",
      body: JSON.stringify({ name }),
    });
    elements.newCollectionName.value = "";
    elements.collectionsError.textContent = "";
    // Crear desde una ficha ademas agrega el termino: es lo que uno quiso al escribir el nombre.
    if (collectionsState.target) {
      await toggleMembership(created.uid, true);
    } else {
      await loadCollections();
    }
  } catch (error) {
    elements.collectionsError.textContent = error.message;
  }
}

async function deleteCollection(collection) {
  try {
    await api(`/api/collections/${encodeURIComponent(collection.uid)}`, { method: "DELETE" });
    await loadCollections();
  } catch (error) {
    elements.collectionsError.textContent = error.message;
  }
}

/** Muestra los terminos de una coleccion en la lista principal, como si fueran un resultado. */
async function showCollection(collection) {
  try {
    const detail = await api(`/api/collections/${encodeURIComponent(collection.uid)}`);
    elements.collectionsDialog.close();
    state.terms = detail.items || [];
    state.total = state.terms.length;
    state.offset = 0;
    renderTermList();
    elements.pageStatus.textContent = `Coleccion "${collection.name}": ${formatNumber(state.terms.length)}`;
    if (state.terms.length) {
      await selectTerm(state.terms[0].slug);
    }
  } catch (error) {
    elements.collectionsError.textContent = error.message;
  }
}

function resetLookup(query = "") {
  elements.lookupQuery.value = query;
  elements.lookupStatus.textContent = "";
  elements.lookupResults.replaceChildren();
}

/**
 * Busca en una fuente externa (ADR 0003) a traves del backend, que es quien aplica la allowlist
 * de hosts y los limites. Es siempre opcional: cargar los campos a mano sigue funcionando igual,
 * y es lo que queda cuando no hay red.
 */
async function runLookup() {
  const query = elements.lookupQuery.value.trim();
  if (!query) {
    return;
  }
  elements.lookupResults.replaceChildren();
  elements.lookupStatus.textContent = "Buscando...";
  elements.lookupButton.disabled = true;
  try {
    const language = formField("language").value || "es";
    const payload = await api(
      `/api/knowledge/search?q=${encodeURIComponent(query)}&language=${encodeURIComponent(language)}`
    );
    renderLookupResults(payload.items || []);
  } catch (error) {
    elements.lookupStatus.textContent =
      error.status === 504
        ? "No se pudo contactar la fuente. Podes cargar el termino a mano igual."
        : error.message;
  } finally {
    elements.lookupButton.disabled = false;
  }
}

function renderLookupResults(items) {
  if (!items.length) {
    elements.lookupStatus.textContent = "Sin resultados.";
    return;
  }
  elements.lookupStatus.textContent = "";
  const nodes = items.map((item) => {
    const entry = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "lookup-result";
    button.innerHTML = `
      <span class="lookup-result-title">${escapeHtml(item.title)}</span>
      ${item.description ? `<span class="lookup-result-note">${escapeHtml(item.description)}</span>` : ""}
    `;
    button.addEventListener("click", () => importLookupResult(item));
    entry.append(button);
    return entry;
  });
  elements.lookupResults.replaceChildren(...nodes);
}

/**
 * Completa el formulario con el articulo elegido. Solo se pisan los campos que la fuente puede
 * llenar; categorias, etiquetas y notas son anotaciones propias del usuario y se conservan.
 */
async function importLookupResult(item) {
  elements.lookupStatus.textContent = "Trayendo el articulo...";
  try {
    const article = await api(
      `/api/knowledge/article?id=${encodeURIComponent(item.external_id)}` +
        `&language=${encodeURIComponent(item.language || "es")}`
    );
    setFormValue("title", article.title);
    setFormValue("language", article.language);
    setFormValue("summary", article.summary);
    setFormValue("content", article.content);
    setFormValue("source_url", article.source_url);
    resetLookup();
    elements.termFormError.textContent = "";
  } catch (error) {
    elements.lookupStatus.textContent = error.message;
  }
}

function openTermDialog(term = null) {
  state.editingSlug = term?.slug || null;
  elements.termForm.reset();
  elements.termFormError.textContent = "";
  resetLookup();
  elements.termDialogTitle.textContent = term ? "Editar termino" : "Nuevo termino";
  elements.saveTermButton.textContent = term ? "Guardar cambios" : "Guardar termino";

  if (term) {
    setFormValue("title", term.title);
    setFormValue("language", term.language || "und");
    setFormValue("kind", term.kind || "reference");
    setFormValue("status", term.status || "seed");
    setFormValue("summary", term.summary);
    setFormValue("content", term.content);
    setFormValue("source_url", term.source_url);
    setFormValue("categories", (term.categories || []).join(", "));
    setFormValue("tags", (term.tags || []).join(", "));
    setFormValue("notes", (term.notes || []).join("\n"));
  } else {
    setFormValue("language", "es");
    setFormValue("kind", "reference");
    setFormValue("status", "seed");
  }
  elements.termDialog.showModal();
  window.setTimeout(() => formField("title").focus(), 0);
}

function formPayload() {
  return {
    title: formField("title").value,
    language: formField("language").value,
    kind: formField("kind").value,
    status: formField("status").value,
    summary: formField("summary").value,
    content: formField("content").value,
    source_url: formField("source_url").value,
    categories: formField("categories").value,
    tags: formField("tags").value,
    notes: formField("notes").value
  };
}

async function refreshMetadata() {
  const [stats, facets] = await Promise.all([api("/api/stats"), api("/api/facets")]);
  renderStats(stats);
  renderFacets(facets);
}

async function saveTerm(event) {
  event.preventDefault();
  elements.termFormError.textContent = "";
  elements.saveTermButton.disabled = true;
  elements.saveTermButton.textContent = "Guardando...";
  try {
    const editing = Boolean(state.editingSlug);
    const path = editing
      ? `/api/terms/${encodeURIComponent(state.editingSlug)}`
      : "/api/terms";
    const term = await api(path, {
      method: editing ? "PUT" : "POST",
      body: JSON.stringify(formPayload())
    });
    elements.termDialog.close();
    state.offset = 0;
    await refreshMetadata();
    await loadCatalog({ selectFirst: false });
    await selectTerm(term.slug, { scroll: false });
    showSnackbar(editing ? "Cambios guardados." : "Termino agregado al archivo personal.");
  } catch (error) {
    elements.termFormError.textContent = error.message;
    const existingSlug = error.payload?.details?.existing_slug;
    if (existingSlug) {
      elements.termFormError.textContent += " Abri el registro existente desde el indice.";
    }
  } finally {
    elements.saveTermButton.disabled = false;
    elements.saveTermButton.textContent = state.editingSlug ? "Guardar cambios" : "Guardar termino";
  }
}

function openDeleteDialog() {
  if (!state.activeTerm?.editable) {
    return;
  }
  elements.deleteMessage.textContent = `Se eliminara "${state.activeTerm.title}" de tu archivo personal.`;
  elements.deleteDialog.showModal();
}

async function deleteTerm(event) {
  event.preventDefault();
  const slug = state.activeTerm?.slug;
  if (!slug) {
    return;
  }
  try {
    await api(`/api/terms/${encodeURIComponent(slug)}`, { method: "DELETE" });
    elements.deleteDialog.close();
    state.activeSlug = null;
    state.activeTerm = null;
    await refreshMetadata();
    await loadCatalog();
    showSnackbar("Termino personal eliminado.");
  } catch (error) {
    elements.deleteDialog.close();
    showSnackbar(error.message, true);
  }
}

function showSnackbar(message, error = false) {
  window.clearTimeout(state.snackbarTimer);
  elements.snackbar.textContent = message;
  elements.snackbar.classList.toggle("error", error);
  elements.snackbar.classList.add("visible");
  state.snackbarTimer = window.setTimeout(() => {
    elements.snackbar.classList.remove("visible");
  }, 3200);
}

function resetFilters() {
  elements.languageFilter.value = "";
  elements.originFilter.value = "";
  elements.kindFilter.value = "";
  elements.statusFilter.value = "";
  elements.sourceFilter.value = "";
  elements.sortFilter.value = "title_asc";
  state.offset = 0;
  loadCatalog().catch(showFatalError);
}

function applyTheme(dark) {
  const theme = dark ? "dark" : "light";
  document.documentElement.dataset.theme = theme;
  window.localStorage.setItem("lexidex-theme", theme);
  document.querySelector('meta[name="theme-color"]').content = dark ? "#0b1110" : "#14211e";
}

function showFatalError(error) {
  elements.termList.innerHTML = '<div class="empty-list"><strong>No pudimos leer el archivo</strong><p>Revisa que el servidor local siga activo y recarga la pagina.</p></div>';
  elements.detail.innerHTML = '<div class="empty-state error"><h2>Conexion interrumpida</h2><p>Lexidex no pudo acceder a la API local.</p></div>';
  showSnackbar(error.message || "No se pudo completar la operacion.", true);
  console.error(error);
}

elements.termList.addEventListener("click", (event) => {
  const button = event.target.closest("[data-slug]");
  if (button) {
    selectTerm(button.dataset.slug).catch(showFatalError);
  }
});

elements.detail.addEventListener("click", (event) => {
  const relation = event.target.closest("[data-slug]");
  if (relation) {
    selectTerm(relation.dataset.slug).catch(showFatalError);
    return;
  }
  const action = event.target.closest("[data-action]")?.dataset.action;
  if (action === "edit" && state.activeTerm?.editable) {
    openTermDialog(state.activeTerm);
  } else if (action === "delete") {
    openDeleteDialog();
  } else if (action === "collections" && state.activeTerm) {
    openCollections(state.activeTerm).catch(showFatalError);
  }
});

elements.collectionsButton.addEventListener("click", () => openCollections().catch(showFatalError));
elements.closeCollectionsButton.addEventListener("click", () => elements.collectionsDialog.close());
elements.createCollectionButton.addEventListener("click", () => createCollection().catch(showFatalError));
elements.newCollectionName.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    createCollection().catch(showFatalError);
  }
});

elements.searchInput.addEventListener("input", () => {
  window.clearTimeout(elements.searchInput.timer);
  elements.searchInput.timer = window.setTimeout(() => {
    state.offset = 0;
    loadCatalog().catch(showFatalError);
  }, 180);
});

[
  elements.languageFilter,
  elements.originFilter,
  elements.kindFilter,
  elements.statusFilter,
  elements.sourceFilter,
  elements.sortFilter
].forEach((control) => control.addEventListener("change", () => {
  state.offset = 0;
  loadCatalog().catch(showFatalError);
}));

elements.previousPageButton.addEventListener("click", () => {
  state.offset = Math.max(0, state.offset - PAGE_SIZE);
  loadCatalog().catch(showFatalError);
});

elements.nextPageButton.addEventListener("click", () => {
  if (state.offset + PAGE_SIZE < state.total) {
    state.offset += PAGE_SIZE;
    loadCatalog().catch(showFatalError);
  }
});

elements.addTermButton.addEventListener("click", () => openTermDialog());
elements.libraryButton.addEventListener("click", () => elements.searchInput.focus());
elements.dailyButton.addEventListener("click", () => jumpTo("/api/daily").catch(showFatalError));
elements.randomButton.addEventListener("click", () => jumpTo("/api/random").catch(showFatalError));
elements.clearFiltersButton.addEventListener("click", resetFilters);
elements.themeToggle.addEventListener("change", () => applyTheme(elements.themeToggle.checked));
elements.termForm.addEventListener("submit", saveTerm);
elements.cancelTermButton.addEventListener("click", () => elements.termDialog.close());
elements.lookupButton.addEventListener("click", runLookup);
elements.lookupQuery.addEventListener("keydown", (event) => {
  // El campo vive dentro del <form>, asi que Enter enviaria el formulario entero.
  if (event.key === "Enter") {
    event.preventDefault();
    runLookup();
  }
});
elements.deleteForm.addEventListener("submit", deleteTerm);
elements.cancelDeleteButton.addEventListener("click", () => elements.deleteDialog.close());

document.addEventListener("keydown", (event) => {
  const typing = ["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement?.tagName);
  if (event.key === "/" && !typing && !elements.termDialog.open) {
    event.preventDefault();
    elements.searchInput.focus();
  }
});

async function init() {
  elements.themeToggle.checked = document.documentElement.dataset.theme === "dark";
  if (window.matchMedia("(max-width: 720px)").matches) {
    elements.filterPanel.removeAttribute("open");
  }
  try {
    await refreshMetadata();
    await loadCatalog();
    await refreshCollectionsCount();
  } catch (error) {
    showFatalError(error);
  }
}

init();
