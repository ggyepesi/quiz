<script>
  import QuizableChip from './QuizableChip.svelte';
  import Self from './QuizableCard.svelte';
  import ZoomableImage from './ZoomableImage.svelte';
  import { assetUrl } from './api.js';

  let { view, heading = false, depth = 0 } = $props();

  // Collections show "fieldName (n)" like the desktop CollectionHeader convention.
  const collectionLen = {
    list: (f) => f.values?.length,
    images: (f) => f.values?.length,
    refs: (f) => f.refs?.length,
    inline: (f) => f.nodes?.length,
  };
  const fieldLabel = (f) => {
    const n = collectionLen[f.kind]?.(f);
    return n == null ? f.name : `${f.name} (${n})`;
  };
</script>

<div class="card" class:heading class:nested={depth > 0} class:deep={depth > 2}>
  {#if heading}
    <div class="title">
      <span class="name">{view.name}</span>
      <span class="badge">{view.type}</span>
    </div>
  {/if}

  <dl class="fields">
    {#each view.fields ?? [] as f}
      <div class="field">
        <dt title={f.name}>{fieldLabel(f)}</dt>
        <dd>
          {#if f.kind === 'text'}
            {f.value}
          {:else if f.kind === 'list'}
            <span class="chips">
              {#each f.values as v}<span class="tag">{v}</span>{/each}
            </span>
          {:else if f.kind === 'image'}
            <ZoomableImage src={assetUrl(f.url)} alt={f.name} maxWidth="100%" maxHeight="180px" />
          {:else if f.kind === 'images'}
            <div class="imgs">
              {#each f.values as u}
                <ZoomableImage src={assetUrl(u)} alt={f.name} maxWidth="160px" maxHeight="120px" />
              {/each}
            </div>
          {:else if f.kind === 'link'}
            <a href={f.url} target="_blank" rel="noreferrer">{f.label} ↗</a>
          {:else if f.kind === 'ref'}
            <QuizableChip ref={f.ref} depth={depth + 1} />
          {:else if f.kind === 'refs'}
            <div class="refs">
              {#each f.refs as r}<QuizableChip ref={r} depth={depth + 1} />{/each}
            </div>
          {:else if f.kind === 'inline'}
            <div class="inline">
              {#each f.nodes as n}<Self view={n} heading={true} depth={depth + 1} />{/each}
            </div>
          {/if}
        </dd>
      </div>
    {/each}
  </dl>
</div>

<style>
  .card.heading {
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
    padding: 16px 18px;
  }

  .title {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 6px 10px;
    margin-bottom: 12px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--line);
  }
  .title .name {
    font-size: 1.15rem;
    font-weight: 650;
    letter-spacing: -0.01em;
    overflow-wrap: anywhere;
  }

  /* Query on the card's OWN width so deeply-nested cards collapse the two-column
     field grid — otherwise a depth-3 card squeezes the value column to a few px and
     names wrap one character per line. */
  .fields { margin: 0; display: flex; flex-direction: column; }
  .field {
    display: grid;
    /* minmax floors keep the value column from collapsing to a few px. */
    grid-template-columns: minmax(0, 132px) minmax(0, 1fr);
    gap: 14px;
    padding: 6px 0;
    align-items: start;
  }

  /* A nested card ("section") stacks label-over-value so the value gets the full row,
     and compacts progressively as it deepens. */
  .card.nested .field { grid-template-columns: 1fr; gap: 2px; }
  .card.nested .field dt { font-weight: 600; }
  .card.nested.heading { padding: 10px 12px; box-shadow: none; }
  .card.deep.heading { padding: 6px 8px; border-radius: var(--radius-sm); }
  .field + .field { border-top: 1px solid #f2f3f6; }
  dt { color: var(--muted); font-size: 0.86rem; padding-top: 1px; overflow-wrap: anywhere; }
  dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }

  .chips, .refs { display: flex; flex-wrap: wrap; gap: 6px; }
  .tag {
    background: var(--chip-bg);
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 1px 8px;
    font-size: 0.86rem;
  }
  .inline { display: grid; gap: 10px; }
  .imgs { display: flex; flex-wrap: wrap; gap: 8px; }

  @media (max-width: 720px) {
    .card.heading { padding: 12px 14px; }
    .field { grid-template-columns: 1fr; gap: 2px; padding: 7px 0; }
    dt { font-weight: 600; }
  }
</style>
