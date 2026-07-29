<script>
  import { onMount } from 'svelte';
  import { getDomains, getList, getViewable, getDimensions, getGroups } from '$lib/api.js';
  import ViewableCard from '$lib/ViewableCard.svelte';

  let domains = $state([]);
  let type = $state(null);
  let items = $state([]);
  let selected = $state(null);
  let selectedId = $state(null);
  let loadingList = $state(false);
  let loadingCard = $state(false);
  let error = $state(null);
  let q = $state('');

  // Live re-faceting: the declared dimensions + the chosen one's buckets.
  let dimensions = $state([]);
  let groups = $state(null);
  let groupBy = $state('');          // selected dimension label ('' = none)
  let activeBucket = $state(null);   // selected bucket fullName (null = all)

  const filtered = $derived(
    items.filter((i) => i.name.toLowerCase().includes(q.toLowerCase()))
  );

  // Buckets of the chosen dimension, read from the matching facet in the group tree.
  const buckets = $derived.by(() => {
    if (!groupBy || !groups?.children) return [];
    const facet = groups.children.find((c) => c.name === groupBy);
    return facet?.children ?? [];
  });

  onMount(async () => {
    try {
      domains = (await getDomains()) ?? [];
      const first = domains.find((d) => d.types?.length);
      if (first) await selectType(first.types[0]);
    } catch (e) {
      error = 'Cannot reach the API. Is ViewableServerMain running on :7070?';
    }
  });

  async function selectType(t) {
    type = t;
    selected = null;
    selectedId = null;
    q = '';
    groupBy = '';
    activeBucket = null;
    loadingList = true;
    const [list, dims, grp] = await Promise.all([
      getList(t),
      getDimensions(t).catch(() => []),
      getGroups(t).catch(() => null)
    ]);
    items = list ?? [];
    dimensions = dims ?? [];
    groups = grp;
    loadingList = false;
  }

  function onGroupByChange() {
    activeBucket = null;
    reloadItems();
  }

  async function selectBucket(b) {
    activeBucket = activeBucket === b.fullName ? null : b.fullName;
    await reloadItems();
  }

  async function reloadItems() {
    loadingList = true;
    selected = null;
    selectedId = null;
    items = (await getList(type, activeBucket ?? '')) ?? [];
    loadingList = false;
  }

  async function open(item) {
    selectedId = item.id;
    loadingCard = true;
    selected = await getViewable(item.type, item.id);
    loadingCard = false;
  }
</script>

