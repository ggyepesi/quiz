<script>
  import { assetUrl } from '$lib/api.js';
  import ZoomableImage from '$lib/ZoomableImage.svelte';

  // urls: image URLs. Shows one at a time with left/right arrow stripes when
  // there's more than one, plus the same corner zoom (⛶) icon as a single
  // image — it zooms the CURRENT image.
  let { urls = [], maxWidth = '100%', maxHeight = '170px' } = $props();

  let idx = $state(0);
  const n = $derived(urls.length);

  function step(d, e) {
    e?.stopPropagation();
    idx = (idx + d + n) % n;
  }
</script>

{#if n > 0}
  <div class="carousel" style="--mw:{maxWidth}; --mh:{maxHeight}">
    <div class="frame">
      <img src={assetUrl(urls[idx])} alt="" />
      <ZoomableImage
        icon
        src={assetUrl(urls[idx])}
        alt=""
        onPrev={n > 1 ? () => step(-1) : null}
        onNext={n > 1 ? () => step(1) : null}
      />
      {#if n > 1}
        <button class="arrow left" onclick={(e) => step(-1, e)} aria-label="previous">‹</button>
        <button class="arrow right" onclick={(e) => step(1, e)} aria-label="next">›</button>
        <span class="count">{idx + 1}/{n}</span>
      {/if}
    </div>
  </div>
{/if}

<style>
  .carousel { display: inline-block; max-width: 100%; }
  .frame {
    position: relative;
    display: inline-flex;
    line-height: 0;
  }
  .frame > img { max-width: var(--mw); max-height: var(--mh); object-fit: contain; }

  .arrow {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 28%;
    display: flex;
    align-items: center;
    border: none;
    color: #fff;
    font-size: 1.6rem;
    font-weight: 700;
    cursor: pointer;
    transition: background 0.12s;
  }
  .arrow.left { left: 0; justify-content: flex-start; padding-left: 4px;
    background: linear-gradient(to right, rgba(0,0,0,0.28), rgba(0,0,0,0)); }
  .arrow.right { right: 0; justify-content: flex-end; padding-right: 4px;
    background: linear-gradient(to left, rgba(0,0,0,0.28), rgba(0,0,0,0)); }
  .arrow:hover { background: rgba(0, 0, 0, 0.4); }

  .count {
    position: absolute;
    left: 4px;
    bottom: 3px;
    background: rgba(0, 0, 0, 0.55);
    color: #fff;
    font-size: 0.7rem;
    padding: 0 5px;
    border-radius: 8px;
    line-height: 1.5;
  }
</style>
