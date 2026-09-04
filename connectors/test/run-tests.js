#!/usr/bin/env node
//
// Behavioural tests for connectors/freeze-check.sh (FZ-091).
//
// The script is the one implementation every connector wraps (D-24), so a change here
// changes what every customer's pipeline does. Until now it had no automated coverage
// at all — `sh -n` proved it parsed, which is not the same as proving that a revoked
// API key still fails the build.
//
// What is actually being protected is the set of rules that must NOT fail open:
// a missing variable, a rejected credential, and a bad `FREEZEHUB_ON_ERROR` value all
// exit 2 even when the caller asked to fail open. Each of those is one `case` branch
// away from silently disabling the gate for every pipeline using it.
//
//   node connectors/test/run-tests.js
//
// Requires node, curl and jq. No network beyond localhost.

'use strict';

const fs = require("node:fs");
const os = require("node:os");
const { execFileSync, spawn, spawnSync } = require("node:child_process");
const path = require("node:path");

const SCRIPT = path.join(__dirname, "..", "freeze-check.sh");

// Nothing listens here, so curl fails to connect — which is how "FreezeHub could not be
// reached" is provoked without waiting for a timeout.
const UNREACHABLE = "http://127.0.0.1:1";

const ALLOW = { decision: "ALLOW", message: "No restriction is in force.", restrictions: [], unregistered: [] };

const ALLOW_WITH_ADVISORY = {
    decision: "ALLOW",
    message: "An advisory restriction applies.",
    restrictions: [{ id: 1, name: "Peak trading", level: "ADVISORY", reason: "High volume", endsAt: "2026-12-02T00:00:00Z" }],
    unregistered: [],
};

const BLOCK = {
    decision: "BLOCK",
    message: "A hard freeze is in force.",
    restrictions: [{ id: 2, name: "Black Friday Freeze", level: "HARD_FREEZE", reason: "Revenue-critical period", endsAt: "2026-12-02T00:00:00Z" }],
    unregistered: [],
};

const BLOCK_UNREGISTERED = {
    decision: "BLOCK",
    message: "Not recognised, so the request could not be evaluated.",
    restrictions: [],
    unregistered: ["APPLICATION"],
};

const workDir = fs.mkdtempSync(path.join(os.tmpdir(), "freeze-check-test-"));
const requestLog = path.join(workDir, "requests.jsonl");

function respondWith(status, body) {
    fs.writeFileSync(path.join(workDir, "response.json"), JSON.stringify({ status, body }));
}

function requests() {
    if (!fs.existsSync(requestLog)) return [];
    return fs.readFileSync(requestLog, "utf8").trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
}

function forgetRequests() {
    fs.rmSync(requestLog, { force: true });
}

// A check that asserts on what was sent has to survive nothing being sent — otherwise a
// gate that fails to run at all crashes the suite with a TypeError, and the failures
// after it are never reported. The empty object makes it a clean FAIL instead.
function firstRequest() {
    return requests()[0] || { url: null, headers: {}, raw: "{}" };
}

respondWith(200, ALLOW);
// "ignore" rather than "inherit": the stub prints nothing, and inheriting means it
// holds this process's stdout. If the suite then died early — an exception, or a reader
// closing the pipe — the orphan would keep that pipe open and whatever was waiting on it
// would wait for ever. A test suite that hangs instead of failing is worse than one that
// fails, because CI reports it as a timeout six hours later.
const stub = spawn(process.execPath, [path.join(__dirname, "stub-server.js"), workDir], { stdio: "ignore" });

// Belt and braces: unref so a live stub can never hold this process open, and kill it on
// the way out however the way out is reached.
stub.unref();
process.on("exit", () => stub.kill());

