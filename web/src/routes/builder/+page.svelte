<script>
  import {
    basicCred,
    getBuilderModel,
    getBuilderRuleTree,
    getBuilderSparql
  } from '$lib/api.js';

  let user = $state('builder');
  let pass = $state('');
  let cred = $state(null);

  let model = $state(null);
  let ruletree = $state('');
  let sparql = $state('');
  let depth = $state(0);

  let loading = $state(false);
  let error = $state(null);

  // Unwrap Jackson default-typed lists: ["java.util.ArrayList", [...]] -> [...].
  function arr(v) {
    if (Array.isArray(v) && v.length === 2 && typeof v[0] === 'string' && Array.isArray(v[1]))
      return v[1];
    return Array.isArray(v) ? v : [];
  }

  const rootClass = $derived(model?.rootClass ?? null);
  const fields = $derived(rootClass ? arr(rootClass.fields) : []);

  async function connect() {
    loading = true;
    error = null;
    const c = basicCred(user, pass);
    const m = await getBuilderModel(c);
    if (!m.ok) {
      error = m.status === 401 ? 'Wrong username or password.' : `Error (${m.status}).`;
      cred = null;
      loading = false;
      return;
    }
    cred = c;
    model = m.data;
    await loadDerived();
    loading = false;
  }

  async function loadDerived() {
    if (!cred) return;
    const [rt, sq] = await Promise.all([
      getBuilderRuleTree(cred),
      getBuilderSparql(cred, depth)
    ]);
    ruletree = rt.ok ? JSON.stringify(rt.data, null, 2) : '(failed to load)';
    sparql = sq.ok ? sq.data : '(failed to load)';
  }

  function fieldProp(f) {
    return f?.mapping?.propertyPid || '';
  }
</script>

<div class="wrap">
  <header>
    <a class="back" href="/">← Browse</a>
    <h1>Model builder <span class="tag">read-only</span></h1>
  </header>

  {#if !cred}
    <form class="login" onsubmit={(e) => { e.preventDefault(); connect(); }}>
      <p class="hint">This area is password-protected.</p>
      <label>Username <input bind:value={user} autocomplete="username" /></label>
      <label>Password <input type="password" bind:value={pass} autocomplete="current-password" /></label>
      <button class="primary" disabled={loading}>{loading ? 'Connecting…' : 'Connect'}</button>
      {#if error}<p class="error">{error}</p>{/if}
    </form>
  {:else}
    <div class="grid">
      <section class="card">
        <h2>{model?.name ?? 'Project'} — class <code>{rootClass?.className}</code></h2>
        <table>
          <thead>
            <tr><th>Field</th><th>Type</th><th>Shape</th><th>Property</th><th>Required</th></tr>
          </thead>
          <tbody>
            {#each fields as f}
              <tr>
                <td>{f.name}</td>
                <td>{f.type}</td>
                <td>{f.cardinality}</td>
                <td><code>{fieldProp(f)}</code> {f.mapping?.propertyLabel ?? ''}</td>
                <td>{f.required ? '✓' : ''}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </section>

      <section class="card">
        <div class="card-head">
          <h2>SPARQL preview</h2>
          <label class="depth">depth
            <input type="number" min="0" max="5" bind:value={depth} onchange={loadDerived} />
          </label>
        </div>
        <pre>{sparql}</pre>
      </section>

      <section class="card">
        <h2>Rule tree</h2>
        <pre>{ruletree}</pre>
      </section>
    </div>
  {/if}
</div>

<style>
  .wrap { max-width: 1000px; margin: 0 auto; padding: 20px 24px; }
  header { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; }
  .back { color: var(--accent); font-weight: 600; }
  h1 { font-size: 18px; font-weight: 650; }
  .tag {
    font-size: 11px; font-weight: 600; color: var(--muted);
    border: 1px solid var(--line); border-radius: 999px; padding: 1px 8px; margin-left: 6px;
  }

  .login {
    max-width: 320px; display: flex; flex-direction: column; gap: 10px;
    padding: 18px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--panel);
  }
  .login label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--muted); }
  .login input { padding: 8px 10px; border: 1px solid var(--line-strong); border-radius: var(--radius-sm); }

  .primary {
    padding: 8px 14px; border-radius: var(--radius-sm);
    background: var(--accent); color: #fff; font-weight: 600;
  }
  .primary:disabled { opacity: 0.6; }
  .error { color: #b42318; font-size: 13px; }
  .hint { color: var(--muted); font-size: 13px; }

  .grid { display: flex; flex-direction: column; gap: 16px; }
  .card { border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--panel); padding: 14px 16px; }
  .card-head { display: flex; align-items: center; justify-content: space-between; }
  .depth { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 6px; }
  .depth input { width: 56px; padding: 4px 6px; border: 1px solid var(--line-strong); border-radius: 6px; }
  h2 { font-size: 14px; font-weight: 600; margin-bottom: 10px; }

  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); }
  th { color: var(--muted); font-weight: 600; }
  code { background: var(--chip-bg); padding: 1px 5px; border-radius: 5px; font-size: 12px; }

  pre {
    background: var(--chip-bg); padding: 12px; border-radius: var(--radius-sm);
    overflow-x: auto; font-size: 12px; line-height: 1.45; max-height: 360px;
  }
</style>
