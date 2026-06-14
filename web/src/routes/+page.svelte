<script>
  import { onMount } from 'svelte';
  import { getTypes, getList, getQuizable } from '$lib/api.js';
  import QuizableCard from '$lib/QuizableCard.svelte';

  let types = $state([]);
  let type = $state(null);
  let items = $state([]);
  let selected = $state(null);
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
      error = 'Cannot reach the API at the configured base URL. Is QuizableServerMain running?';
    }
  });

  async function selectType(t) {
    type = t;
    selected = null;
    q = '';
    loadingList = true;
    items = (await getList(t)) ?? [];
    loadingList = false;
  }

  async function open(item) {
    loadingCard = true;
    selected = await getQuizable(item.type, item.id);
    loadingCard = false;
  }
</script>

<header>
  <h1>Quiz</h1>
  <nav>
    {#each types as t}
      <button class:active={t === type} onclick={() => selectType(t)}>{t}</button>
    {/each}
  </nav>
</header>

{#if error}
  <p class="error">{error}</p>
{/if}

<main>
  <aside>
    <input placeholder="Search…" bind:value={q} />
    {#if loadingList}
      <p class="muted">Loading…</p>
    {:else}
      <ul>
        {#each filtered as item}
          <li>
            <button class:active={selected && selected.id === item.id} onclick={() => open(item)}>
              {item.name}
            </button>
          </li>
        {/each}
      </ul>
      <p class="muted count">{filtered.length} / {items.length}</p>
    {/if}
  </aside>

  <section>
    {#if loadingCard}
      <p class="muted">Loading…</p>
    {:else if selected}
      <QuizableCard view={selected} heading={true} />
    {:else}
      <p class="muted">Select an item.</p>
    {/if}
  </section>
</main>

<style>
  header {
    display: flex;
    align-items: baseline;
    gap: 16px;
    padding: 12px 20px;
    border-bottom: 1px solid var(--line);
    background: #fff;
  }
  header h1 { margin: 0; font-size: 1.1rem; }
  nav { display: flex; gap: 6px; flex-wrap: wrap; }
  nav button { padding: 4px 10px; border-radius: 6px; }
  nav button.active { background: var(--accent); color: #fff; }

  .error { color: #b00020; padding: 10px 20px; }

  main {
    display: grid;
    grid-template-columns: 280px 1fr;
    gap: 18px;
    padding: 18px 20px;
    align-items: start;
  }
  aside input {
    width: 100%;
    padding: 6px 8px;
    border: 1px solid var(--line);
    border-radius: 6px;
    margin-bottom: 8px;
  }
  aside ul { list-style: none; margin: 0; padding: 0; max-height: 75vh; overflow: auto; }
  aside li button {
    display: block;
    width: 100%;
    text-align: left;
    padding: 4px 8px;
    border-radius: 6px;
  }
  aside li button:hover { background: var(--chip-bg); }
  aside li button.active { background: var(--accent); color: #fff; }
  .muted { color: var(--muted); }
  .count { font-size: 0.8em; }
</style>
