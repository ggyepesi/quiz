<script>
  // Opens a fullscreen lightbox: scroll to zoom, drag to pan, double-click
  // toggles 1x/2.5x, Esc/backdrop to close.
  //
  // Two trigger modes:
  //   default  — renders the thumbnail itself (click it to zoom)
  //   icon     — renders a small ⛶ button only (the caller draws the image);
  //              use when the image already has a click action, e.g. a pairing
  //              match cell.
  let {
    src,
    alt = '',
    icon = false,
    maxWidth = '100%',
    maxHeight = '220px',
    // Optional: when a carousel passes these, the lightbox shows ‹ › arrows
    // that step through the set (the parent updates `src`).
    onPrev = null,
    onNext = null
  } = $props();

  let open = $state(false);
  let scale = $state(1);
  let tx = $state(0);
  let ty = $state(0);

  // Reset zoom/pan whenever the image changes (e.g. navigating the carousel
  // inside the lightbox), so each image starts fit-to-view.
  $effect(() => {
    src;
    scale = 1;
    tx = 0;
    ty = 0;
  });

  function step(d, e) {
    e?.stopPropagation();
    if (d < 0 && onPrev) onPrev();
    else if (d > 0 && onNext) onNext();
  }

  let dragging = false;
  let startX = 0;
  let startY = 0;

  function show(e) {
    e?.stopPropagation();
    open = true;
    scale = 1;
    tx = 0;
    ty = 0;
  }
  function close() {
    open = false;
  }

  function onWheel(e) {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    scale = Math.min(8, Math.max(1, scale * factor));
    if (scale === 1) {
      tx = 0;
      ty = 0;
    }
  }
  function onDblClick() {
    if (scale > 1) {
      scale = 1;
      tx = 0;
      ty = 0;
    } else {
      scale = 2.5;
    }
  }
  function down(e) {
    if (scale === 1) return;
    dragging = true;
    startX = e.clientX - tx;
    startY = e.clientY - ty;
    e.currentTarget.setPointerCapture?.(e.pointerId);
  }
  function move(e) {
    if (!dragging) return;
    tx = e.clientX - startX;
    ty = e.clientY - startY;
  }
  function up() {
    dragging = false;
  }
  function onKey(e) {
    if (!open) return;
    if (e.key === 'Escape') close();
    else if (e.key === '+' || e.key === '=') scale = Math.min(8, scale * 1.2);
    else if (e.key === '-') scale = Math.max(1, scale / 1.2);
  }
</script>

<svelte:window onkeydown={onKey} />

{#if icon}
  <button class="zoom-icon" onclick={show} title="Zoom" aria-label="Zoom image">⛶</button>
{:else}
  <button class="thumb-btn" onclick={show} title="Click to zoom" aria-label="Zoom image">
    <img
      class="thumb"
      {src}
      {alt}
      loading="lazy"
      style="max-width:{maxWidth}; max-height:{maxHeight};"
    />
  </button>
{/if}

{#if open}
  <div class="overlay" onclick={close} role="presentation">
    <button class="close" onclick={close} aria-label="Close">×</button>
    {#if onPrev}
      <button class="navarrow left" onclick={(e) => step(-1, e)} aria-label="previous">‹</button>
    {/if}
    {#if onNext}
      <button class="navarrow right" onclick={(e) => step(1, e)} aria-label="next">›</button>
    {/if}
    <img
      class="full"
      class:grab={scale > 1}
      {src}
      {alt}
      style="transform: translate({tx}px, {ty}px) scale({scale})"
      onclick={(e) => e.stopPropagation()}
      onwheel={onWheel}
      ondblclick={onDblClick}
      onpointerdown={down}
      onpointermove={move}
      onpointerup={up}
      onpointercancel={up}
      draggable="false"
    />
    <div class="hint" role="presentation" onclick={(e) => e.stopPropagation()}>
      {onPrev ? '‹ › to change · ' : ''}scroll to zoom · drag to pan · double-click · Esc
    </div>
  </div>
{/if}

<style>
  .thumb-btn {
    padding: 0;
    border: 0;
    background: none;
    cursor: zoom-in;
    display: inline-block;
    line-height: 0;
    max-width: 100%;
  }
  .thumb {
    display: block;
    width: auto;
    height: auto;
    object-fit: contain;
    border-radius: 6px;
    background: #fff;
  }

  .zoom-icon {
    position: absolute;
    top: 4px;
    right: 4px;
    width: 26px;
    height: 26px;
    border-radius: 6px;
    background: rgba(0, 0, 0, 0.45);
    color: #fff;
    font-size: 0.95rem;
    line-height: 1;
    cursor: zoom-in;
    z-index: 2;
  }
  .zoom-icon:hover { background: rgba(0, 0, 0, 0.7); }

  .overlay {
    position: fixed;
    inset: 0;
    z-index: 1000;
    background: rgba(0, 0, 0, 0.85);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    cursor: zoom-out;
  }
  .full {
    max-width: 92vw;
    max-height: 88vh;
    object-fit: contain;
    transform-origin: center center;
    transition: transform 0.05s linear;
    cursor: default;
    user-select: none;
    touch-action: none;
  }
  .full.grab { cursor: grab; }
  .full.grab:active { cursor: grabbing; }

  .close {
    position: fixed;
    top: 14px;
    right: 18px;
    width: 40px;
    height: 40px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.15);
    color: #fff;
    font-size: 1.6rem;
    line-height: 1;
    cursor: pointer;
  }

  .navarrow {
    position: fixed;
    top: 50%;
    transform: translateY(-50%);
    width: 54px;
    height: 84px;
    border: none;
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.12);
    color: #fff;
    font-size: 2.4rem;
    line-height: 1;
    cursor: pointer;
    z-index: 1;
  }
  .navarrow.left { left: 16px; }
  .navarrow.right { right: 16px; }
  .navarrow:hover { background: rgba(255, 255, 255, 0.28); }
  .close:hover { background: rgba(255, 255, 255, 0.3); }

  .hint {
    position: fixed;
    bottom: 14px;
    left: 50%;
    transform: translateX(-50%);
    color: rgba(255, 255, 255, 0.7);
    font-size: 0.8rem;
    background: rgba(0, 0, 0, 0.4);
    padding: 4px 12px;
    border-radius: 999px;
    pointer-events: none;
  }
</style>
