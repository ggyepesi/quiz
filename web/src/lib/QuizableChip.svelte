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

<div class="chip" class:open>
  <button class="head" onclick={toggle}>
    <span class="tri" class:open>▸</span>
    <span class="name">{ref.name}</span>
  </button>

  {#if open}
    <div class="body">
      {#if loading}
        <span class="hint">Loading…</span>
      {:else if child}
        <QuizableCard view={child} />
      {:else}
        <span class="hint">no detail available</span>
      {/if}
    </div>
  {/if}
</div>

<style>
  .chip { display: inline-block; max-width: 100%; }
  .chip.open { display: block; }

  .head {
    display: inline-flex;
    align-items: baseline;
    gap: 5px;
    max-width: 100%;
    text-align: left;
    padding: 2px 9px 2px 6px;
    border-radius: 12px;
    background: var(--chip-bg);
    color: var(--accent);
    font-weight: 500;
  }
  .head:hover { background: var(--chip-hover); }

  .tri {
    color: var(--faint);
    font-size: 0.7em;
    transition: transform 0.12s ease;
    flex: none;
  }
  .tri.open { transform: rotate(90deg); color: var(--muted); }

  .name { overflow-wrap: anywhere; }

  .body {
    margin: 6px 0 8px 9px;
    padding-left: 12px;
    border-left: 2px solid var(--line-strong);
  }
  .hint { color: var(--faint); font-style: italic; }
</style>