// Synchronous wait: everything below drives the script with spawnSync, so there is no
// event loop to await on. The stub writes its port once it is listening.
const portFile = path.join(workDir, "port");
for (let waited = 0; !fs.existsSync(portFile); waited += 50) {
    if (waited > 5000) {
        stub.kill();
        console.error("the stub server did not start");
        process.exit(1);
    }
    spawnSync("sleep", ["0.05"]);
}
const url = `http://127.0.0.1:${fs.readFileSync(portFile, "utf8").trim()}`;

function run({ url: target = url, env = {}, timeoutSeconds = 5 } = {}) {
    const result = spawnSync("sh", [SCRIPT], {
        env: {
            PATH: process.env.PATH,
            FREEZEHUB_URL: target,
            FREEZEHUB_API_KEY: "fzh_test",
            FREEZEHUB_APPLICATION: "payments-api",
            FREEZEHUB_ENVIRONMENT: "production",
            FREEZEHUB_TIMEOUT: String(timeoutSeconds),
            ...env,
        },
        encoding: "utf8",
    });
    return { code: result.status, stdout: result.stdout || "", stderr: result.stderr || "" };
}

const failures = [];

function check(name, condition, detail) {
    if (condition) {
        console.log(`  ok   ${name}`);
    } else {
        console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
        failures.push(name);
    }
}

function exits(name, expected, options) {
    const result = run(options);
    check(name, result.code === expected, `expected exit ${expected}, got ${result.code}. stderr: ${result.stderr.trim()}`);
    return result;
}

console.log('freeze-check.sh');

// --- the ordinary answers -----------------------------------------------------------
respondWith(200, ALLOW);
exits('ALLOW exits 0', 0);

respondWith(200, ALLOW_WITH_ADVISORY);
const advisory = exits('an advisory still exits 0', 0);
check('the advisory is printed rather than swallowed',
    advisory.stdout.includes('Peak trading'), advisory.stdout.trim());

respondWith(200, BLOCK);
const blocked = exits('BLOCK exits 1', 1);
check('the blocking restriction is named',
    blocked.stderr.includes('Black Friday Freeze'), blocked.stderr.trim());

respondWith(200, BLOCK_UNREGISTERED);
const unregistered = exits('an unregistered name exits 1', 1);
check('an unregistered name is explained differently from a freeze',
    unregistered.stderr.includes('does not have this application or environment registered'),
    unregistered.stderr.trim());

// --- the rules that must never fail open --------------------------------------------
// Each is checked with FREEZEHUB_ON_ERROR=allow, because that is the setting under which
// a mistake here would be invisible: the pipeline would deploy, report success, and
// nobody would know the gate had stopped working.
respondWith(401, { title: 'Unauthorized' });
exits('a rejected credential exits 2 even when asked to fail open', 2,
    { env: { FREEZEHUB_ON_ERROR: 'allow' } });

respondWith(400, { title: 'Bad Request' });
exits('a rejected request exits 2 even when asked to fail open', 2,
    { env: { FREEZEHUB_ON_ERROR: 'allow' } });

for (const missing of ['FREEZEHUB_URL', 'FREEZEHUB_API_KEY', 'FREEZEHUB_APPLICATION', 'FREEZEHUB_ENVIRONMENT']) {
    exits(`a missing ${missing} exits 2 even when asked to fail open`, 2,
        { env: { [missing]: '', FREEZEHUB_ON_ERROR: 'allow' } });
}

exits('an unrecognised FREEZEHUB_ON_ERROR exits 2 rather than guessing', 2,
    { env: { FREEZEHUB_ON_ERROR: 'warn' } });

respondWith(200, { decision: 'MAYBE', message: 'x', restrictions: [], unregistered: [] });
exits('an unrecognised decision exits 2 rather than being treated as ALLOW', 2);

// --- the one genuine choice ----------------------------------------------------------
respondWith(503, { title: 'Service Unavailable' });
exits('a server error exits 2 by default', 2);
exits('a server error exits 0 when explicitly asked to fail open', 0,
    { env: { FREEZEHUB_ON_ERROR: 'allow' } });

