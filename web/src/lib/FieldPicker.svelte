<script>
  import { onMount } from 'svelte';
  import Self from './FieldPicker.svelte';
  import { getFields } from '$lib/api.js';

  // promptFields/answerFields are arrays of dotted paths (e.g. "namedAfter.area").
  // onPrompt/onAnswer(path, checked) toggle membership in those arrays.
  let {
    type,
    path = '',
    promptFields,
    answerFields,
    onPrompt,
    onAnswer,
    depth = 0
  } = $props();

  let fields = $state([]);
  let loading = $state(true);
  let open = $state({}); // full path -> expanded?

  onMount(async () => {
    fields = (await getFields(type, path)) ?? [];
    loading = false;
  });

  const full = (name) => (path ? `${path}.${name}` : name);
</script>

{#if depth === 0}
  <div class="fhead">
    <span class="fname">Field</span><span class="cb">Show</span><span class="cb">Guess</span>
  </div>
{/if}

{#if loading}
  <div class="hint" style="padding-left:{depth * 14 + 18}px">Loading…</div>
{:else}
  {#each fields as f}
    {@const fp = full(f.name)}
    <div class="frow">
      <span class="fname" style="padding-left:{depth * 14}px">
        {#if f.expandable}
          <button class="exp" onclick={() => (open = { ...open, [fp]: !open[fp] })} aria-label="expand">{open[fp] ? '▾' : '▸'}</button>
        {:else}
          <span class="exp"></span>
        {/if}
        <span class="nm">{f.name}</span> <span class="kind">{f.kind}</span>
      </span>
      <span class="cb"><input type="checkbox" checked={promptFields.includes(fp)} onchange={(e) => onPrompt(fp, e.currentTarget.checked)} /></span>
      <span class="cb"><input type="checkbox" checked={answerFields.includes(fp)} onchange={(e) => onAnswer(fp, e.currentTarget.checked)} /></span>
    </div>
    {#if f.expandable && open[fp]}
      <Self {type} path={fp} {promptFields} {answerFields} {onPrompt} {onAnswer} depth={depth + 1} />
    {/if}
  {/each}
{/if}

<style>
  .fhead,
  .frow {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .fhead {
    font-size: 0.76rem;
    color: var(--muted);
    padding: 2px 0 4px;
    border-bottom: 1px solid var(--line);
    margin-bottom: 2px;
  }
  .frow {
    padding: 2px 0;
  }
  .fname {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 4px;
    overflow: hidden;
  }
  .nm {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cb {
    width: 48px;
    flex: none;
    display: flex;
    justify-content: center;
  }
  .exp {
    width: 16px;
    flex: none;
    color: var(--faint);
    font-size: 0.72em;
    background: none;
    border: none;
    cursor: pointer;
    padding: 0;
  }
  .exp:hover {
    color: var(--accent);
  }
  .kind {
    color: var(--faint);
    font-size: 0.8em;
    flex: none;
  }
  .hint {
    color: var(--muted);
    font-style: italic;
    font-size: 0.85em;
    padding: 3px 0;
  }
</style>
