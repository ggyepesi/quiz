<script>
  import { onMount } from 'svelte';
  import { getDomains, getCoverage } from '$lib/api.js';

  let domains = $state([]);
  let type = $state('');
  let rows = $state([]);
  let loading = $state(false);
  let error = $state(null);

  const rank = { VIOLATION: 0, GAP: 1, OK: 2 };
  const sorted = $derived(
    [...rows].sort(
      (a, b) => (rank[a.verdict] - rank[b.verdict]) || (a.present / a.total - b.present / b.total)
    )
  );
  const summary = $derived.by(() => {
    const s = { VIOLATION: 0, GAP: 0, OK: 0 };
    for (const r of rows) s[r.verdict] = (s[r.verdict] ?? 0) + 1;
    return s;
  });

  onMount(async () => {
    try {
      domains = (await getDomains()) ?? [];
      const first = domains.find((d) => d.types?.length);
      if (first) await selectType(first.types[0]);
    } catch (e) {
      error = 'Cannot reach the API. Is QuizableServerMain running on :7070?';
    }
  });

  async function selectType(t) {
    type = t;
    loading = true;
    rows = (await getCoverage(t)) ?? [];
    loading = false;
  }

  const pct = (r) => (r.total ? Math.round((1000 * r.present) / r.total) / 10 : 0);
</script>

<div class="page">
  <header>
    <a class="back" href="/">← Explorer</a>
    <h1>Data quality</h1>
    <div class="picker">
      {#each domains as d}
        <label class="dom">
          <span class="dn">{d.name}</span>
          <select
            value={d.types.includes(type) ? type : ''}
            onchange={(e) => e.currentTarget.value && selectType(e.currentTarget.value)}
          >
            {#if !d.types.includes(type)}<option value="" disabled>Class…</option>{/if}
            {#each d.types as t}<option value={t}>{t}</option>{/each}
          </select>
        </label>
      {/each}
    </div>
  </header>

  {#if error}<div class="banner">{error}</div>{/if}

  <div class="body">
    {#if loading}
      <p class="hint">Loading…</p>
    {:else if rows.length}
      <div class="chips">
        <span class="chip VIOLATION">{summary.VIOLATION} violation{summary.VIOLATION === 1 ? '' : 's'}</span>
        <span class="chip GAP">{summary.GAP} gap{summary.GAP === 1 ? '' : 's'}</span>
        <span class="chip OK">{summary.OK} ok</span>
      </div>
      <table>
        <thead>
          <tr>
            <th>Field</th><th>Coverage</th>
            <th class="num">Present</th><th class="num">Missing</th>
            <th>Expect</th><th>Verdict</th>
          </tr>
        </thead>
        <tbody>
          {#each sorted as r}
            <tr>
              <td class="fld">
                <span class="nm">{r.label}</span>
                {#if r.path !== r.label}<span class="path">{r.path}</span>{/if}
              </td>
              <td class="cov">
                <div class="bar"><div class="fill {r.verdict}" style="width:{pct(r)}%"></div></div>
                <span class="pctn">{pct(r)}%</span>
              </td>
              <td class="num">{r.present.toLocaleString()}</td>
              <td class="num" class:miss={r.total - r.present > 0}>{(r.total - r.present).toLocaleString()}</td>
              <td class="exp">{r.expectation === 'NONE' ? '—' : r.expectation.toLowerCase()}</td>
              <td><span class="badge {r.verdict}">{r.verdict.toLowerCase()}</span></td>
            </tr>
          {/each}
        </tbody>
      </table>
      <p class="foot">
        Mark a field <b>expected</b> or <b>required</b> in the modelbuilder to turn its gaps into
        <span class="badge GAP">gap</span> / <span class="badge VIOLATION">violation</span> verdicts.
      </p>
    {:else}
      <p class="hint">No fields to report.</p>
    {/if}
  </div>
</div>

<style>
  .page { max-width: 900px; margin: 0 auto; padding: 22px 24px; }
  header { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
  .back { color: var(--accent); font-weight: 600; }
  h1 { font-size: 20px; font-weight: 650; }
  .picker { margin-left: auto; display: flex; gap: 12px; flex-wrap: wrap; }
  .dom { display: flex; align-items: center; gap: 5px; }
  .dn { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--muted); }
  .picker select {
    padding: 4px 8px; border-radius: var(--radius-sm);
    border: 1px solid var(--line-strong); background: var(--chip-bg); color: var(--fg);
  }

  .banner { background: #fff4f4; color: #b42318; padding: 8px 12px; border-radius: var(--radius-sm); }
  .hint { color: var(--muted); }

  .chips { display: flex; gap: 8px; margin-bottom: 12px; }
  .chip { padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; }
  .chip.VIOLATION { background: #fdecea; color: #b42318; }
  .chip.GAP { background: #fef6e7; color: #b7791f; }
  .chip.OK { background: var(--chip-bg); color: var(--muted); }

  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { text-align: left; padding: 7px 10px; border-bottom: 1px solid var(--line); }
  th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.03em; color: var(--muted); }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  td.miss { color: #b7791f; font-weight: 600; }

  .fld { display: flex; flex-direction: column; }
  .fld .nm { font-weight: 550; }
  .fld .path { font-size: 11px; color: var(--faint); }

  .cov { display: flex; align-items: center; gap: 8px; min-width: 160px; }
  .bar { flex: 1; height: 7px; background: var(--line); border-radius: 999px; overflow: hidden; }
  .fill { height: 100%; border-radius: 999px; background: var(--accent); }
  .fill.GAP { background: #d69e2e; }
  .fill.VIOLATION { background: #e0483a; }
  .fill.OK { background: #3fa46a; }
  .pctn { width: 44px; text-align: right; color: var(--muted); font-variant-numeric: tabular-nums; }
  .exp { color: var(--muted); }

  .badge { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 650; text-transform: uppercase; letter-spacing: 0.02em; }
  .badge.OK { background: var(--chip-bg); color: var(--muted); }
  .badge.GAP { background: #fef6e7; color: #b7791f; }
  .badge.VIOLATION { background: #fdecea; color: #b42318; }

  .foot { margin-top: 14px; font-size: 12px; color: var(--muted); }
  .foot .badge { text-transform: none; }
</style>
