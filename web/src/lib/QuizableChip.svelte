<script>
  import { getQuizable } from './api.js';
  import QuizableCard from './QuizableCard.svelte';

  let { ref } = $props();

  let open = $state(false);
  let loading = $state(false);
  let child = $state(null);
  let missing = $state(false);

  async function toggle() {
    open = !open;
    if (open && !child && !missing) {
      loading = true;
      child = await getQuizable(ref.type, ref.id);
      missing = child === null;
      loading = false;
    }
  }
</script>

<div class="chip">
  <button class="head" onclick={toggle}>
    <span class="tri">{open ? '▾' : '▸'}</span>
    <span class="name">{ref.name}</span>
    <span class="type">{ref.type}</span>
  </button>

  {#if open}
    <div class="body">
      {#if loading}
        <span class="muted">Loading…</span>
      {:else if child}
        <QuizableCard view={child} />
      {:else}
        <span class="muted">{ref.name} — no detail available</span>
      {/if}
    </div>
  {/if}
</div>

<style>
  .chip { margin: 2px 0; }
  .head {
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
    padding: 2px 6px;
    border-radius: 6px;
    background: var(--chip-bg);
  }
  .head:hover { background: #e8eefb; }
  .tri { color: var(--muted); font-size: 0.8em; }
  .name { color: var(--accent); }
  .type { color: var(--muted); font-size: 0.78em; }
  .body { margin: 4px 0 6px 16px; }
  .muted { color: var(--muted); font-style: italic; }
</style>
