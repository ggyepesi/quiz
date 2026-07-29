// Base URL of the Java ViewableHttpServer.
// Default: same origin ('') — calls go to /api/... and the Vite dev server
// proxies them to localhost:7070 (see vite.config.js). This way the API and
// images travel through whatever serves the page (localhost, LAN IP, or a
// single ngrok tunnel) with no CORS and no second port to expose.
// Override with VITE_API only if the API is reached directly elsewhere.
function apiBase() {
  return import.meta.env.VITE_API ?? '';
}

/** Resolve a server-relative path (e.g. an image URL) against the API base.
 *  Absolute URLs (e.g. a Wikimedia Commons chart) are returned untouched. */
export function assetUrl(path) {
  if (/^https?:\/\//.test(path)) return path;
  return `${apiBase()}${path}`;
}

async function json(url) {
  const r = await fetch(url, {
    cache: 'no-store',
    headers: { 'ngrok-skip-browser-warning': '1' }
  });
  if (!r.ok) return null;
  return r.json();
}

/** @returns {Promise<string[]>} registered type names */
export function getTypes() {
  return json(`${apiBase()}/api/types`);
}

/**
 * Types grouped by domain: [{ name, types: [...] }]. Falls back to a single
 * "All" group on an older server that lacks /api/domains.
 * @returns {Promise<Array<{name:string,types:string[]}>>}
 */
export async function getDomains() {
  try {
    const d = await json(`${apiBase()}/api/domains`);
    if (Array.isArray(d) && d.length) return d;
  } catch (_) {
    /* fall through to flat list */
  }
  const types = (await getTypes()) ?? [];
  return types.length ? [{ name: 'All', types }] : [];
}

/** @returns {Promise<Array<{id:string,name:string,type:string}>>} shallow list.
 *  With `group` (a dimension bucket fullName), returns only that bucket's members. */
export function getList(type, group = '') {
  const p = new URLSearchParams({ type });
  if (group) p.set('group', group);
  return json(`${apiBase()}/api/viewables?${p}`);
}

/** The declared groupable dimensions for a type: [{ label, path, kind }]. */
export function getDimensions(type) {
  return json(`${apiBase()}/api/dimensions?type=${encodeURIComponent(type)}`);
}

/** Per-field coverage + verdict for a type: [{ label, path, present, total,
 *  expectation, verdict }] — the data-quality report. */
export function getCoverage(type) {
  return json(`${apiBase()}/api/coverage?type=${encodeURIComponent(type)}`);
}

/** @returns {Promise<object|null>} full ViewableView */
export function getViewable(type, id) {
  return json(`${apiBase()}/api/viewable/${encodeURIComponent(type)}/${encodeURIComponent(id)}`);
}

/** Fields of a type, or — with a dotted `path` — the fields available under a
 *  reference (e.g. path="namedAfter" → the target's fields), for deep config.
 *  @returns {Promise<Array<{name:string,kind:string,expandable:boolean}>>} */
export function getFields(type, path = '') {
  const p = new URLSearchParams({ type });
  if (path) p.set('path', path);
  return json(`${apiBase()}/api/fields?${p}`);
}

/** @returns {Promise<object|null>} the group tree, or null if the type has none */
export function getGroups(type) {
  return json(`${apiBase()}/api/groups?type=${encodeURIComponent(type)}`);
}

/** @returns {Promise<object|null>} a generated multiple-choice quiz */
export function getQuiz(type, { prompt = 'logo', ask = 'name', n = 10, group = '' } = {}) {
  const p = new URLSearchParams({ type, prompt, ask, n: String(n) });
  if (group) p.set('group', group);
  return json(`${apiBase()}/api/quiz?${p}`);
}

/** A stateless ordered-card deck. `order` is the comparable field path;
 *  prompt and answer are independent ViewConfig field selections. */
export function getOrderingQuiz(
  type,
  {
    prompt = 'name',
    answer = '',
    order,
    valueType = 'DATE',
    direction = 'ASCENDING',
    equalValues = 'EQUIVALENT',
    n = 10,
    group = ''
  }
) {
  const p = new URLSearchParams({
    type, prompt, answer, order, valueType, direction, equalValues, n: String(n)
  });
  if (group) p.set('group', group);
  return json(`${apiBase()}/api/ordering?${p}`);
}

/** @returns {Promise<object|null>} a matching game (prompts vs answers) */
export function getPairing(type, { prompt = 'logo', ask = 'name', n = 12, group = '' } = {}) {
  const p = new URLSearchParams({ type, prompt, ask, n: String(n) });
  if (group) p.set('group', group);
  return json(`${apiBase()}/api/pairing?${p}`);
}

// --- Model builder (password-gated) -------------------------------------

/** Base64 Basic-auth credential for the builder endpoints. */
export function basicCred(user, pass) {
  return btoa(`${user}:${pass}`);
}

/** @returns {Promise<{ok:boolean, status:number, data:any}>} */
async function builderGet(path, cred, asText = false) {
  const r = await fetch(`${apiBase()}${path}`, {
    cache: 'no-store',
    headers: { Authorization: `Basic ${cred}`, 'ngrok-skip-browser-warning': '1' }
  });
  if (!r.ok) return { ok: false, status: r.status, data: null };
  return { ok: true, status: r.status, data: asText ? await r.text() : await r.json() };
}

export const getBuilderModel = (cred) => builderGet('/api/builder/model', cred);
export const getBuilderRuleTree = (cred) => builderGet('/api/builder/ruletree', cred);
export const getBuilderSparql = (cred, depth = 0) =>
  builderGet(`/api/builder/sparql?depth=${depth}`, cred, true);
