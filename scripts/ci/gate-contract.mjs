#!/usr/bin/env node
'use strict';

if (process.argv.length === 3 && process.argv[2] === '--version') {
    console.log('gate-contract 5');
} else {
    await import('../i18n/gate-contract.mjs');
}
