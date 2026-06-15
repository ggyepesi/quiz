<script>
  import { onMount } from 'svelte';
  import { getTypes, getFields, getGroups, getQuiz, assetUrl } from '$lib/api.js';

  // config
  let types = $state([]);
  let type = $state('');
  let fields = $state([]);
  let groups = $state([]);
  let group = $state('');
  let prompt = $state('');
  let ask = $state('');
  let n = $state(10);

  function flattenGroups(node, depth, out) {
    if (!node) return;
    out.push({
      fullName: node.fullName,
      label: '  '.repeat(depth) + node.name,
      count: node.count
    });
    for (const c of node.children ?? []) flattenGroups(c, depth + 1, out);
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
      types = (await getTypes()) ?? [];
      if (types.length) await selectType(types[0]);
    } catch (e) {
      error = 'Cannot reach the API. Is QuizableServerMain running?';
    }
  });

  async function selectType(t) {
    type = t;
    fields = (await getFields(t)) ?? [];
    const img = fields.find((f) => f.kind === 'image' || f.kind === 'images');
    prompt = (img ?? fields[0])?.name ?? '';
    const txt = fields.find((f) => f.name !== prompt && (f.kind === 'text' || f.kind === 'refs' || f.kind === 'list'));
    ask = (txt ?? fields.find((f) => f.name !== prompt) ?? fields[0])?.name ?? '';

    const tree = await getGroups(t);
    const flat = [];
    flattenGroups(tree, 0, flat);
    groups = flat;
    group = ''; // default: whole dataset
  }

  async function startQuiz() {
    if (!type || !prompt || !ask) return;
    loading = true;
    error = null;
    quiz = await getQuiz(type, { prompt, ask, n, group });
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
            {#each types as t}<option value={t}>{t}</option>{/each}
          </select>
        </label>
        {#if groups.length}
          <label>
            Group
            <select bind:value={group}>
              <option value="">All ({type})</option>
              {#each groups as g}
                <option value={g.fullName}>{g.label} ({g.count})</option>
              {/each}
            </select>
          </label>
        {/if}
        <label>
          Show (prompt)
          <select bind:value={prompt}>
            {#each fields as f}<option value={f.name}>{f.name} · {f.kind}</option>{/each}
          </select>
        </label>
        <label>
          Guess (answer)
          <select bind:value={ask}>
            {#each fields as f}<option value={f.name}>{f.name} · {f.kind}</option>{/each}
          </select>
        </label>
        <label>
          Questions
          <input type="number" min="1" max="50" bind:value={n} />
        </label>
        <button class="primary" onclick={startQuiz} disabled={!prompt || !ask}>Start</button>
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
          {#if q.prompt.kind === 'image'}
            <img src={assetUrl(q.prompt.url)} alt="" />
          {:else if q.prompt.kind === 'images'}
            <img src={assetUrl(q.prompt.values[0])} alt="" />
          {:else if q.prompt.kind === 'list'}
            <div class="prompt-text">{q.prompt.values.join(', ')}</div>
          {:else}
            <div class="prompt-text">{q.prompt.value ?? ''}</div>
          {/if}
        </div>
        <p class="ask">
          {q.prompt.kind === 'image' || q.prompt.kind === 'images' ? 'Which one is this?' : `Which ${quiz.ask}?`}
        </p>

        <div class="options">
          {#each q.options as opt}
            <button
              class="opt"
              class:correct={picked && opt === q.answer}
              class:wrong={picked === opt && opt !== q.answer}
              disabled={!!picked}
              onclick={() => pick(opt)}
            >
              {opt}
            </button>
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
    align-items: center;
    justify-content: center;
    min-height: 160px;
    padding: 8px;
    background: #fff;
    border-radius: var(--radius-sm);
  }
  .prompt img { max-width: 220px; max-height: 170px; object-fit: contain; }
  .prompt-text { font-size: 1.4rem; font-weight: 600; }
  .ask { color: var(--muted); margin: 14px 0 16px; }

  .options { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .opt {
    padding: 12px;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-sm);
    background: #fff;
    font-weight: 500;
  }
  .opt:hover:not(:disabled) { border-color: var(--accent); background: var(--accent-weak); }
  .opt:disabled { cursor: default; }
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
    .prompt img { max-width: 180px; max-height: 150px; }
  }
</style>