<div class="app">
  <header class="topbar">
    <div class="brand">Quiz<span class="dot">·</span><span class="sub">explorer</span></div>
    <nav class="tabs">
      {#each domains as d}
        <label class="domain" class:active={d.types.includes(type)} title={d.name}>
          <span class="domain-name">{d.name}</span>
          <select
            class="domain-select"
            value={d.types.includes(type) ? type : ''}
            onchange={(e) => e.currentTarget.value && selectType(e.currentTarget.value)}
          >
            {#if !d.types.includes(type)}<option value="" disabled>Class…</option>{/if}
            {#each d.types as t}
              <option value={t}>{t}</option>
            {/each}
          </select>
        </label>
      {/each}
    </nav>
    <div class="actions">
      <a class="play ghost" href="/quality">Quality</a>
      <a class="play ghost" href="/builder">Builder</a>
      <a class="play ghost" href="/pairing">Pair up →</a>
      <a class="play" href="/quiz">Play quiz →</a>
    </div>
  </header>

  {#if error}
    <div class="banner">{error}</div>
  {/if}

  <div class="body" class:show-detail={!!selected}>
    <aside class="sidebar">
      <div class="search">
        <input placeholder="Search…" bind:value={q} />
      </div>
      {#if dimensions.length}
        <div class="groupby">
          <span class="gb-label">Group by</span>
          <select bind:value={groupBy} onchange={onGroupByChange}>
            <option value="">None</option>
            {#each dimensions as d}
              <option value={d.label}>{d.label}</option>
            {/each}
          </select>
        </div>
        {#if groupBy && buckets.length}
          <div class="buckets">
            {#each buckets as b}
              <button
                class="bucket"
                class:active={activeBucket === b.fullName}
                onclick={() => selectBucket(b)}
                title={b.name}
              >
                <span class="bk-name">{b.name}</span>
                <span class="bk-count">{b.count}</span>
              </button>
            {/each}
          </div>
        {/if}
      {/if}
      <div class="list">
        {#if loadingList}
          <p class="hint">Loading…</p>
        {:else}
          {#each filtered as item}
            <button
              class="row"
              class:active={selectedId === item.id}
              onclick={() => open(item)}
              title={item.name}
            >
              {item.name}
            </button>
          {/each}
        {/if}
      </div>
      {#if !loadingList}
        <div class="count">{filtered.length} of {items.length}</div>
      {/if}
    </aside>

    <main class="content">
      {#if loadingCard}
        <p class="hint center">Loading…</p>
      {:else if selected}
        <div class="content-inner">
          <button class="back-mobile" onclick={() => { selected = null; selectedId = null; }}>← List</button>
          <ViewableCard view={selected} heading={true} />
        </div>
      {:else}
        <div class="empty">
          <div class="empty-mark">◵</div>
          <p>Select an item to view its card.</p>
        </div>
      {/if}
    </main>
  </div>
</div>

<style>
  .app { display: flex; flex-direction: column; height: 100vh; }

  .topbar {
    display: flex;
    align-items: center;
    gap: 22px;
    padding: 0 20px;
    height: 54px;
    background: var(--panel);
    border-bottom: 1px solid var(--line);
    flex: none;
  }
  .brand { font-weight: 650; font-size: 15px; letter-spacing: -0.01em; }
  .brand .dot { color: var(--accent); margin: 0 3px; }
  .brand .sub { color: var(--muted); font-weight: 500; }

  .tabs { display: flex; gap: 14px; align-items: center; }
  .domain { display: flex; align-items: center; gap: 4px; }
  .domain + .domain { border-left: 1px solid var(--border, #2a2a2a); padding-left: 14px; }
  .domain-name {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted);
    margin-right: 2px;
  }
  .domain.active .domain-name { color: var(--accent); }
  .domain-select {
    padding: 4px 8px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--line-strong);
    background: var(--chip-bg);
    color: var(--fg);
    font-weight: 500;
    max-width: 150px;
  }
  .domain.active .domain-select { border-color: var(--accent); color: var(--accent); }
  .domain-select:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-weak); }

  .actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
  .play {
    padding: 6px 14px;
    border-radius: 999px;
    background: var(--accent);
    color: #fff;
    font-weight: 600;
  }
  .play.ghost { background: transparent; color: var(--accent); border: 1px solid var(--accent); }
  .play:hover { text-decoration: none; filter: brightness(1.05); }

  .banner {
    background: #fff4f4;
    color: #b42318;
    border-bottom: 1px solid #f3c9c4;
    padding: 8px 20px;
    font-size: 13px;
  }

  .body {
    flex: 1;
    display: grid;
    grid-template-columns: 300px 1fr;
    min-height: 0;
  }

  .sidebar {
    display: flex;
    flex-direction: column;
    min-height: 0;
    min-width: 0;
    border-right: 1px solid var(--line);
    background: var(--panel);
  }
  .search { padding: 12px; border-bottom: 1px solid var(--line); }
  .search input {
    width: 100%;
    padding: 8px 10px;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    outline: none;
  }
  .search input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-weak); }

  .groupby {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    border-bottom: 1px solid var(--line);
  }
  .gb-label { font-size: 12px; color: var(--muted); }
  .groupby select {
    flex: 1;
    padding: 5px 8px;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: var(--panel);
    color: var(--fg);
    outline: none;
  }
  .groupby select:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-weak); }

  .buckets {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
    padding: 8px 12px;
    max-height: 168px;
    overflow-y: auto;
    border-bottom: 1px solid var(--line);
  }
  .bucket {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 3px 9px;
    border-radius: 999px;
    background: var(--chip-bg);
    color: var(--fg);
    font-size: 12px;
    line-height: 1.4;
  }
  .bucket:hover { background: var(--accent-weak); }
  .bucket.active { background: var(--accent); color: #fff; }
  .bk-count { color: var(--faint); font-variant-numeric: tabular-nums; }
  .bucket.active .bk-count { color: #ffffffcc; }

  .list { flex: 1; overflow-y: auto; padding: 6px; }
  .row {
    display: block;
    width: 100%;
    text-align: left;
    padding: 7px 10px;
    border-radius: var(--radius-sm);
    color: var(--fg);
    line-height: 1.35;
    overflow-wrap: anywhere;
  }
  .row:hover { background: var(--chip-bg); }
  .row.active { background: var(--accent-weak); color: var(--accent); font-weight: 550; }

  .count { padding: 8px 12px; border-top: 1px solid var(--line); color: var(--faint); font-size: 12px; }

  .content { overflow-y: auto; min-height: 0; min-width: 0; }
  .content-inner { max-width: 720px; margin: 0 auto; padding: 26px 24px; }

  .hint { color: var(--muted); }
  .hint.center { text-align: center; padding-top: 40px; }

  .empty {
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    color: var(--faint);
  }
  .empty-mark { font-size: 34px; opacity: 0.5; }

  .back-mobile {
    display: none;
    margin-bottom: 12px;
    color: var(--accent);
    font-weight: 600;
  }

  /* Phone: single column master-detail (list, then card with a back link) */
  @media (max-width: 720px) {
    .topbar {
      height: auto;
      flex-wrap: wrap;
      gap: 8px 12px;
      padding: 8px 14px;
    }
    .tabs { overflow-x: auto; max-width: 100%; }
    .actions { margin-left: auto; }

    .body { grid-template-columns: 1fr; }
    .sidebar { border-right: none; }
    .content { display: none; }
    .content-inner { padding: 16px 14px; }

    .body.show-detail .sidebar { display: none; }
    .body.show-detail .content { display: block; }
    .back-mobile { display: inline-block; }
  }
</style>
