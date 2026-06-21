// Base URL of the Java QuizableHttpServer.
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

/** @returns {Promise<Array<{id:string,name:string,type:string}>>} shallow list */
export function getList(type) {
  return json(`${apiBase()}/api/quizables?type=${encodeURIComponent(type)}`);
}

/** @returns {Promise<object|null>} full QuizableView */
export function getQuizable(type, id) {
  return json(`${apiBase()}/api/quizable/${encodeURIComponent(type)}/${encodeURIComponent(id)}`);
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
