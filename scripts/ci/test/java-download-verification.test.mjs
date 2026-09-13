import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import YAML from 'yaml';

test('Java 安装步骤显式要求签名校验失败时阻断', () => {
    const root = fileURLToPath(new URL('../../../.github/', import.meta.url));
    let installations = 0;
    for (const file of fs.readdirSync(root, { recursive: true })) {
        if (!/\.ya?ml$/u.test(file)) continue;
        const document = YAML.parse(fs.readFileSync(path.join(root, file), 'utf8'));
        const jobs = document.jobs ? Object.values(document.jobs) : [document.runs];
        for (const job of jobs) {
            for (const step of job?.steps ?? []) {
                if (!step.uses?.startsWith('actions/setup-java@')) continue;
                assert.equal(step.with?.['verify-signature'], true, file);
                installations++;
            }
        }
    }
    assert.ok(installations > 0);
});
