'use strict';

async function exampleMinimalInitialize() {
    const i18n = await PixivI18n.create({namespaces: ['example-minimal', 'common']});
    i18n.apply();
}

document.addEventListener('DOMContentLoaded', exampleMinimalInitialize);
