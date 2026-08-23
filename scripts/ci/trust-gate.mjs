#!/usr/bin/env node
'use strict';

if (process.argv.length === 3 && process.argv[2] === '--version') {
    console.log('trusted-release-gate 4');
} else {
    await import('../i18n/trust-gate.mjs');
}
