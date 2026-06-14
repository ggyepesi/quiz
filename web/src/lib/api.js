// Base URL of the Java QuizableHttpServer. Override with VITE_API in .env.
const BASE = import.meta.env.VITE_API ?? 'http://localhost:7070';

/** Resolve a server-relative path (e.g. an image URL) against the API base. */
export function assetUrl(path) {
  return `${BASE}${path}`;
}

async function json(url) {
  const r = await fetch(url);
  if (!r.ok) return null;
  return r.json();
}

/** @returns {Promise<string[]>} registered type names */
export function getTypes() {
  return json(`${BASE}/api/types`);
}

/** @returns {Promise<Array<{id:string,name:string,type:string}>>} shallow list */
export function getList(type) {
  return json(`${BASE}/api/quizables?type=${encodeURIComponent(type)}`);
}

/** @returns {Promise<object|null>} full QuizableView */
export function getQuizable(type, id) {
  return json(`${BASE}/api/quizable/${encodeURIComponent(type)}/${encodeURIComponent(id)}`);
}
