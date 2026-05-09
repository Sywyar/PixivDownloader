(() => {
  // ---------- canary detection ----------
  const canarySupported =
    typeof CanvasRenderingContext2D !== 'undefined' &&
    typeof CanvasRenderingContext2D.prototype.drawElementImage === 'function';
  if (canarySupported) {
    const banner = document.getElementById('canaryBanner');
    banner.classList.add('show');
    document.body.classList.add('banner-on');
    function closeBanner() {
      banner.classList.remove('show');
      document.body.classList.remove('banner-on');
    }
    banner.querySelector('.close').addEventListener('click', closeBanner);
    banner.querySelector('.banner-timer').addEventListener('animationend', closeBanner);
  }

  // ---------- deck controller ----------
  const deck = document.getElementById('deck');
  const slides = [...document.querySelectorAll('.slide')];
  const dotsWrap = document.getElementById('dots');
  const isMobile = matchMedia('(max-width:760px)').matches;
  let current = 0, locked = false;

  slides.forEach((s, i) => {
    const b = document.createElement('button');
    b.setAttribute('data-label', s.dataset.label || `S${i+1}`);
    b.addEventListener('click', () => go(i));
    dotsWrap.appendChild(b);
  });
  const dots = [...dotsWrap.children];

  function go(i) {
    i = Math.max(0, Math.min(slides.length - 1, i));
    if (i === current) return;
    current = i;
    if (!isMobile) deck.style.transform = `translateY(-${i * 100}vh)`;
    slides.forEach((s, idx) => s.classList.toggle('active', idx === i));
    dots.forEach((d, idx) => d.classList.toggle('active', idx === i));
    locked = true;
    setTimeout(() => { locked = false; }, 900);
  }

  slides[0].classList.add('active');
  dots[0].classList.add('active');

  if (!isMobile) {
    let wheelAccum = 0, wheelTimer;
    window.addEventListener('wheel', (e) => {
      e.preventDefault();
      if (locked) return;
      wheelAccum += e.deltaY;
      clearTimeout(wheelTimer);
      wheelTimer = setTimeout(() => (wheelAccum = 0), 160);
      if (wheelAccum > 40) { go(current + 1); wheelAccum = 0; }
      else if (wheelAccum < -40) { go(current - 1); wheelAccum = 0; }
    }, { passive: false });

    window.addEventListener('keydown', (e) => {
      if (['ArrowDown','PageDown',' '].includes(e.key)) { e.preventDefault(); go(current + 1); }
      else if (['ArrowUp','PageUp'].includes(e.key)) { e.preventDefault(); go(current - 1); }
      else if (e.key === 'Home') { e.preventDefault(); go(0); }
      else if (e.key === 'End') { e.preventDefault(); go(slides.length - 1); }
    });

    let touchStart = null;
    window.addEventListener('touchstart', (e) => { touchStart = e.touches[0].clientY; });
    window.addEventListener('touchend', (e) => {
      if (touchStart == null) return;
      const dy = touchStart - e.changedTouches[0].clientY;
      if (Math.abs(dy) > 60) go(current + (dy > 0 ? 1 : -1));
      touchStart = null;
    });
  }

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
})();
