// Base URL of the Java QuizableHttpServer.
// Default: the same host that served this page, on port 7070 — so it works
// from localhost AND from a phone (Mac's LAN IP) with no config. Override
// explicitly with VITE_API if the API runs elsewhere.
function apiBase() {
  if (import.meta.env.VITE_API) return import.meta.env.VITE_API;
  if (typeof window !== 'undefined') {
    return `${location.protocol}//${location.hostname}:7070`;
  }
  return 'http://localhost:7070';
}

/** Resolve a server-relative path (e.g. an image URL) against the API base. */
export function assetUrl(path) {
  return `${apiBase()}${path}`;
}

async function json(url) {
  const r = await fetch(url, { cache: 'no-store' });
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

/** @returns {Promise<Array<{name:string,kind:string}>>} fields of a type */
export function getFields(type) {
  return json(`${apiBase()}/api/fields?type=${encodeURIComponent(type)}`);
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
