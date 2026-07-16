'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const BATCH_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const SCHEDULE_SOURCE = fs.readFileSync(path.join(BATCH_ROOT, 'modes', 'schedule.js'), 'utf8');
const NATIVE_DIALOG_PATTERN = /(^|[^\w$.])(?:(?:window|globalThis)\s*\.\s*)?(?:alert|confirm|prompt)\s*\(/m;

function collectJavascript(directory, files) {
    fs.readdirSync(directory, {withFileTypes: true}).forEach(entry => {
        const absolute = path.join(directory, entry.name);
        if (entry.isDirectory()) collectJavascript(absolute, files);
        else if (entry.isFile() && entry.name.endsWith('.js')) files.push(absolute);
    });
}

const javascriptFiles = [];
collectJavascript(BATCH_ROOT, javascriptFiles);

assert.ok(javascriptFiles.every(file => !NATIVE_DIALOG_PATTERN.test(fs.readFileSync(file, 'utf8'))),
    'batch modules do not call native alert, confirm, or prompt');
assert.strictEqual((SCHEDULE_SOURCE.match(/await confirmScheduleOverrideClears\(/g) || []).length, 2,
    'both schedule override save paths await their confirmation result');

console.log('batch-feedback.test.js: 2 assertions passed');