exits('an unreachable FreezeHub exits 2 by default', 2, { url: UNREACHABLE });
exits('an unreachable FreezeHub exits 0 when explicitly asked to fail open', 0,
    { url: UNREACHABLE, env: { FREEZEHUB_ON_ERROR: 'allow' } });

// --- what it sends --------------------------------------------------------------------
respondWith(200, ALLOW);

forgetRequests();
run({ env: { GITLAB_USER_EMAIL: 'dev@northwind.test', CI_COMMIT_SHA: '9c1f0aa', CI_PIPELINE_URL: 'https://gitlab.test/run/7' } });
let sent = JSON.parse(firstRequest().raw);
check('CI metadata is detected and forwarded',
    sent.actor === 'dev@northwind.test' && sent.reference === '9c1f0aa' && sent.source === 'https://gitlab.test/run/7',
    firstRequest().raw);
check('the API key travels in X-API-Key and nowhere else',
    firstRequest().headers['x-api-key'] === 'fzh_test' && !firstRequest().headers.authorization);
check('it posts to /api/policy/evaluate', firstRequest().url === '/api/policy/evaluate', firstRequest().url);

forgetRequests();
run({ env: { FREEZEHUB_ACTOR: 'someone', GITHUB_ACTOR: 'ignored' } });
check('an explicit actor overrides what the CI system reports',
    JSON.parse(firstRequest().raw).actor === 'someone', firstRequest().raw);

// A name containing a quote is the case that turns a hand-built JSON body into an
// unparseable one — the reason the script builds its request with jq.
forgetRequests();
const quoted = 'payments "api"';
run({ env: { FREEZEHUB_APPLICATION: quoted } });
check('a quote in a name does not break the request body',
    JSON.parse(firstRequest().raw).application === quoted, firstRequest().raw);

forgetRequests();
run();
check('the action is DEPLOY', JSON.parse(firstRequest().raw).action === 'DEPLOY', firstRequest().raw);

// --- the GitHub Action, executed ------------------------------------------------------
//
// check-action.js covers the action's shape. This covers what it does, by running the
// exact command the composite step runs, with the exact environment it builds — read
// out of action.yml rather than restated here, so the two cannot drift.
//
// It is not a substitute for the workflow in verify.yml, which is the only thing that
// proves GitHub itself wires it up. It is what makes the action verifiable without a
// runner, which is the difference between "the tests pass" and "CI has not run yet".

const action = JSON.parse(execFileSync('ruby', [
    '-ryaml', '-rjson', '-e', 'puts YAML.load_file(ARGV[0]).to_json',
    path.join(__dirname, '..', 'action.yml'),
], { encoding: 'utf8' }));

const actionStep = action.runs.steps[0];

function runAction(inputs) {
    const env = { PATH: process.env.PATH, GITHUB_ACTION_PATH: path.join(__dirname, '..') };
    for (const [name, expression] of Object.entries(actionStep.env)) {
        const input = String(expression).match(/inputs\.([a-z-]+)/)[1];
        const supplied = inputs[input];
        env[name] = supplied !== undefined ? supplied : (action.inputs[input].default ?? '');
    }
    const result = spawnSync('bash', ['-e', '-c', actionStep.run], { env, encoding: 'utf8' });
    return { code: result.status, stdout: result.stdout || '', stderr: result.stderr || '' };
}

const actionInputs = { url, 'api-key': 'fzh_test', application: 'payments-api', environment: 'production', timeout: '5' };

respondWith(200, ALLOW);
let outcome = runAction(actionInputs);
check('the action succeeds on ALLOW', outcome.code === 0,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);

respondWith(200, BLOCK);
outcome = runAction(actionInputs);
check('the action fails on BLOCK', outcome.code === 1,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);

respondWith(401, { title: 'Unauthorized' });
outcome = runAction({ ...actionInputs, 'on-error': 'allow' });
check('the action fails on a rejected credential even with on-error: allow', outcome.code === 2,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);

