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
    for (const tile of tiles) {
      tile.style.setProperty('--l', model.looseness(progress, Number(tile.dataset.order || 0)).toFixed(4));
    }
    // 浮动动画只在首屏起始位置播放；其关键帧依赖 --l，滚动期间播放会让每一帧都重新计算全部瓦片。
    stage.classList.toggle('is-floating', progress === 0);
  }

  function resetScene() {
    for (const name of ['--copy', '--chrome', '--caption', '--mx', '--my']) stage.style.removeProperty(name);
    stage.classList.remove('is-floating');
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

  // ---------- 吸附翻页 ----------
  // 停靠点：首屏起止两种完整状态、各章节（分屏章节按每一屏）顶部与页尾；高于视口时，内部按原生方式自由滚动。
  // 滚轮与翻页键由这里接管并以统一的缓动翻页；触摸、拖动滚动条等原生滚动停下后再归位到停靠点。
  const snapRegions = all('main > .chapter:not(.chapter--paged), main > .chapter--paged > .chapter__page, main > .finale');
  const WHEEL_GESTURE_GAP = 180;
  const SETTLE_DELAY = 140;
  const SETTLE_COMMIT = 0.12;
  const userScrollWindow = 1000;
  let snapAnimation = null;
  let wheelMode = null;
  let lastWheel = -Infinity;
  let lastUserScroll = -Infinity;
  let lastScrollY = window.scrollY;
  let scrollDirection = 0;
  let touching = false;
  let settleTimer = 0;

  const maxScroll = () => Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
  const documentTop = (element) => element.getBoundingClientRect().top + window.scrollY;

  function snapLayout() {
    const viewport = window.innerHeight;
    const sceneTop = documentTop(scene);
    const regions = [
      { top: sceneTop, height: 0 },
      { top: sceneTop + scene.offsetHeight - viewport, height: 0 }
    ];
    for (const region of snapRegions) {
      regions.push({ top: documentTop(region), height: region.offsetHeight, free: true });
    }
    return model.snapLayout(regions, viewport, maxScroll());
  }

  function stepSnapAnimation(now) {
    const animation = snapAnimation;
    if (!animation) return;
    const t = Math.min(1, (now - animation.start) / animation.duration);
    window.scrollTo({ top: animation.from + (animation.to - animation.from) * model.snapEase(t), behavior: 'instant' });
    // 同一帧内同步首屏，避免滚动事件晚一帧才更新画面。
    if (sceneActive) paintScene();
    if (t < 1) {
      animation.frame = requestAnimationFrame(stepSnapAnimation);
    } else {
      snapAnimation = null;
    }
  }

  function snapTo(target) {
    const to = Math.round(Math.min(Math.max(target, 0), maxScroll()));
    const from = window.scrollY;
    cancelSnap();
    if (Math.abs(to - from) < 1) return;
    snapAnimation = { from, to, start: performance.now(), duration: model.snapDuration(to - from, window.innerHeight) };
    snapAnimation.frame = requestAnimationFrame(stepSnapAnimation);
  }

  function cancelSnap() {
    if (snapAnimation) cancelAnimationFrame(snapAnimation.frame);
    snapAnimation = null;
  }

  // 正在翻页时，后续操作以动画终点为起点，连按可以连续翻过多页。
  const snapOrigin = () => (snapAnimation ? snapAnimation.to : window.scrollY);

  function step(direction) {
    const target = model.nextStop(snapLayout(), snapOrigin(), direction);
    if (target !== null) snapTo(target);
  }

  window.addEventListener('wheel', (event) => {
    if (!sceneActive || event.ctrlKey || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
    const now = performance.now();
    // 同一次手势（含触控板惯性）的事件间隔很短；间隔拉开后才算新的手势。
    if (now - lastWheel > WHEEL_GESTURE_GAP && !snapAnimation) wheelMode = null;
    lastWheel = now;
    if (wheelMode === 'snapped') {
      event.preventDefault();
      return;
    }
    const direction = Math.sign(event.deltaY);
    const layout = snapLayout();
    const range = model.freeRange(layout, window.scrollY);
    if (range && model.hasRoom(range, window.scrollY, direction)) {
      // 长章节内部交给原生滚动；滚到边缘时停住，下一次手势再翻页。
      wheelMode = 'native';
      lastUserScroll = now;
      return;
    }
    event.preventDefault();
    if (wheelMode === 'native') return;
    wheelMode = 'snapped';
    step(direction);
  }, { passive: false });

  const PAGE_KEYS = { PageDown: 1, PageUp: -1, ' ': 1 };
  const ARROW_KEYS = { ArrowDown: 1, ArrowUp: -1 };

  const isElementWithin = (target, selector) => target instanceof Element && Boolean(target.closest(selector));
  const FIELDS = 'input, textarea, select, [contenteditable]';
  // 空格在按钮、链接等控件上用于激活。
  const ACTIVATES_ON_SPACE = `${FIELDS}, button, a[href], summary, [role="tab"]`;

  window.addEventListener('keydown', (event) => {
    if (!sceneActive || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
    const key = event.key;
    if (isElementWithin(event.target, key === ' ' ? ACTIVATES_ON_SPACE : FIELDS)) return;
    if (key === 'Home' || key === 'End') {
      event.preventDefault();
      snapTo(key === 'Home' ? 0 : maxScroll());
      return;
    }
    const page = PAGE_KEYS[key];
    const arrow = ARROW_KEYS[key];
    if (!page && !arrow) return;
    const direction = page ? (key === ' ' && event.shiftKey ? -1 : page) : arrow;
    const origin = snapOrigin();
    const layout = snapLayout();
    const range = model.freeRange(layout, origin);
    if (range && model.hasRoom(range, origin, direction)) {
      if (arrow && !snapAnimation) {
        lastUserScroll = performance.now();
        return;
      }
      // 长章节内部按一屏的大部分翻动，不越过章节边缘。
      event.preventDefault();
      const next = origin + direction * window.innerHeight * 0.85;
      snapTo(direction > 0 ? Math.min(next, range[1]) : Math.max(next, range[0]));
      return;
    }
    event.preventDefault();
    step(direction);
  });

  // 页内导航以同样的方式翻到对应章节；跳到正文等其它锚点保持原生行为。
  document.addEventListener('click', (event) => {
    if (!sceneActive || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('a[href^="#"]');
    const target = link && document.getElementById(link.getAttribute('href').slice(1));
    if (!target || (target !== scene && !target.matches('main > section'))) return;
    event.preventDefault();
    snapTo(documentTop(target));
    if (location.hash !== link.getAttribute('href')) history.pushState(null, '', link.getAttribute('href'));
  });

  // 原生滚动（触摸、拖动滚动条、方向键、长章节越过边缘）停下后，归位到停靠点。
  function settle() {
    settleTimer = 0;
    if (!sceneActive || snapAnimation || touching || performance.now() - lastUserScroll > userScrollWindow) return;
    const target = model.settleStop(snapLayout(), window.scrollY, scrollDirection, SETTLE_COMMIT);
    if (target !== null) snapTo(target);
  }

  function trackScroll() {
    const y = window.scrollY;
    if (y !== lastScrollY) scrollDirection = Math.sign(y - lastScrollY);
    lastScrollY = y;
    if (snapAnimation) return;
    clearTimeout(settleTimer);
    settleTimer = setTimeout(settle, SETTLE_DELAY);
  }

  window.addEventListener('touchstart', () => {
    touching = true;
    cancelSnap();
  }, { passive: true });
  for (const type of ['touchend', 'touchcancel']) {
    window.addEventListener(type, (event) => {
      touching = event.touches.length > 0;
      lastUserScroll = performance.now();
      if (!touching && !settleTimer) settleTimer = setTimeout(settle, SETTLE_DELAY);
    }, { passive: true });
  }
  // 按下鼠标（例如拖动滚动条）会打断正在进行的翻页，松开后再归位。
  window.addEventListener('pointerdown', (event) => {
    if (event.pointerType !== 'mouse') return;
    cancelSnap();
    lastUserScroll = performance.now();
  });
  window.addEventListener('pointerup', (event) => {
    if (event.pointerType === 'mouse') lastUserScroll = performance.now();
  });

  function applyMotionPreference() {
    sceneActive = !reducedMotion.matches;
    if (!sceneActive) cancelSnap();
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
    trackScroll();
  }, { passive: true });
  window.addEventListener('resize', () => {
    refreshScene();
    // 视口变化后停靠点随之移动，不在自由区间内时直接对齐最近的停靠点。
    if (!sceneActive || snapAnimation) return;
    requestAnimationFrame(() => {
      const target = model.settleStop(snapLayout(), window.scrollY, 0, SETTLE_COMMIT);
      if (target !== null) window.scrollTo({ top: target, behavior: 'instant' });
    });
  });
  window.addEventListener('load', refreshScene);
  reducedMotion.addEventListener('change', applyMotionPreference);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(refreshScene);
  if (typeof PixivTheme !== 'undefined') {
    PixivTheme.mount({ mountPoint: document.getElementById('themeAnchor'), variant: 'intro' });
  }
  initI18n();
})();
