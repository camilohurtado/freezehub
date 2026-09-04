#!/usr/bin/env node
//
// Structural checks on the GitHub Action (FZ-092).
//
// The action's behaviour is covered by the workflow in verify.yml, which needs a runner.
// This covers what can be checked on a laptop and what would otherwise be found only by
// a customer: an input declared but never wired to anything, a script path that does not
// resolve, and the rule that no input may turn a blocked check into a passing one.
//
//   node connectors/test/check-action.js
//
// Uses ruby for YAML parsing — it ships on macOS and on GitHub runners, and this is not
// worth a node_modules directory in a folder that otherwise has none.

'use strict';

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const ACTION = path.join(__dirname, '..', 'github-action', 'action.yml');

const action = JSON.parse(execFileSync('ruby', [
    '-ryaml', '-rjson', '-e', 'puts YAML.load_file(ARGV[0]).to_json', ACTION,
], { encoding: 'utf8' }));

const failures = [];

function check(name, condition, detail) {
    if (condition) {
        console.log(`  ok   ${name}`);
    } else {
        console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
        failures.push(name);
    }
}

console.log('github-action/action.yml');

check('it is a composite action', action.runs && action.runs.using === 'composite',
    JSON.stringify(action.runs && action.runs.using));

const steps = (action.runs && action.runs.steps) || [];
check('it has exactly one step', steps.length === 1, `got ${steps.length}`);

const step = steps[0] || {};
const env = step.env || {};

// An input nobody reads is an input that silently does nothing — the caller sets
// `timeout: 60`, the gate waits 10 seconds, and nothing says so.
const declared = Object.keys(action.inputs || {});
const consumed = Object.values(env)
    .map((value) => String(value).match(/inputs\.([a-z-]+)/))
    .filter(Boolean)
    .map((match) => match[1]);

for (const input of declared) {
    check(`input "${input}" is passed to the script`, consumed.includes(input));
}
check('no environment variable reads an input that is not declared',
    consumed.every((input) => declared.includes(input)),
    consumed.filter((i) => !declared.includes(i)).join(', '));

// These four have no sensible default. Defaulting any of them would mean guessing which
// application is being deployed, which is how the wrong freeze gets applied.
for (const required of ['url', 'api-key', 'application', 'environment']) {
    check(`"${required}" is required`, action.inputs[required] && action.inputs[required].required === true);
}

// The script's own defaults, restated here so the action's documentation is not a lie.
check('"on-error" defaults to block', action.inputs['on-error'].default === 'block',
    action.inputs['on-error'].default);
check('"timeout" has a default', Boolean(action.inputs.timeout.default));

// Interpolating customer data into a shell command is how an application name becomes a
// command. Environment values are never evaluated, which is why every input goes there.
check('no input is interpolated into the run line',
    !/\$\{\{\s*inputs\./.test(step.run || ''), step.run);

const scriptPath = path.join(path.dirname(ACTION), '..', 'freeze-check.sh');
check('the run line points at the canonical script',
    (step.run || '').includes('$GITHUB_ACTION_PATH/../freeze-check.sh'), step.run);
check('that script exists', fs.existsSync(scriptPath), scriptPath);

// D-24 and 12-connectors.md §4: a connector must not offer a way to downgrade a freeze
// to a warning. `continue-on-error` already exists in every CI system, so a customer who
// wants that can have it visibly, in their own workflow, where a reviewer sees it.
check('the action declares no outputs', !action.outputs,
    JSON.stringify(action.outputs));
// Checked on the parsed document rather than the file text: the concern is the key,
// and the prose above explains why it is absent.
check('no step swallows its own failure',
    steps.every((each) => each['continue-on-error'] === undefined));
check('no input offers a warn-only mode',
    !declared.some((input) => /warn|soft|advis|ignore|dry/.test(input)),
    declared.join(', '));

console.log();
if (failures.length) {
    console.log(`${failures.length} failure(s): ${failures.join(', ')}`);
    process.exit(1);
}
console.log('all checks passed');
