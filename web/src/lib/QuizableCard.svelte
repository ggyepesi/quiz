<script>
  import QuizableChip from './QuizableChip.svelte';
  import Self from './QuizableCard.svelte';

  let { view, heading = false } = $props();
</script>

<div class="card" class:heading>
  {#if heading}
    <div class="title">
      <span class="name">{view.name}</span>
      <span class="type">{view.type}</span>
    </div>
  {/if}

  {#each view.fields ?? [] as f}
    <div class="field">
      <span class="fname">{f.name}</span>
      <span class="fval">
        {#if f.kind === 'text'}
          {f.value}
        {:else if f.kind === 'list'}
          {f.values.join(', ')}
        {:else if f.kind === 'link'}
          <a href={f.url} target="_blank" rel="noreferrer">{f.label}</a>
        {:else if f.kind === 'ref'}
          <QuizableChip ref={f.ref} />
        {:else if f.kind === 'refs'}
          <div class="refs">
            {#each f.refs as r}
              <QuizableChip ref={r} />
            {/each}
          </div>
        {:else if f.kind === 'inline'}
          <div class="inline">
            {#each f.nodes as n}
              <Self view={n} heading={true} />
            {/each}
          </div>
        {/if}
      </span>
    </div>
  {/each}
</div>

<style>
  .card.heading {
    border: 1px solid var(--line);
    border-radius: 10px;
    padding: 12px 14px;
    background: #fff;
  }
  .title {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin-bottom: 8px;
  }
  .title .name { font-weight: 600; font-size: 1.05em; }
  .title .type { color: var(--muted); font-size: 0.8em; }
  .field {
    display: grid;
    grid-template-columns: 140px 1fr;
    gap: 10px;
    padding: 3px 0;
    align-items: start;
  }
  .fname { color: var(--muted); }
  .inline { display: grid; gap: 8px; }
</style>
