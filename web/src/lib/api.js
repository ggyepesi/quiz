// Base URL of the Java QuizableHttpServer.
// Default: same origin ('') — calls go to /api/... and the Vite dev server
// proxies them to localhost:7070 (see vite.config.js). This way the API and
// images travel through whatever serves the page (localhost, LAN IP, or a
// single ngrok tunnel) with no CORS and no second port to expose.
// Override with VITE_API only if the API is reached directly elsewhere.
function apiBase() {
  return import.meta.env.VITE_API ?? '';
}

/** Resolve a server-relative path (e.g. an image URL) against the API base. */
export function assetUrl(path) {
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
