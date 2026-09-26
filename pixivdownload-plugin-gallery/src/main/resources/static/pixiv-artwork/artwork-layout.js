'use strict';

// 以原图尺寸决定内容组宽度，作者栏紧邻作品；加载失败时保留可用的默认布局。
document.getElementById('viewer').addEventListener('load', event => {
    const image = event.target;
    if (image.tagName !== 'IMG' || !image.naturalWidth || !image.naturalHeight) return;
    if (image.parentElement.id !== 'mainImage') return;
    const wrapper = document.getElementById('mainWrapper');
    wrapper.style.setProperty('--artwork-natural-width', `${image.naturalWidth}px`);
    wrapper.style.setProperty('--artwork-ratio', image.naturalWidth / image.naturalHeight);
}, true);
