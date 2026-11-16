(() => {
  'use strict';

  const model = window.PixivIntroModel;
  const root = document.documentElement;
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const narrowScreen = matchMedia('(max-width: 640px)');
  const finePointer = matchMedia('(hover: hover) and (pointer: fine)');
  const all = (selector, scope) => Array.from((scope || document).querySelectorAll(selector));

  // 每帧最多执行一次的调度器。
  function frameTask(task) {
    let queued = false;
    return () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => {
        queued = false;
        task();
      });
    };
  }

  // ---------- 顶部导航 ----------
  const nav = document.getElementById('nav');
  const syncNav = () => nav.classList.toggle('is-scrolled', window.scrollY > 8);

  // ---------- 首屏：散落的作品汇成画廊 ----------
  const scene = document.getElementById('top');
  const stage = document.getElementById('stage');
  const copy = stage.querySelector('.scene__copy');
  const tiles = all('.tile', stage);
  let sceneActive = false;
  let paintedProgress = -1;

  // 瓦片在网格中的中心点，相对舞台左上角；不受当前 transform 影响。
  function restingCenter(tile) {
    let x = tile.offsetWidth / 2;
    let y = tile.offsetHeight / 2;
    for (let node = tile; node && node !== stage; node = node.offsetParent) {
      x += node.offsetLeft;
      y += node.offsetTop;
    }
    return [x, y];
  }

  // 标题文案占据的区域（相对舞台），四周留出间距；散落的作品不进入这一区域。
  function copyBounds() {
    const origin = stage.getBoundingClientRect();
    const margin = narrowScreen.matches ? 12 : 28;
    const keep = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
    for (const child of copy.children) {
      const range = document.createRange();
      range.selectNodeContents(child);
      const rect = range.getBoundingClientRect();
      keep.left = Math.min(keep.left, rect.left - origin.left - margin);
      keep.top = Math.min(keep.top, rect.top - origin.top - margin);
      keep.right = Math.max(keep.right, rect.right - origin.left + margin);
      keep.bottom = Math.max(keep.bottom, rect.bottom - origin.top + margin);
    }
    return keep;
  }

  function layoutScene() {
    const width = stage.clientWidth;
    const height = stage.clientHeight;
    const keep = copyBounds();
    const visible = tiles.filter((tile) => tile.offsetParent !== null);
    const centers = visible.map(restingCenter);
    const slots = model.assignSlots(
      centers.map(([x, y]) => [x / width, y / height]),
      narrowScreen.matches ? model.NARROW_SLOTS : model.WIDE_SLOTS
    );
    visible.forEach((tile, index) => {
      const slot = slots[index];
      if (!slot) return;
      const [x, y] = centers[index];
      const target = model.clearOf(
        [slot[0] * width, slot[1] * height],
        [tile.offsetWidth * slot[2] / 2, tile.offsetHeight * slot[2] / 2],
        keep
      );
      tile.style.setProperty('--dx', `${(target[0] - x).toFixed(1)}px`);
      tile.style.setProperty('--dy', `${(target[1] - y).toFixed(1)}px`);
      tile.style.setProperty('--ds', (slot[2] - 1).toFixed(3));
      tile.style.setProperty('--rot', `${slot[3]}deg`);
      tile.style.setProperty('--depth', slot[2].toFixed(2));
      tile.style.zIndex = String(Math.round(slot[2] * 10));
      // 离画面中心越远的作品越晚归位。
      tile.dataset.order = Math.min(1, Math.hypot(slot[0] - 0.5, slot[1] - 0.5) / 0.75).toFixed(3);
      tile.style.setProperty('--bob-delay', `${(-index * 0.83).toFixed(2)}s`);
      tile.style.setProperty('--in-delay', `${(0.25 + index * 0.035).toFixed(3)}s`);
    });
  }

  function paintScene() {
    const rect = scene.getBoundingClientRect();
    const progress = model.scrollProgress(rect.top, rect.height, window.innerHeight);
    // 滚过首屏后进度不再变化，无需重复写入样式。
    if (progress === paintedProgress) return;
    paintedProgress = progress;
    const state = model.sceneState(progress);
    stage.style.setProperty('--copy', state.copy.toFixed(3));
    stage.style.setProperty('--chrome', state.chrome.toFixed(3));
    stage.style.setProperty('--caption', state.caption.toFixed(3));
    copy.classList.toggle('is-live', state.copy > 0.6);
    let loose = 0;
    for (const tile of tiles) {
      const value = model.looseness(progress, Number(tile.dataset.order || 0));
      tile.style.setProperty('--l', value.toFixed(4));
      loose += value;
    }
    // 全部归位后停掉浮动动画。
    stage.classList.toggle('is-settled', loose === 0);
  }

  function resetScene() {
    for (const name of ['--copy', '--chrome', '--caption', '--mx', '--my']) stage.style.removeProperty(name);
    stage.classList.remove('is-settled');
    paintedProgress = -1;
    for (const tile of tiles) {
      for (const name of ['--l', '--dx', '--dy', '--ds', '--rot', '--depth', '--bob-delay', '--in-delay']) {
        tile.style.removeProperty(name);
      }
      tile.style.zIndex = '';
    }
  }

  const refreshScene = frameTask(() => {
    if (!sceneActive) return;
    layoutScene();
    paintedProgress = -1;
    paintScene();
  });
  const repaintScene = frameTask(() => {
    if (sceneActive) paintScene();
  });

  // 指针视差：只在精确指针设备上启用，数值经过平滑。
  const pointer = { x: 0, y: 0, targetX: 0, targetY: 0, running: false };
  function stepPointer() {
    pointer.x += (pointer.targetX - pointer.x) * 0.08;
    pointer.y += (pointer.targetY - pointer.y) * 0.08;
    stage.style.setProperty('--mx', pointer.x.toFixed(4));
    stage.style.setProperty('--my', pointer.y.toFixed(4));
    const settled = Math.abs(pointer.targetX - pointer.x) < 0.001 && Math.abs(pointer.targetY - pointer.y) < 0.001;
    pointer.running = sceneActive && !settled;
    if (pointer.running) requestAnimationFrame(stepPointer);
  }
  stage.addEventListener('pointermove', (event) => {
    if (!sceneActive || !finePointer.matches) return;
    pointer.targetX = event.clientX / window.innerWidth * 2 - 1;
    pointer.targetY = event.clientY / window.innerHeight * 2 - 1;
    if (!pointer.running) {
      pointer.running = true;
      requestAnimationFrame(stepPointer);
    }
  });

  // ---------- 进入视口时显现 ----------
  let revealObserver = null;
  function watchReveals() {
    if (revealObserver) revealObserver.disconnect();
    revealObserver = null;
    const targets = all('[data-reveal]');
    if (reducedMotion.matches || typeof IntersectionObserver === 'undefined') {
      targets.forEach((target) => target.classList.add('is-in'));
      return;
    }
    revealObserver = new IntersectionObserver((entries) => {
      const entering = entries.filter((entry) => entry.isIntersecting);
      entering.forEach((entry, index) => {
        entry.target.style.transitionDelay = `${index * 70}ms`;
        entry.target.classList.add('is-in');
        revealObserver.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -12% 0px', threshold: 0.08 });
    targets.filter((target) => !target.classList.contains('is-in')).forEach((target) => revealObserver.observe(target));
  }

  function applyMotionPreference() {
    sceneActive = !reducedMotion.matches;
    root.classList.toggle('motion', sceneActive);
    if (sceneActive) {
      layoutScene();
      paintScene();
    } else {
      resetScene();
    }
    watchReveals();
  }

  // ---------- 获取：来源切换 ----------
  const sources = document.getElementById('sources');
  const sourceTabs = all('.source', sources);
  const queue = document.getElementById('queue');
  const queueValue = document.getElementById('queueValue');
  const queueIcon = queue.querySelector('.queue__input use');

  function showSource(tab, focus) {
    sourceTabs.forEach((item) => {
      const active = item === tab;
      item.classList.toggle('is-active', active);
      item.setAttribute('aria-selected', String(active));
      item.tabIndex = active ? 0 : -1;
    });
    queue.setAttribute('aria-labelledby', tab.id);
    queueValue.textContent = tab.querySelector('.source__sample').textContent;
    queueIcon.setAttribute('href', tab.querySelector('use').getAttribute('href'));
    // 重新触发队列动画。
    queue.classList.remove('is-running');
    void queue.offsetWidth;
    queue.classList.add('is-running');
    if (focus) tab.focus();
  }

  function stepSource(offset) {
    const current = sourceTabs.findIndex((tab) => tab.classList.contains('is-active'));
    return sourceTabs[(current + offset + sourceTabs.length) % sourceTabs.length];
  }

  sources.addEventListener('click', (event) => {
    const tab = event.target.closest('.source');
    if (!tab) return;
    sources.classList.remove('is-auto');
    showSource(tab, false);
  });
  sources.addEventListener('keydown', (event) => {
    const offset = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (!offset) return;
    event.preventDefault();
    sources.classList.remove('is-auto');
    showSource(stepSource(offset), true);
  });
  // 自动轮播由当前项的进度条动画驱动；进度条走完即切到下一项。
  sources.addEventListener('animationend', (event) => {
    if (event.animationName === 'source-fill' && sources.classList.contains('is-auto')) showSource(stepSource(1), false);
  });
  for (const type of ['pointerenter', 'focusin']) sources.addEventListener(type, () => sources.classList.add('is-paused'));
  for (const type of ['pointerleave', 'focusout']) sources.addEventListener(type, () => sources.classList.remove('is-paused'));

  // 第一次进入视口时开始自动轮播；离开视口期间暂停。
  if (typeof IntersectionObserver !== 'undefined') {
    let started = false;
    new IntersectionObserver(([entry]) => {
      sources.classList.toggle('is-idle', !entry.isIntersecting);
      if (!entry.isIntersecting || started || reducedMotion.matches) return;
      started = true;
      sources.classList.add('is-auto');
      queue.classList.add('is-running');
    }, { threshold: 0.35 }).observe(queue);
  }

  // ---------- 画廊：三态标签 ----------
  const demo = document.getElementById('demo');
  const tagButtons = all('.tag', demo);
  const pieces = all('.piece', demo);
  const shownCount = document.getElementById('demoShown');
  const strings = document.getElementById('introStrings');
  const stateName = (state) => strings.querySelector(`[data-string="${state}"]`).textContent;

  function applyTagFilter() {
    const states = {};
    tagButtons.forEach((button) => {
      const state = button.dataset.state || 'ignore';
      states[button.dataset.tag] = state;
      button.setAttribute('aria-label', `${button.textContent.trim()} · ${stateName(state)}`);
    });
    let shown = 0;
    pieces.forEach((piece) => {
      const match = model.matchesTags(piece.dataset.tags.split(' '), states);
      piece.classList.toggle('is-out', !match);
      if (match) shown += 1;
    });
    shownCount.textContent = String(shown);
  }

  demo.addEventListener('click', (event) => {
    const button = event.target.closest('.tag');
    if (!button) return;
    button.dataset.state = model.nextTagState(button.dataset.state || 'ignore');
    applyTagFilter();
  });
  document.getElementById('demoTotal').textContent = String(pieces.length);

  // ---------- 语言与主题 ----------
  let pageI18n = null;

  function renderLanguages() {
    const container = document.getElementById('langs');
    container.replaceChildren(...(pageI18n.supportedLocales || []).map((locale) => {
      const item = document.createElement('span');
      item.textContent = locale.displayName || locale.tag;
      item.classList.toggle('is-current', locale.tag === pageI18n.lang);
      return item;
    }));
  }

  function applyTranslations() {
    pageI18n.apply(document.body);
    document.title = pageI18n.t('page.title', document.title);
    const description = document.querySelector('meta[name="description"]');
    description.setAttribute('content', pageI18n.t('page.description', description.getAttribute('content')));
    renderLanguages();
    applyTagFilter();
    const active = sourceTabs.find((tab) => tab.classList.contains('is-active'));
    queueValue.textContent = active.querySelector('.source__sample').textContent;
    // 文案长度随语言变化，首屏需要重新测量。
    refreshScene();
  }

  async function initI18n() {
    if (typeof PixivI18n === 'undefined') return;
    try {
      pageI18n = await PixivI18n.create({ namespaces: ['intro', 'common'] });
      if (typeof PixivLangSwitcher !== 'undefined') {
        await PixivLangSwitcher.mount({
          mountPoint: document.getElementById('langAnchor'),
          i18n: pageI18n,
          variant: 'intro',
          onChange: (nextClient) => {
            pageI18n = nextClient;
            applyTranslations();
          }
        });
      }
      applyTranslations();
    } catch (error) {
      // 语言资源不可用时保留页面内置的源语言文案。
      console.warn('Intro page i18n initialization failed', error);
    }
  }

  // ---------- 启动 ----------
  applyTagFilter();
  applyMotionPreference();
  syncNav();
  window.addEventListener('scroll', () => {
    syncNav();
    repaintScene();
  }, { passive: true });
  window.addEventListener('resize', refreshScene);
  window.addEventListener('load', refreshScene);
  reducedMotion.addEventListener('change', applyMotionPreference);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(refreshScene);
  if (typeof PixivTheme !== 'undefined') {
    PixivTheme.mount({ mountPoint: document.getElementById('themeAnchor'), variant: 'intro' });
  }
  initI18n();
})();