respondWith(200, ALLOW);
forgetRequests();
runAction({ ...actionInputs, application: 'checkout-web' });
check('the action passes its inputs through rather than defaulting them',
    JSON.parse(firstRequest().raw).application === 'checkout-web', firstRequest().raw);


// --- the GitLab component, executed --------------------------------------------------
//
// Same idea as the action above: run what the job runs, with the environment read out of
// template.yml rather than restated, so the two cannot drift. A typo in a variable name
// here would not be loud — FREEZEHUB_TIMEOUT misspelled just silently reverts to 10
// seconds, and FREEZEHUB_ON_ERROR misspelled silently reverts to blocking.

const [gitlabSpec, gitlabJobs] = JSON.parse(execFileSync('ruby', [
    '-ryaml', '-rjson', '-e', 'puts YAML.load_stream(File.read(ARGV[0])).to_json',
    path.join(__dirname, '..', 'templates', 'freeze-check.yml'),
], { encoding: 'utf8' }));

const gitlabJob = gitlabJobs[Object.keys(gitlabJobs)[0]];

function runGitlabJob(inputs, extraEnv = {}) {
    const env = { PATH: process.env.PATH, ...extraEnv };
    for (const [name, expression] of Object.entries(gitlabJob.variables)) {
        const input = String(expression).match(/inputs\.([a-z-]+)/)[1];
        const supplied = inputs[input];
        env[name] = String(supplied !== undefined ? supplied : (gitlabSpec.spec.inputs[input].default ?? ''));
    }
    // The job's script line, run the way GitLab Runner runs it: its own shell, with the
    // image's entrypoint overridden. Locally that is the script by path; the container
    // form of the same thing is covered by image-smoke.sh.
    const result = spawnSync('sh', [SCRIPT], { env, encoding: 'utf8' });
    return { code: result.status, stdout: result.stdout || '', stderr: result.stderr || '' };
}

const gitlabInputs = { url, application: 'payments-api', environment: 'production', timeout: '5' };
const apiKey = { FREEZEHUB_API_KEY: 'fzh_test' };

respondWith(200, ALLOW);
outcome = runGitlabJob(gitlabInputs, apiKey);
check('the GitLab job succeeds on ALLOW', outcome.code === 0,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);

respondWith(200, BLOCK);
outcome = runGitlabJob(gitlabInputs, apiKey);
check('the GitLab job fails on BLOCK', outcome.code === 1,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);

// The component deliberately has no api-key input, because a component input becomes
// part of the pipeline's configuration. It has to come from a CI/CD variable instead —
// so the job must fail clearly when nobody set one, rather than deploying.
outcome = runGitlabJob(gitlabInputs);
check('the GitLab job fails when FREEZEHUB_API_KEY is not set', outcome.code === 2,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);
check('and says which variable is missing',
    outcome.stderr.includes('FREEZEHUB_API_KEY'), outcome.stderr.trim());

respondWith(401, { title: 'Unauthorized' });
outcome = runGitlabJob({ ...gitlabInputs, 'on-error': 'allow' }, apiKey);
check('the GitLab job fails on a rejected credential even with on-error: allow', outcome.code === 2,
    `exit ${outcome.code}: ${outcome.stderr.trim()}`);

respondWith(200, ALLOW);
forgetRequests();
runGitlabJob({ ...gitlabInputs, application: 'billing-worker' }, apiKey);
check('the GitLab job passes its inputs through rather than defaulting them',
    JSON.parse(firstRequest().raw).application === 'billing-worker', firstRequest().raw);

stub.kill();
fs.rmSync(workDir, { recursive: true, force: true });

console.log();
if (failures.length) {
    console.log(`${failures.length} failure(s): ${failures.join(', ')}`);
    process.exit(1);
}
console.log('all checks passed');
