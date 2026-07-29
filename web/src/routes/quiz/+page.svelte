<script>
  import { onMount } from 'svelte';
  import { getDomains, getFields, getGroups, getDimensions, getQuiz, assetUrl } from '$lib/api.js';
  import GroupTree from '$lib/GroupTree.svelte';
  import FieldPicker from '$lib/FieldPicker.svelte';
  import ImageCarousel from '$lib/ImageCarousel.svelte';
  import ZoomableImage from '$lib/ZoomableImage.svelte';

  // config
  let domains = $state([]);
  let type = $state('');
  let fields = $state([]);
  let groupTree = $state(null);
  let group = $state('');
  let dimensions = $state([]);
  let promptFields = $state([]);
  let answerFields = $state([]);
  let n = $state(10);
  let loadingFields = $state(false);

  // The field PATHS the current group is scoped by (constant across its members) —
  // parsed from the group's fullName ("All Nomination/type/human" → dimension "type")
  // and mapped label→path via the declared dimensions (label "type" → "nominee.type").
  function excludedPaths(g) {
    const set = new Set();
    if (!g) return set;
    const segs = g.split('/');
    const labelToPath = new Map(dimensions.map((d) => [d.label, d.path]));
    for (let k = 1; k < segs.length; k += 2) {
      set.add(labelToPath.get(segs[k]) ?? segs[k]);
    }
    return set;
  }
  const excluded = $derived(excludedPaths(group));

  function selectGroup(fn) {
    group = fn;
    const ex = excludedPaths(fn);
    promptFields = promptFields.filter((p) => !ex.has(p));
    answerFields = answerFields.filter((a) => !ex.has(a));
  }

  function togglePrompt(name, on) {
    if (on) {
      promptFields = [...promptFields.filter((x) => x !== name), name];
      answerFields = answerFields.filter((x) => x !== name); // can't be both
    } else {
      promptFields = promptFields.filter((x) => x !== name);
    }
  }
  function toggleAnswer(name, on) {
    if (on) {
      answerFields = [...answerFields.filter((x) => x !== name), name];
      promptFields = promptFields.filter((x) => x !== name); // can't be both
    } else {
      answerFields = answerFields.filter((x) => x !== name);
    }
  }

  // play
  let quiz = $state(null);
  let i = $state(0);
  let picked = $state(null);
  let score = $state(0);
  let loading = $state(false);
  let error = $state(null);

  const q = $derived(quiz && i < quiz.questions.length ? quiz.questions[i] : null);
  const done = $derived(!!quiz && i >= quiz.questions.length);

  onMount(async () => {
    try {
      domains = (await getDomains()) ?? [];
      const first = domains.find((d) => d.types?.length);
      if (first) await selectType(first.types[0]);
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
      if (type !== t) return; // user switched again — ignore stale result
      fields = fs;

      const img = fields.find((f) => f.kind === 'image' || f.kind === 'images');
      const p = (img ?? fields[0])?.name;
      promptFields = p ? [p] : [];
      const txt = fields.find(
        (f) => !promptFields.includes(f.name) && (f.kind === 'text' || f.kind === 'refs' || f.kind === 'list')
      );
      const a = (txt ?? fields.find((f) => !promptFields.includes(f.name)) ?? fields[0])?.name;
      answerFields = a ? [a] : [];

      const [tree, dims] = await Promise.all([
        getGroups(t),
        getDimensions(t).catch(() => [])
      ]);
      if (type !== t) return;
      groupTree = tree;
      dimensions = dims ?? [];
      group = tree ? tree.fullName : '';
    } finally {
      if (type === t) loadingFields = false;
    }
  }

  async function startQuiz() {
    if (!type || !promptFields.length || !answerFields.length) return;
    loading = true;
    error = null;
    quiz = await getQuiz(type, {
      prompt: promptFields.join(','),
      ask: answerFields.join(','),
      n,
      group
    });
    i = 0;
    score = 0;
    picked = null;
    if (!quiz || !quiz.questions || quiz.questions.length === 0) {
      error = 'No questions could be generated for those fields — try different ones.';
      quiz = null;
    }
    loading = false;
  }

  function pick(opt) {
    if (picked) return;
    picked = opt;
    if (opt === q.answer) score++;
  }

  function next() {
    picked = null;
    i++;
  }

  function reconfigure() {
    quiz = null;
    picked = null;
  }
</script>

<div class="quiz">
  <header>
    <a class="back" href="/">← Browse</a>
    <span class="mode">ABCD quiz</span>
    <a class="alt" href="/pairing">Switch to Pairing →</a>
    {#if quiz && !done}
      <span class="progress">Question {i + 1} / {quiz.questions.length}</span>
      <span class="score">Score {score}</span>
    {/if}
  </header>

  <main>
    {#if loading}
      <p class="hint center">Loading…</p>
    {:else if error}
      <p class="error center">{error}</p>
    {:else if !quiz}
      <!-- config -->
      <div class="config">
        <h1>New quiz</h1>
        <label>
          Dataset
          <select value={type} onchange={(e) => selectType(e.currentTarget.value)}>
            {#each domains as d}
              <optgroup label={d.name}>
                {#each d.types as t}<option value={t}>{t}</option>{/each}
              </optgroup>
            {/each}
          </select>
        </label>
        {#if loadingFields}
          <p class="hint">Loading {type}…</p>
        {:else}
          {#if groupTree}
            <div class="group-field">
              <span class="field-label">Group</span>
              <div class="group-tree">
                <GroupTree node={groupTree} selected={group} onSelect={selectGroup} />
              </div>
            </div>
          {/if}
          <div class="fields">
            {#key type}
              <FieldPicker
                {type}
                {promptFields}
                {answerFields}
                exclude={excluded}
                onPrompt={togglePrompt}
                onAnswer={toggleAnswer}
              />
            {/key}
          </div>
          <label>
            Questions
            <input type="number" min="1" max="50" bind:value={n} />
          </label>
          <button class="primary" onclick={startQuiz} disabled={!promptFields.length || !answerFields.length}>Start</button>
        {/if}
      </div>
    {:else if done}
      <div class="results">
        <div class="big">{score} / {quiz.questions.length}</div>
        <p class="hint">{score === quiz.questions.length ? 'Perfect!' : 'Nice try.'}</p>
        <div class="row">
          <button class="primary" onclick={startQuiz}>Play again</button>
          <button class="ghost" onclick={reconfigure}>Change quiz</button>
        </div>
      </div>
    {:else if q}
      <div class="card">
        <div class="prompt">
          {#each q.prompts as p}
            {#if p.kind === 'image'}
              <ZoomableImage src={assetUrl(p.url)} alt="" />
            {:else if p.kind === 'images'}
              <ImageCarousel urls={p.values} maxWidth="240px" maxHeight="180px" />
            {:else if p.kind === 'list'}
              <div class="prompt-line"><span class="pf">{p.name}</span>{p.values.join(', ')}</div>
            {:else if p.kind === 'refs'}
              <div class="prompt-line"><span class="pf">{p.name}</span>{p.refs.map((r) => r.name).join(', ')}</div>
            {:else if p.kind === 'ref'}
              <div class="prompt-line"><span class="pf">{p.name}</span>{p.ref?.name ?? ''}</div>
            {:else}
              <div class="prompt-line"><span class="pf">{p.name}</span>{p.value ?? ''}</div>
            {/if}
          {/each}
        </div>
        <p class="ask">
          {q.prompts.length === 1 && (q.prompts[0].kind === 'image' || q.prompts[0].kind === 'images')
            ? 'Which one is this?'
            : `Which ${quiz.ask}?`}
        </p>

        <div class="options" class:image-options={q.optionImages}>
          {#each q.options as opt, oi}
            <div
              class="opt"
              class:correct={picked && opt === q.answer}
              class:wrong={picked === opt && opt !== q.answer}
              class:disabled={!!picked}
              role="button"
              tabindex="0"
              onclick={() => pick(opt)}
              onkeydown={(e) => e.key === 'Enter' && pick(opt)}
            >
              {#if q.optionImages && q.optionImages[oi] && q.optionImages[oi].length}
                <ImageCarousel urls={q.optionImages[oi]} maxWidth="100%" maxHeight="150px" />
              {:else}
                {opt}
              {/if}
            </div>
          {/each}
        </div>

        {#if picked}
          <button class="primary next" onclick={next}>
            {i + 1 < quiz.questions.length ? 'Next' : 'See results'}
          </button>
        {/if}
      </div>
    {/if}
  </main>
</div>

<style>
  .quiz { display: flex; flex-direction: column; min-height: 100vh; }
  header {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 12px 20px;
    border-bottom: 1px solid var(--line);
    background: var(--panel);
  }
  .back { font-weight: 500; }
  .mode { font-weight: 700; }
  .alt { font-weight: 500; color: var(--muted); }
  .progress { color: var(--muted); }
  .score { margin-left: auto; font-weight: 600; }

  main { flex: 1; display: flex; align-items: flex-start; justify-content: center; padding: 32px 20px; }

  .config {
    width: 100%;
    max-width: 420px;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
    padding: 22px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .config h1 { margin: 0 0 4px; font-size: 1.2rem; }
  .config label { display: flex; flex-direction: column; gap: 5px; font-size: 0.86rem; color: var(--muted); }
  .config select, .config input {
    padding: 8px 10px;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    background: #fff;
  }
  .group-field { display: flex; flex-direction: column; gap: 5px; font-size: 0.86rem; color: var(--muted); }
  .group-tree {
    max-height: 220px;
    overflow-y: auto;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    padding: 6px;
    background: #fff;
  }

  .fields {
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    padding: 6px 8px;
    max-height: 240px;
    overflow-y: auto;
  }
  .card {
    width: 100%;
    max-width: 460px;
    background: var(--panel);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
    padding: 22px;
    text-align: center;
  }
  .prompt {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    min-height: 160px;
    padding: 12px;
    background: #fff;
    border-radius: var(--radius-sm);
  }
  .prompt-line { font-size: 1.3rem; font-weight: 600; display: flex; flex-direction: column; gap: 2px; }
  .prompt-line .pf {
    font-size: 0.66rem;
    font-weight: 600;
    color: var(--faint);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .ask { color: var(--muted); margin: 14px 0 16px; }

  .options { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .opt {
    padding: 12px;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: #fff;
    font-weight: 500;
    cursor: pointer;
    text-align: center;
  }
  .opt:hover:not(.disabled) { border-color: var(--accent); background: var(--accent-weak); }
  .opt.disabled { cursor: default; pointer-events: none; }
  .opt.correct { background: #e7f7ec; border-color: #34a853; color: #14702f; }
  .opt.wrong { background: #fdecea; border-color: #d93025; color: #b3271e; }

  .primary {
    padding: 10px 18px;
    border-radius: var(--radius-sm);
    background: var(--accent);
    color: #fff;
    font-weight: 600;
  }
  .primary:disabled { opacity: 0.5; cursor: default; }
  .ghost { padding: 10px 18px; border-radius: var(--radius-sm); border: 1px solid var(--line-strong); }
  .next { margin-top: 18px; }

  .results { text-align: center; padding-top: 30px; }
  .results .big { font-size: 2.6rem; font-weight: 700; }
  .row { display: flex; gap: 10px; justify-content: center; margin-top: 14px; }

  .hint { color: var(--muted); }
  .center { text-align: center; padding-top: 40px; }
  .error { color: #b00020; }

  @media (max-width: 720px) {
    main { padding: 18px 14px; }
    .card, .config { padding: 16px 14px; }
    .options { grid-template-columns: 1fr; }
  }
</style>
