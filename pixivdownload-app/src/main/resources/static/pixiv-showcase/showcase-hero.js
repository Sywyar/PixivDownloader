'use strict';

    const COMFORT_COLORS = [
        { name: 'Pearl White',     hex: '#F0EDE5', r: 240, g: 237, b: 229 },
        { name: 'Warm Ivory',      hex: '#E8E0D0', r: 232, g: 224, b: 208 },
        { name: 'Soft Cream',      hex: '#F5E6CC', r: 245, g: 230, b: 204 },
        { name: 'Blush Pink',      hex: '#F2D7D5', r: 242, g: 215, b: 213 },
        { name: 'Rose Mist',       hex: '#E8C4C4', r: 232, g: 196, b: 196 },
        { name: 'Coral Kiss',      hex: '#F0B8A8', r: 240, g: 184, b: 168 },
        { name: 'Peach Bloom',     hex: '#F5C6AA', r: 245, g: 198, b: 170 },
        { name: 'Salmon Glow',     hex: '#E8A598', r: 232, g: 165, b: 152 },
        { name: 'Lavender Haze',   hex: '#D4C5E2', r: 212, g: 197, b: 226 },
        { name: 'Wisteria Dream',  hex: '#C4B0D8', r: 196, g: 176, b: 216 },
        { name: 'Lilac Whisper',   hex: '#DCD0F0', r: 220, g: 208, b: 240 },
        { name: 'Sky Breath',      hex: '#B8D4E8', r: 184, g: 212, b: 232 },
        { name: 'Arctic Blue',     hex: '#A8C8E0', r: 168, g: 200, b: 224 },
        { name: 'Ocean Foam',      hex: '#9CC8D8', r: 156, g: 200, b: 216 },
        { name: 'Mint Cloud',      hex: '#B8E0D0', r: 184, g: 224, b: 208 },
        { name: 'Sage Breeze',     hex: '#A8D0B8', r: 168, g: 208, b: 184 },
        { name: 'Spring Dew',      hex: '#C0E0A8', r: 192, g: 224, b: 168 },
        { name: 'Lemon Sorbet',    hex: '#F0E8A0', r: 240, g: 232, b: 160 },
        { name: 'Honey Glow',      hex: '#E8D8A0', r: 232, g: 216, b: 160 },
        { name: 'Champagne Gold',  hex: '#E0D0A8', r: 224, g: 208, b: 168 },
        { name: 'Silver Frost',    hex: '#C8D0D8', r: 200, g: 208, b: 216 },
        { name: 'Slate Moon',      hex: '#A8B8C8', r: 168, g: 184, b: 200 },
        { name: 'Dusty Rose',      hex: '#D4A8A8', r: 212, g: 168, b: 168 },
        { name: 'Mauve Dusk',      hex: '#C8A0B8', r: 200, g: 160, b: 184 },
    ];

    function colorDistance(c1, c2) {
        const dr = c1.r - c2.r;
        const dg = c1.g - c2.g;
        const db = c1.b - c2.b;
        return dr * dr + dg * dg + db * db;
    }

    function invertColor(r, g, b) {
        return { r: 255 - r, g: 255 - g, b: 255 - b };
    }

    function findClosestPaletteColor(r, g, b) {
        const target = { r, g, b };
        let closest = COMFORT_COLORS[0];
        let minDist = colorDistance(target, closest);
        for (let i = 1; i < COMFORT_COLORS.length; i++) {
            const d = colorDistance(target, COMFORT_COLORS[i]);
            if (d < minDist) {
                minDist = d;
                closest = COMFORT_COLORS[i];
            }
        }
        return closest;
    }

    function extractAverageColor(imgElement) {
        try {
            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            const size = 32;
            canvas.width = size;
            canvas.height = size;
            ctx.drawImage(imgElement, 0, 0, size, size);
            const data = ctx.getImageData(0, 0, size, size).data;
            let rSum = 0, gSum = 0, bSum = 0, count = 0;
            for (let i = 0; i < data.length; i += 4) {
                rSum += data[i];
                gSum += data[i + 1];
                bSum += data[i + 2];
                count++;
            }
            return {
                r: Math.round(rSum / count),
                g: Math.round(gSum / count),
                b: Math.round(bSum / count),
            };
        } catch (e) {
            return null;
        }
    }

    function applyHeroAccentColor(avgColor) {
        const inverted = invertColor(avgColor.r, avgColor.g, avgColor.b);
        const palette = findClosestPaletteColor(inverted.r, inverted.g, inverted.b);
        const root = document.documentElement;
        root.style.setProperty('--hero-accent', palette.hex);
        root.style.setProperty('--hero-accent-glow', palette.hex + '4D');
        root.style.setProperty('--hero-accent-bg', palette.hex + '14');
        const textR = Math.min(255, palette.r + 10);
        const textG = Math.min(255, palette.g + 10);
        const textB = Math.min(255, palette.b + 10);
        root.style.setProperty('--hero-text', `rgb(${textR}, ${textG}, ${textB})`);
        const secR = Math.round(palette.r * 0.85);
        const secG = Math.round(palette.g * 0.85);
        const secB = Math.round(palette.b * 0.85);
        root.style.setProperty('--hero-text-secondary', `rgb(${secR}, ${secG}, ${secB})`);
        const mutR = Math.round(palette.r * 0.65);
        const mutG = Math.round(palette.g * 0.65);
        const mutB = Math.round(palette.b * 0.65);
        root.style.setProperty('--hero-muted', `rgb(${mutR}, ${mutG}, ${mutB})`);
    }

    function triggerHeroAnimations() {
        const artwork = document.getElementById('heroArtwork');
        const label = document.querySelector('.hero-label');
        const title = document.getElementById('heroTitle');
        const author = document.getElementById('heroAuthor');
        const desc = document.getElementById('heroDesc');
        const tags = document.getElementById('heroTags');

        requestAnimationFrame(() => {
            artwork.classList.add('animate-in');
            setTimeout(() => label.classList.add('animate-in'), 200);
            setTimeout(() => title.classList.add('animate-in'), 400);
            setTimeout(() => author.classList.add('animate-in'), 600);
            setTimeout(() => desc.classList.add('animate-in'), 800);
            setTimeout(() => tags.classList.add('animate-in'), 1000);
        });
    }

    async function renderHero() {
        const a = state.artwork;
        const thumbUrl = `/api/downloaded/thumbnail/${a.artworkId}/0`;

        setBgImage(document.getElementById('heroBg'), thumbUrl);
        const heroSrc = await setImage(document.getElementById('heroArtworkImg'), thumbUrl);

        if (heroSrc) {
            const imgEl = document.getElementById('heroArtworkImg');
            if (imgEl.complete && imgEl.naturalWidth > 0) {
                const avg = extractAverageColor(imgEl);
                if (avg) applyHeroAccentColor(avg);
            } else {
                imgEl.addEventListener('load', () => {
                    const avg = extractAverageColor(imgEl);
                    if (avg) applyHeroAccentColor(avg);
                }, { once: true });
            }
        }

        document.getElementById('heroTitle').textContent = localizedArtworkTitle(a);

        const badges = [];
        if (a.xRestrict === 2) badges.push('<span class="hero-badge r18">R-18G</span>');
        else if (a.xRestrict === 1) badges.push('<span class="hero-badge r18">R-18</span>');
        if (a.isAi) badges.push('<span class="hero-badge ai">AI</span>');
        if ((a.count || 1) > 1) badges.push(`<span class="hero-badge pages">${a.count}P</span>`);
        document.getElementById('heroBadges').innerHTML = badges.join('');

        const name = a.authorName || wt('artist.fallback-name', 'Artist {id}', {id: a.authorId || ''});
        document.getElementById('heroAuthorAvatar').textContent = (name[0] || '?').toUpperCase();
        document.getElementById('heroAuthorName').textContent = name;
        document.getElementById('heroAuthorId').textContent = a.authorId ? `ID: ${a.authorId}` : '';

        const desc = a.description ? a.description.replace(/<[^>]*>/g, '').trim() : '';
        document.getElementById('heroDesc').textContent = desc || wt('status.no-description', 'No description');

        const tags = a.tags || [];
        document.getElementById('heroTags').innerHTML = tags.slice(0, 8).map(t =>
            `<span class="hero-tag">${escapeHtml(t.name)}${t.translatedName ? `<span class="tag-trans">${escapeHtml(t.translatedName)}</span>` : ''}</span>`
        ).join('');

        document.getElementById('heroArtwork').onclick = () => {
            if (heroSrc) openLightbox(0, [heroSrc]);
        };

        triggerHeroAnimations();
    }
