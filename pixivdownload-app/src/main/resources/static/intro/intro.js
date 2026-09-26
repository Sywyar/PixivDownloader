(() => {
  // 原生滚动允许长内容、文字缩放与窗口重排共用同一组页面。
  const deck = document.getElementById('deck');
  const slides = [...document.querySelectorAll('.slide')];
  const dotsWrap = document.getElementById('dots');

  slides.forEach((s, i) => {
    const b = document.createElement('button');
    b.setAttribute('data-label', s.dataset.label || `S${i+1}`);
    b.addEventListener('click', () => go(i));
    dotsWrap.appendChild(b);
  });
  const dots = [...dotsWrap.children];

  function go(i) {
    i = Math.max(0, Math.min(slides.length - 1, i));
    slides[i].scrollIntoView({behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'});
  }

  slides[0].classList.add('active');
  dots[0].classList.add('active');

  const slideObserver = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const index = slides.indexOf(entry.target);
      entry.target.classList.add('active');
      dots.forEach((dot, i) => dot.classList.toggle('active', i === index));
    }
  }, {root: deck, rootMargin: '-40% 0px -40% 0px'});
  slides.forEach(slide => slideObserver.observe(slide));

  document.querySelectorAll('[data-goto]').forEach((el) => {
    el.addEventListener('click', (e) => { e.preventDefault(); go(+el.dataset.goto); });
  });

  // ---------- global tooltip ----------
  const tip = document.getElementById('gtip');
  let tipTarget = null;
  function showTip(el, x, y) {
    const text = el.dataset.tip;
    if (!text) return;
    tip.textContent = text;
    tip.classList.add('show');
    positionTip(x, y);
  }
  function hideTip() { tip.classList.remove('show'); tipTarget = null; }
  function positionTip(x, y) {
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let px = x + 14, py = y + 18;
    if (px + w > innerWidth - 10) px = x - w - 14;
    if (py + h > innerHeight - 10) py = y - h - 14;
    tip.style.left = px + 'px';
    tip.style.top = py + 'px';
  }
  document.addEventListener('pointerover', (e) => {
    const el = e.target.closest('[data-tip]');
    if (el && el !== tipTarget) { tipTarget = el; showTip(el, e.clientX, e.clientY); }
  });
  document.addEventListener('pointermove', (e) => {
    if (tipTarget) positionTip(e.clientX, e.clientY);
  });
  document.addEventListener('pointerout', (e) => {
    if (tipTarget && !tipTarget.contains(e.relatedTarget)) hideTip();
  });

  // ---------- i18n ----------
  let pageI18n = null;

  function applyDynamicTranslations() {
    if (!pageI18n) return;
    // data-tip tooltips: read data-i18n-tip key, translate, write back to data-tip
    document.querySelectorAll('[data-i18n-tip]').forEach((el) => {
      const key = el.getAttribute('data-i18n-tip');
      if (!key) return;
      const fallback = el.getAttribute('data-tip') || '';
      el.setAttribute('data-tip', pageI18n.t(key, fallback));
    });
    // gallery search box value
    document.querySelectorAll('[data-i18n-value]').forEach((el) => {
      const key = el.getAttribute('data-i18n-value');
      if (!key) return;
      el.value = pageI18n.t(key, el.value);
    });
  }

  function applyPageTranslations() {
    if (!pageI18n) return;
    pageI18n.apply(document.body);
    document.title = pageI18n.t('page.title', document.title);
    applyDynamicTranslations();
    // refresh dot labels (S1/S2... unless slide.dataset.label is set)
    dots.forEach((d, idx) => {
      const slide = slides[idx];
      if (slide && slide.dataset.label) d.setAttribute('data-label', slide.dataset.label);
    });
  }

  async function initI18n() {
    if (typeof PixivI18n === 'undefined') return;
    try {
      pageI18n = await PixivI18n.create({ namespaces: ['intro', 'common'] });
      const anchor = document.getElementById('langSwitcherAnchor');
      if (anchor && typeof PixivLangSwitcher !== 'undefined') {
        await PixivLangSwitcher.mount({
          mountPoint: anchor,
          i18n: pageI18n,
          variant: 'intro',
          onChange: (nextClient) => {
            pageI18n = nextClient;
            applyPageTranslations();
          }
        });
      }
      applyPageTranslations();
    } catch (e) {
      // i18n initialization failed; page falls back to inline Chinese text
      console && console.warn && console.warn(pageI18n ? pageI18n.t('intro:log.i18n-init-failed', 'i18n 初始化失败') : 'i18n 初始化失败', e);
    }
  }

  initI18n();
})();
