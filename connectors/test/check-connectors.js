#!/usr/bin/env node
//
// Structural checks on the connectors (FZ-092, FZ-093).
//
// Their behaviour is covered by run-tests.js, which executes what each connector
// executes. This covers what a parser can see and a customer would otherwise find for
// us: an input declared but never wired to anything, a script path that does not
// resolve, a credential in a place credentials must not go, and the rule that no
// connector may turn a blocked check into a passing one.
//
//   node connectors/test/check-connectors.js
//
// Uses ruby for YAML parsing — it ships on macOS and on GitHub runners, and this is not
// worth a node_modules directory in a folder that otherwise has none.

'use strict';

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const failures = [];

function check(name, condition, detail) {
    if (condition) {
        console.log(`  ok   ${name}`);
    } else {
        console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
        failures.push(name);
    }
}

function yaml(file, stream = false) {
    const script = stream
        ? 'puts YAML.load_stream(File.read(ARGV[0])).to_json'
        : 'puts YAML.load_file(ARGV[0]).to_json';
    return JSON.parse(execFileSync('ruby', ['-ryaml', '-rjson', '-e', script, file], { encoding: 'utf8' }));
}

const SCRIPT = path.join(__dirname, '..', 'freeze-check.sh');

// ---------------------------------------------------------------------------------
// GitHub Actions
// ---------------------------------------------------------------------------------

console.log('action.yml');

const ACTION = path.join(__dirname, '..', 'action.yml');
const action = yaml(ACTION);

check('it is a composite action', action.runs && action.runs.using === 'composite',
    JSON.stringify(action.runs && action.runs.using));

const steps = (action.runs && action.runs.steps) || [];
check('it has exactly one step', steps.length === 1, `got ${steps.length}`);

const step = steps[0] || {};
const stepEnv = step.env || {};

// An input nobody reads is an input that silently does nothing — the caller sets
// `timeout: 60`, the gate waits 10 seconds, and nothing says so.
const actionInputs = Object.keys(action.inputs || {});
const actionConsumed = Object.values(stepEnv)
    .map((value) => String(value).match(/inputs\.([a-z-]+)/))
    .filter(Boolean)
    .map((match) => match[1]);

for (const input of actionInputs) {
    check(`input "${input}" is passed to the script`, actionConsumed.includes(input));
}
check('no environment variable reads an input that is not declared',
    actionConsumed.every((input) => actionInputs.includes(input)),
    actionConsumed.filter((i) => !actionInputs.includes(i)).join(', '));

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

// The same path in the monorepo and in the published repo, because the gate sits
// beside action.yml in both. Publishing copies rather than rewrites, so what customers
// run is what these tests ran.
check('the run line points at the canonical script',
    (step.run || '').includes('$GITHUB_ACTION_PATH/freeze-check.sh')
        && !(step.run || '').includes('..'), step.run);
check('that script exists', fs.existsSync(SCRIPT), SCRIPT);

// D-24 and 12-connectors.md §4: a connector must not offer a way to downgrade a freeze
// to a warning. `continue-on-error` already exists in every CI system, so a customer who
// wants that can have it visibly, in their own workflow, where a reviewer sees it.
check('the action declares no outputs', !action.outputs, JSON.stringify(action.outputs));
check('no step swallows its own failure',
    steps.every((each) => each['continue-on-error'] === undefined));
check('no input offers a warn-only mode',
    !actionInputs.some((input) => /warn|soft|advis|ignore|dry/.test(input)),
    actionInputs.join(', '));

// ---------------------------------------------------------------------------------
// GitLab CI
// ---------------------------------------------------------------------------------

console.log();
console.log('templates/freeze-check.yml');

const TEMPLATE = path.join(__dirname, '..', 'templates', 'freeze-check.yml');
const [spec, jobs] = yaml(TEMPLATE, true);

check('it is a component: a spec header and a job document',
    Boolean(spec && spec.spec && spec.spec.inputs) && Boolean(jobs));

const gitlabInputs = Object.keys(spec.spec.inputs);
const jobName = Object.keys(jobs)[0];
const job = jobs[jobName];

// Everything an input can reach: the job's name, its stage, its image, its variables.
const gitlabWiring = JSON.stringify(jobs);
for (const input of gitlabInputs) {
    check(`input "${input}" is used by the job`,
        gitlabWiring.includes(`inputs.${input}`));
}

// The reason this connector has no api-key input. Component inputs are interpolated when
// the pipeline is created and become part of its configuration, so a key passed as an
// input is a key written into the pipeline.
check('there is no api-key input', !gitlabInputs.includes('api-key'), gitlabInputs.join(', '));
check('the job does not set FREEZEHUB_API_KEY itself',
    !Object.keys(job.variables || {}).includes('FREEZEHUB_API_KEY'),
    Object.keys(job.variables || {}).join(', '));

check('the job runs the gate', JSON.stringify(job.script || []).includes('freeze-check'),
    JSON.stringify(job.script));
check('it runs the connector image',
    String(spec.spec.inputs.image.default).startsWith('ghcr.io/freezehubio/freeze-check:'),
    spec.spec.inputs.image.default);

check('"on-error" defaults to block', spec.spec.inputs['on-error'].default === 'block',
    spec.spec.inputs['on-error'].default);
// `options` makes GitLab reject a typo when the pipeline is created. Without it,
// FREEZEHUB_ON_ERROR=blcok reaches the script, which exits 2 — safe, but at deploy time.
check('"on-error" is constrained to block or allow',
    JSON.stringify(spec.spec.inputs['on-error'].options || []) === JSON.stringify(['block', 'allow']),
    JSON.stringify(spec.spec.inputs['on-error'].options));

check('the job does not swallow its own failure', job.allow_failure === undefined,
    JSON.stringify(job.allow_failure));
check('no input offers a warn-only mode',
    !gitlabInputs.some((input) => /warn|soft|advis|ignore|dry/.test(input)),
    gitlabInputs.join(', '));

// The four the script cannot guess. `image`, `stage`, `job-name`, `on-error` and
// `timeout` all default; these must be supplied or the wrong freeze gets applied.
for (const required of ['url', 'application', 'environment']) {
    check(`"${required}" has no default`, spec.spec.inputs[required].default === undefined,
        JSON.stringify(spec.spec.inputs[required].default));
}

console.log();
if (failures.length) {
    console.log(`${failures.length} failure(s): ${failures.join(', ')}`);
    process.exit(1);
}
console.log('all checks passed');
