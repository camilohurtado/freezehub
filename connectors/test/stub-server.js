#!/usr/bin/env node
//
// A stand-in for the Policy API, in its own process (FZ-091).
//
// Its own process because the tests drive the script with spawnSync, which blocks the
// event loop — an in-process server would never answer, every case would time out, and
// the suite would report the timeout behaviour while claiming to test something else.
//
//   node stub-server.js <work-dir>
//
// Reads <work-dir>/response.json before each reply, so a test changes the scenario by
// writing a file. Appends every request to <work-dir>/requests.jsonl, so a test can
// assert what the script actually sent. Writes <work-dir>/port once listening.
//
// A scenario may carry `headers`, and a `then` describing what the *next* request gets
// (FZ-130). That second part is what makes a retry testable at all: "429 once, then 200"
// cannot be expressed by a stub that answers every request the same way, and a retry is
// only observable as a change between two answers.

'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const dir = process.argv[2];
if (!dir) {
    console.error('usage: stub-server.js <work-dir>');
    process.exit(2);
}

const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
        fs.appendFileSync(path.join(dir, 'requests.jsonl'),
            JSON.stringify({ url: req.url, headers: req.headers, raw }) + '\n');

        const response = JSON.parse(fs.readFileSync(path.join(dir, 'response.json'), 'utf8'));

        // Swapped in before the reply is sent, not after: the caller cannot ask again
        // until this response ends, so doing it here leaves no window in which a retry
        // could read the old scenario.
        if (response.then) {
            fs.writeFileSync(path.join(dir, 'response.json'), JSON.stringify(response.then));
        }

        res.writeHead(response.status, { 'Content-Type': 'application/json', ...(response.headers || {}) });
        res.end(typeof response.body === 'string' ? response.body : JSON.stringify(response.body));
    });
});

server.listen(0, '127.0.0.1', () => {
    fs.writeFileSync(path.join(dir, 'port'), String(server.address().port));
});
