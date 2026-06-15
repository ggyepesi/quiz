<script>
  import { onMount } from 'svelte';
  import { getTypes, getList, getQuizable } from '$lib/api.js';
  import QuizableCard from '$lib/QuizableCard.svelte';

  let types = $state([]);
  let type = $state(null);
  let items = $state([]);
  let selected = $state(null);
  let selectedId = $state(null);
  let loadingList = $state(false);
  let loadingCard = $state(false);
  let error = $state(null);
  let q = $state('');

  const filtered = $derived(
    items.filter((i) => i.name.toLowerCase().includes(q.toLowerCase()))
  );

  onMount(async () => {
    try {
      types = (await getTypes()) ?? [];
      if (types.length) await selectType(types[0]);
    } catch (e) {
      error = 'Cannot reach the API. Is QuizableServerMain running on :7070?';
    }
  });

  async function selectType(t) {
    type = t;
    selected = null;
    selectedId = null;
    q = '';
    loadingList = true;
    items = (await getList(t)) ?? [];
    loadingList = false;
  }

  async function open(item) {
    selectedId = item.id;
    loadingCard = true;
    selected = await getQuizable(item.type, item.id);
    loadingCard = false;
  }
</script>

<div class="app">
  <header class="topbar">
    <div class="brand">Quiz<span class="dot">·</span><span class="sub">explorer</span></div>
    <nav class="tabs">
      {#each types as t}
        <button class="tab" class:active={t === type} onclick={() => selectType(t)}>{t}</button>
      {/each}
    </nav>
    <a class="play" href="/quiz">Play quiz →</a>
  </header>

  {#if error}
    <div class="banner">{error}</div>
  {/if}

  <div class="body" class:show-detail={!!selected}>
    <aside class="sidebar">
      <div class="search">
        <input placeholder="Search…" bind:value={q} />
      </div>
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
          <QuizableCard view={selected} heading={true} />
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

  .tabs { display: flex; gap: 4px; }
  .tab {
    padding: 5px 12px;
    border-radius: 999px;
    color: var(--muted);
    font-weight: 500;
  }
  .tab:hover { background: var(--chip-bg); color: var(--fg); }
  .tab.active { background: var(--accent); color: #fff; }

  .play {
    margin-left: auto;
    padding: 6px 14px;
    border-radius: 999px;
    background: var(--accent);
    color: #fff;
    font-weight: 600;
  }
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
    .play { margin-left: auto; }

    .body { grid-template-columns: 1fr; }
    .sidebar { border-right: none; }
    .content { display: none; }
    .content-inner { padding: 16px 14px; }

    .body.show-detail .sidebar { display: none; }
    .body.show-detail .content { display: block; }
    .back-mobile { display: inline-block; }
  }
</style>
