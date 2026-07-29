<script>
  import { onMount } from 'svelte';
  import { getTypes, getFields, getGroups, getPairing, assetUrl } from '$lib/api.js';
  import GroupTree from '$lib/GroupTree.svelte';
  import FieldPicker from '$lib/FieldPicker.svelte';
  import ImageCarousel from '$lib/ImageCarousel.svelte';
  import ZoomableImage from '$lib/ZoomableImage.svelte';

  const ROUND = 4;
  const COLORS = ['#34a853', '#1a73e8', '#d93025', '#9334e6', '#e8710a', '#0891b2', '#b8860b'];

  // config
  let types = $state([]);
  let type = $state('');
  let fields = $state([]);
  let groupTree = $state(null);
  let group = $state('');
  let promptFields = $state([]);
  let answerFields = $state([]);
  let n = $state(12);
  let loadingFields = $state(false);

  function togglePrompt(name, on) {
    if (on) {
      promptFields = [...promptFields.filter((x) => x !== name), name];
      answerFields = answerFields.filter((x) => x !== name);
    } else {
      promptFields = promptFields.filter((x) => x !== name);
    }
  }
  function toggleAnswer(name, on) {
    if (on) {
      answerFields = [...answerFields.filter((x) => x !== name), name];
      promptFields = promptFields.filter((x) => x !== name);
    } else {
      answerFields = answerFields.filter((x) => x !== name);
    }
  }

  // play
  let pairing = $state(null);
  let roundIdx = $state(0);
  let left = $state([]);
  let right = $state([]);
  let selected = $state(null);
  let colorIdx = $state(0);
  let wrong = $state(null); // {l, r}
  let score = $state(0);
  let loading = $state(false);
  let error = $state(null);

  const roundCount = $derived(pairing ? Math.ceil(pairing.pairs.length / ROUND) : 0);
  const roundSolved = $derived(left.length > 0 && left.every((x) => x.matched));
  const done = $derived(!!pairing && roundIdx >= roundCount);

  onMount(async () => {
    try {
      types = (await getTypes()) ?? [];
      if (types.length) await selectType(types[0]);
    } catch (e) {
      error = 'Cannot reach the API. Is ViewableServerMain running?';
    }
  });

  async function selectType(t) {
    type = t;
    loadingFields = true;
    fields = [];
    groupTree = null;
    promptFields = [];
    answerFields = [];
    try {
      const fs = (await getFields(t)) ?? [];
      if (type !== t) return;
      fields = fs;
      const img = fields.find((f) => f.kind === 'image' || f.kind === 'images');
      const p = (img ?? fields[0])?.name;
      promptFields = p ? [p] : [];
      const txt = fields.find(
        (f) => !promptFields.includes(f.name) && (f.kind === 'text' || f.kind === 'refs' || f.kind === 'list')
      );
      const a = (txt ?? fields.find((f) => !promptFields.includes(f.name)) ?? fields[0])?.name;
      answerFields = a ? [a] : [];
      const tree = await getGroups(t);
      if (type !== t) return;
      groupTree = tree;
      group = tree ? tree.fullName : '';
    } finally {
      if (type === t) loadingFields = false;
    }
  }

  function shuffle(a) {
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  function setupRound(idx) {
    const slice = pairing.pairs.slice(idx * ROUND, idx * ROUND + ROUND);
    left = slice.map((p) => ({ prompts: p.prompts, answer: p.answer, matched: false, color: null }));
    right = shuffle(slice.map((p) => p.answer)).map((a) => ({ answer: a, matched: false, color: null }));
    selected = null;
    wrong = null;
  }

  async function start() {
    if (!type || !promptFields.length || !answerFields.length) return;
    loading = true;
    error = null;
    pairing = await getPairing(type, {
      prompt: promptFields.join(','),
      ask: answerFields.join(','),
      n,
      group
    });
    if (!pairing || !pairing.pairs || pairing.pairs.length === 0) {
      error = 'No pairs could be generated for those fields — try different ones.';
      pairing = null;
      loading = false;
      return;
    }
    roundIdx = 0;
    score = 0;
    colorIdx = 0;
    setupRound(0);
    loading = false;
  }

  function clickLeft(i) {
    if (left[i].matched) return;
    selected = selected === i ? null : i;
  }

  function clickRight(j) {
    if (right[j].matched || selected === null) return;
    if (left[selected].answer === right[j].answer) {
      const color = COLORS[colorIdx++ % COLORS.length];
      left[selected].matched = true;
      left[selected].color = color;
      right[j].matched = true;
      right[j].color = color;
      score++;
      selected = null;
    } else {
      wrong = { l: selected, r: j };
      const sel = selected;
      selected = null;
      setTimeout(() => {
        if (wrong && wrong.l === sel && wrong.r === j) wrong = null;
      }, 550);
    }
  }

  function next() {
    roundIdx++;
    if (roundIdx < roundCount) setupRound(roundIdx);
  }

  function reconfigure() {
    pairing = null;
  }
</script>

<div class="pair">
  <header>
    <a class="back" href="/">← Browse</a>
    <span class="mode">Pairing</span>
    <a class="alt" href="/quiz">Switch to ABCD quiz →</a>
    {#if pairing && !done}
      <span class="progress">Round {roundIdx + 1} / {roundCount}</span>
      <span class="score">Matched {score}</span>
    {/if}
  </header>

  <main>
    {#if loading}
      <p class="hint center">Loading…</p>
    {:else if error}
      <p class="error center">{error}</p>
    {:else if !pairing}
      <!-- config -->
      <div class="config">
        <h1>New pairing</h1>
        <label>
          Dataset
          <select value={type} onchange={(e) => selectType(e.currentTarget.value)}>
            {#each types as t}<option value={t}>{t}</option>{/each}
          </select>
        </label>
        {#if loadingFields}
          <p class="hint">Loading {type}…</p>
        {:else}
          {#if groupTree}
            <div class="group-field">
              <span class="field-label">Group</span>
              <div class="group-tree">
                <GroupTree node={groupTree} selected={group} onSelect={(fn) => (group = fn)} />
              </div>
            </div>
          {/if}
          <div class="fields">
            {#key type}
              <FieldPicker
                {type}
                {promptFields}
                {answerFields}
                onPrompt={togglePrompt}
                onAnswer={toggleAnswer}
              />
            {/key}
          </div>
          <label>
            Pairs
            <input type="number" min="2" max="40" bind:value={n} />
          </label>
          <button class="primary" onclick={start} disabled={!promptFields.length || !answerFields.length}>Start</button>
        {/if}
      </div>
    {:else if done}
      <div class="results">
        <div class="big">{score} matched</div>
        <p class="hint">Nicely paired!</p>
        <div class="row">
          <button class="primary" onclick={start}>Play again</button>
          <button class="ghost" onclick={reconfigure}>Change setup</button>
        </div>
      </div>
    {:else}
      <div class="board">
        <p class="ask">Match each item to its <strong>{pairing.ask}</strong></p>
        <div class="cols">
          <div class="col">
            {#each left as item, i}
              <!-- a div (not button) so the corner zoom button can nest: click
                   the cell to select, click ⛶ to zoom -->
              <div
                class="cell prompt-cell"
                class:sel={selected === i}
                class:matched={item.matched}
                class:wrong={wrong && wrong.l === i}
                style={item.matched ? `border-color:${item.color}; box-shadow: inset 0 0 0 2px ${item.color}` : ''}
                role="button"
                tabindex={item.matched ? -1 : 0}
                onclick={() => clickLeft(i)}
                onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); clickLeft(i); } }}
              >
                {#each item.prompts as p}
                  {#if p.kind === 'image'}
                    <img src={assetUrl(p.url)} alt="" />
                    <ZoomableImage icon src={assetUrl(p.url)} alt="" />
                  {:else if p.kind === 'images'}
                    <ImageCarousel urls={p.values} maxWidth="120px" maxHeight="64px" />
                  {:else if p.kind === 'list'}
                    <span class="ptext">{p.values.join(', ')}</span>
                  {:else if p.kind === 'refs'}
                    <span class="ptext">{p.refs.map((r) => r.name).join(', ')}</span>
                  {:else if p.kind === 'ref'}
                    <span class="ptext">{p.ref?.name ?? ''}</span>
                  {:else}
                    <span class="ptext">{p.value ?? ''}</span>
                  {/if}
                {/each}
              </div>
            {/each}
          </div>
          <div class="col">
            {#each right as item, j}
              <button
                class="cell answer-cell"
                class:matched={item.matched}
                class:wrong={wrong && wrong.r === j}
                style={item.matched ? `border-color:${item.color}; box-shadow: inset 0 0 0 2px ${item.color}` : ''}
                disabled={item.matched}
                onclick={() => clickRight(j)}
              >
                {item.answer}
              </button>
            {/each}
          </div>
        </div>

        {#if roundSolved}
          <button class="primary next" onclick={next}>
            {roundIdx + 1 < roundCount ? 'Next round' : 'See results'}
          </button>
        {/if}
      </div>
    {/if}
  </main>
</div>

<style>
  .pair { display: flex; flex-direction: column; min-height: 100vh; }
  header {
    display: flex; align-items: center; gap: 16px;
    padding: 12px 20px; border-bottom: 1px solid var(--line); background: var(--panel);
  }
  .back, .alt { font-weight: 500; }
  .mode { font-weight: 700; }
  .alt { color: var(--muted); }
  .progress { color: var(--muted); }
  .score { margin-left: auto; font-weight: 600; }

  main { flex: 1; display: flex; align-items: flex-start; justify-content: center; padding: 28px 20px; }

  .config {
    width: 100%; max-width: 420px; background: var(--panel);
    border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow);
    padding: 22px; display: flex; flex-direction: column; gap: 14px;
  }
  .config h1 { margin: 0 0 4px; font-size: 1.2rem; }
  .config label { display: flex; flex-direction: column; gap: 5px; font-size: 0.86rem; color: var(--muted); }
  .config select, .config input {
    padding: 8px 10px; border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm); color: var(--fg); background: #fff;
  }
  .group-field { display: flex; flex-direction: column; gap: 5px; font-size: 0.86rem; color: var(--muted); }
  .group-tree {
    max-height: 220px; overflow-y: auto; border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm); padding: 6px; background: #fff;
  }
  .fields { border: 1px solid var(--line-strong); border-radius: var(--radius-sm); padding: 6px 8px; max-height: 240px; overflow-y: auto; }

  .board { width: 100%; max-width: 640px; }
  .ask { color: var(--muted); text-align: center; margin: 0 0 16px; }
  .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
  .col { display: flex; flex-direction: column; gap: 10px; }
  .cell {
    min-height: 60px; padding: 10px; display: flex; align-items: center; justify-content: center;
    gap: 8px; border: 1px solid var(--line-strong); border-radius: var(--radius-sm);
    background: #fff; font-weight: 500; text-align: center; transition: background 0.12s, border-color 0.12s;
  }
  .cell { cursor: pointer; }
  .cell:hover:not(:disabled):not(.matched) { border-color: var(--accent); background: var(--accent-weak); }
  .cell:disabled { cursor: default; }
  .prompt-cell { position: relative; }
  .prompt-cell img { max-width: 120px; max-height: 64px; object-fit: contain; }
  .ptext { overflow-wrap: anywhere; }
  .cell.sel { border-color: var(--accent); background: var(--accent-weak); box-shadow: inset 0 0 0 2px var(--accent); }
  .cell.matched { opacity: 0.92; cursor: default; }
  .cell.wrong { background: #fdecea; border-color: #d93025; animation: shake 0.3s; }
  @keyframes shake { 0%,100%{transform:translateX(0)} 25%{transform:translateX(-4px)} 75%{transform:translateX(4px)} }

  .primary { padding: 10px 18px; border-radius: var(--radius-sm); background: var(--accent); color: #fff; font-weight: 600; }
  .primary:disabled { opacity: 0.5; cursor: default; }
  .ghost { padding: 10px 18px; border-radius: var(--radius-sm); border: 1px solid var(--line-strong); }
  .next { margin: 20px auto 0; display: block; }

  .results { text-align: center; padding-top: 30px; }
  .results .big { font-size: 2.4rem; font-weight: 700; }
  .row { display: flex; gap: 10px; justify-content: center; margin-top: 14px; }

  .hint { color: var(--muted); }
  .center { text-align: center; padding-top: 40px; }
  .error { color: #b00020; }

  @media (max-width: 720px) {
    main { padding: 16px 12px; }
    .cols { gap: 8px; }
    .cell { min-height: 52px; padding: 8px; font-size: 0.92rem; }
    .prompt-cell img { max-width: 90px; max-height: 52px; }
  }
</style>
