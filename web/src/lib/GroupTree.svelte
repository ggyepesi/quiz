<script>
  import Self from './GroupTree.svelte';
  import { getViewable } from './api.js';
  import ViewableCard from './ViewableCard.svelte';

  let { node, selected, onSelect, depth = 0 } = $props();

  let open = $state(depth < 1); // root expanded, deeper levels collapsed
  const hasKids = $derived(!!node.children && node.children.length > 0);
  // A FACET node is a dimension header (holds the whole universe), not a useful
  // quiz scope — show it as a label, don't let it be selected. Trees without
  // roles (legacy, hand-built) report UNIVERSE everywhere, so stay selectable.
  const isFacet = $derived(node.role === 'FACET');

  // A reference bucket carries the entity it groups by (a parent Creature, …);
  // peek shows that entity's own card.
  let showCard = $state(false);
  let card = $state(null);
  let cardLoading = $state(false);
  async function peek() {
    showCard = !showCard;
    if (showCard && !card && node.ref) {
      cardLoading = true;
      card = await getViewable(node.ref.type, node.ref.id);
      cardLoading = false;
    }
  }
</script>

<div class="row" style="padding-left: {depth * 16}px">
  {#if hasKids}
    <button class="tog" onclick={() => (open = !open)} aria-label="toggle">{open ? '▾' : '▸'}</button>
  {:else}
    <span class="tog"></span>
  {/if}
  {#if isFacet}
    <button class="lbl facet" onclick={() => (open = !open)}>
      <span class="nm">{node.name}</span>
      <span class="ct">{node.count}</span>
    </button>
  {:else}
    <button class="lbl" class:sel={selected === node.fullName} onclick={() => onSelect(node.fullName)}>
      <span class="nm">{node.name}</span>
      <span class="ct">{node.count}</span>
    </button>
    {#if node.ref}
      <button class="peek" class:on={showCard} onclick={peek} title="Show {node.ref.name}">ⓘ</button>
    {/if}
  {/if}
</div>

{#if showCard}
  <div class="refcard" style="padding-left: {depth * 16 + 20}px">
    {#if cardLoading}
      <span class="hint">Loading…</span>
    {:else if card}
      <ViewableCard view={card} />
    {:else}
      <span class="hint">no detail available</span>
    {/if}
  </div>
{/if}

{#if open && hasKids}
  {#each node.children as c}
    <Self node={c} {selected} {onSelect} depth={depth + 1} />
  {/each}
{/if}

<style>
  .row { display: flex; align-items: center; gap: 2px; }
  .tog {
    width: 18px;
    flex: none;
    text-align: center;
    color: var(--faint);
    font-size: 0.72em;
  }
  .lbl {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 3px 8px;
    border-radius: 6px;
    text-align: left;
  }
  .lbl:hover { background: var(--chip-bg); }
  .lbl.sel { background: var(--accent-weak); color: var(--accent); font-weight: 600; }
  .lbl.facet {
    color: var(--faint);
    font-size: 0.78em;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    cursor: default;
  }
  .lbl.facet:hover { background: transparent; }
  .nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .ct { flex: none; color: var(--faint); font-size: 0.8em; }
  .peek {
    flex: none;
    width: 20px;
    color: var(--faint);
    font-size: 0.9em;
    border-radius: 6px;
  }
  .peek:hover, .peek.on { color: var(--accent); background: var(--chip-bg); }
  .refcard { margin: 4px 0 8px; }
  .hint { color: var(--faint); font-style: italic; font-size: 0.85em; }
</style>
