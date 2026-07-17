'use strict';

async function exampleMinimalInitialize() {
    exampleMinimalI18n = await PixivI18n.create({namespaces: ['example-minimal', 'common']});
    exampleMinimalI18n.apply();
    document.getElementById('example-refresh').addEventListener('click', exampleMinimalRefreshStatus);
    await exampleMinimalRefreshStatus();
}

document.addEventListener('DOMContentLoaded', exampleMinimalInitialize);
