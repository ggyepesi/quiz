<script>
  let { parts = [], text = '', onPlain = null } = $props();
</script>

<span class="linked-text">
  {#if parts?.length}
    {#each parts as part}
      {#if part.url}
        <a href={part.url} target="_blank" rel="noreferrer">{part.text}</a>
      {:else if onPlain}
        <button type="button" class="plain" onclick={onPlain}>{part.text}</button>
      {:else}{part.text}{/if}
    {/each}
  {:else}{text}{/if}
</span>

<style>
  .plain {
    display: inline;
    padding: 0;
    border: 0;
    background: none;
    color: inherit;
    font: inherit;
    line-height: inherit;
  }
</style>
