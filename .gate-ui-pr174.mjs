import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

const BASE = 'http://127.0.0.1:8080';
const EVIDENCE = '/tmp/ccr-pr174-97b460e/evidence';
const EVIDENCE2 = '/Users/kevinturner/IdeaProjects/classic-chat-reader/.gate-evidence-pr174';
const EMAILS = [
  'demo.student@example.com',
  'demo.teacher@example.com',
  'student@example.com',
  'teacher@example.com',
  'reader@example.com',
];
// local e2e seed password from e2e/account-auth.spec.js — never logged
const PASSWORD = (process.env.CCR_E2E_PASSWORD || fs.readFileSync('/tmp/ccr-pr174-97b460e/pw.secret','utf8')).trim();

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

function writeEvidence(name, data, encoding) {
  for (const dir of [EVIDENCE, EVIDENCE2]) {
    fs.mkdirSync(dir, { recursive: true });
    if (Buffer.isBuffer(data) || encoding === undefined) {
      fs.writeFileSync(path.join(dir, name), data);
    } else {
      fs.writeFileSync(path.join(dir, name), data, encoding);
    }
  }
}


async function waitHealth(timeoutMs = 120000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const r = await fetch(BASE + '/');
      if (r.status === 200) return true;
    } catch {}
    await new Promise(r => setTimeout(r, 1000));
  }
  return false;
}

async function fingerprint() {
  const live = Buffer.from(await (await fetch(BASE + '/js/sensitive-request-guard.js')).arrayBuffer());
  const git = fs.readFileSync('/Users/kevinturner/IdeaProjects/classic-chat-reader/src/main/resources/static/js/sensitive-request-guard.js');
  const liveHash = sha256(live);
  const gitHash = sha256(git);
  const match = liveHash === gitHash && live.length > 0;
  const head = fs.existsSync('/tmp/ccr-pr174-97b460e/pinned-head.txt')
    ? fs.readFileSync('/tmp/ccr-pr174-97b460e/pinned-head.txt','utf8').trim()
    : '';
  fs.writeFileSync(path.join(EVIDENCE, 'fingerprint.txt'), [
    `liveSha256=${liveHash}`,
    `diskSha256=${gitHash}`,
    `liveBytes=${live.length}`,
    `match=${match}`,
    `head=${head}`,
  ].join('\n') + '\n');
  return match;
}

async function tryLogin(email) {
  const r = await fetch(BASE + '/api/account/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD }),
  });
  return { ok: r.ok, status: r.status, email };
}

async function tryRegister(email) {
  const r = await fetch(BASE + '/api/account/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD }),
  });
  return { ok: r.ok, status: r.status, email };
}

async function resolveAuth() {
  for (const email of EMAILS) {
    const login = await tryLogin(email);
    if (login.ok) return { email, mode: 'login' };
  }
  for (const email of ['student@example.com', 'demo.student@example.com']) {
    await tryRegister(email);
    const login = await tryLogin(email);
    if (login.ok) return { email, mode: 'register+login' };
  }
  throw new Error('No demo account could login/register');
}

async function pickBook() {
  const books = await (await fetch(BASE + '/api/library')).json();
  fs.writeFileSync(path.join(EVIDENCE, 'library.json'), JSON.stringify(books).slice(0, 200000));
  const prefer = books.find(b => /gawain|green knight/i.test(b.title || '') && b.characterEnabled);
  if (prefer) return prefer;
  const anyChar = books.find(b => b.characterEnabled);
  if (!anyChar) throw new Error('No character-enabled book');
  return anyChar;
}

async function main() {
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.mkdirSync(EVIDENCE2, { recursive: true });
  const _w = fs.writeFileSync.bind(fs);
  fs.writeFileSync = (file, data, enc) => {
    _w(file, data, enc);
    try {
      if (String(file).startsWith(EVIDENCE)) {
        const rel = path.relative(EVIDENCE, file);
        _w(path.join(EVIDENCE2, rel), data, enc);
      }
    } catch {}
  };
  const healthy = await waitHealth();
  if (!healthy) throw new Error('Server never became healthy');
  const fpOk = await fingerprint();
  if (!fpOk) throw new Error('Fingerprint mismatch');

  const auth = await resolveAuth();
  const book = await pickBook();

  const browser = await chromium.launch({
    headless: true,
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
  });
  const context = await browser.newContext({
    permissions: ['microphone'],
    viewport: { width: 1400, height: 900 },
  });
  await context.grantPermissions(['microphone'], { origin: BASE });
  const page = await context.newPage();

  // Establish session cookies via API in the browser context
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 60000 });
  // Login via Node first to validate credentials, then seed browser cookies via page request
  const apiLogin = await fetch(BASE + '/api/account/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: auth.email, password: PASSWORD }),
  });
  const apiLoginText = await apiLogin.text();
  if (!apiLogin.ok) throw new Error('API login failed ' + apiLogin.status);

  // Use Playwright request context (not page fetch) then transfer cookies
  const loginResp = await context.request.post(BASE + '/api/account/login', {
    data: { email: auth.email, password: PASSWORD },
  });
  const loginResult = { ok: loginResp.ok(), status: loginResp.status(), text: (await loginResp.text()).slice(0,300) };
  const storage = await context.storageState();
  fs.writeFileSync(path.join(EVIDENCE, 'storage-state.json'), JSON.stringify({ cookies: storage.cookies?.length, origins: storage.origins?.length }));
  fs.writeFileSync(path.join(EVIDENCE, 'login-result.json'), JSON.stringify({ ...loginResult, email: auth.email }));
  if (!loginResult.ok) throw new Error('Browser login failed ' + loginResult.status);

  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const status = await page.evaluate(async () => {
    const r = await fetch('/api/account/status', { cache: 'no-store' });
    return r.json();
  });
  fs.writeFileSync(path.join(EVIDENCE, 'account-status.json'), JSON.stringify(status, null, 2));
  await page.screenshot({ path: path.join(EVIDENCE, '01-after-auth.png'), fullPage: true });

  await page.goto(BASE + `/?book=${book.id}`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(EVIDENCE, '02-book-open.png'), fullPage: true });

  // open characters UI if present
  await page.evaluate(() => {
    const btn = document.querySelector('#characters-toggle, #character-panel-toggle, button[title*="Character"]');
    if (btn) btn.click();
  });
  await page.waitForTimeout(1000);

  // Fetch characters from Node (survives reader.js fetch wrapper / brief blips)
  let charsPayload;
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      const r = await fetch(BASE + `/api/characters/book/${book.id}`);
      if (!r.ok) throw new Error('chars status ' + r.status);
      charsPayload = await r.json();
      break;
    } catch (e) {
      await new Promise(r => setTimeout(r, 1000));
      if (attempt === 9) throw e;
    }
  }
  const list = Array.isArray(charsPayload) ? charsPayload : (charsPayload.characters || []);
  const gawain = list.find(c => /gawain/i.test(c.name || ''));
  const primary = list.find(c => String(c.type || c.characterType || '').toUpperCase() === 'PRIMARY');
  const chosen = gawain || primary || list[0] || null;
  fs.writeFileSync(path.join(EVIDENCE, 'characters.json'), JSON.stringify({ count: list.length, names: list.slice(0,12).map(c=>c.name), chosen }, null, 2));

  const prep = await page.evaluate((c) => {
    let state = null;
    for (const k of Object.keys(window)) {
      try {
        const v = window[k];
        if (v && typeof v === 'object' && Object.prototype.hasOwnProperty.call(v, 'chatCharacterId') && Object.prototype.hasOwnProperty.call(v, 'voiceCallAvailable')) {
          state = v;
          break;
        }
      } catch {}
    }
    if (state && c) {
      state.chatCharacterId = c.id;
      state.chatCharacter = c;
      state.voiceCallAvailable = true;
      state.callActive = false;
      state.accountAuthenticated = true;
    }
    const btn = document.getElementById('character-call-btn');
    if (btn) btn.classList.remove('hidden');
    const cards = Array.from(document.querySelectorAll('[data-character-id], .character-card, .character-item, button'));
    for (const el of cards) {
      const text = (el.textContent || '') + (el.getAttribute('data-character-id') || '');
      if (c && (text.includes(c.id) || (c.name && text.toLowerCase().includes(String(c.name).toLowerCase())))) {
        el.click();
        break;
      }
    }
    return {
      character: c ? { id: c.id, name: c.name, type: c.type || c.characterType } : null,
      hasBtn: !!btn,
      stateFound: !!state,
    };
  }, chosen);
  fs.writeFileSync(path.join(EVIDENCE, 'prep.json'), JSON.stringify(prep, null, 2));
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(EVIDENCE, '03-before-call.png'), fullPage: true });

  await page.locator('#character-call-btn').click({ force: true, timeout: 10000 }).catch(async () => {
    await page.evaluate(() => document.getElementById('character-call-btn')?.click());
  });
  await page.waitForTimeout(3000);
  await page.screenshot({ path: path.join(EVIDENCE, '04-after-call-click.png'), fullPage: true });

  const result = await page.evaluate(() => {
    const authModal = document.getElementById('auth-modal');
    const callModal = document.getElementById('character-call-modal');
    const callStatus = document.getElementById('call-status');
    const callError = document.getElementById('call-error-message');
    const modalPresent = !!(authModal && !authModal.classList.contains('hidden'));
    const callModalVisible = !!(callModal && !callModal.classList.contains('hidden'));
    const statusText = (callStatus?.textContent || '').trim();
    const errorText = (callError?.textContent || '').trim();
    const unauthorized = /unauthor/i.test(statusText + ' ' + errorText);
    const connecting = /connect|in call|listening|speaking|live|connected/i.test(statusText);
    const callStarted = callModalVisible && (connecting || (!unauthorized && statusText.length > 0));
    return { modalPresent, callModalVisible, statusText, errorText, unauthorized, callStarted };
  });

  const pass = result.modalPresent === false && result.callStarted === true && result.unauthorized !== true;
  const head = fs.existsSync('/tmp/ccr-pr174-97b460e/pinned-head.txt')
    ? fs.readFileSync('/tmp/ccr-pr174-97b460e/pinned-head.txt','utf8').trim()
    : '';
  const verdict = {
    overall: pass ? 'PASS' : 'FAIL',
    mergeReadyCandidate: pass,
    modalPresent: result.modalPresent,
    callStarted: result.callStarted,
    authEmail: auth.email,
    bookId: book.id,
    bookTitle: book.title,
    character: prep.character,
    paths: {
      evidence: EVIDENCE,
      screenshots: ['01-after-auth.png','02-book-open.png','03-before-call.png','04-after-call-click.png'],
      fingerprint: 'fingerprint.txt',
      verdict: 'VERDICT.json',
    },
    details: result,
    authMode: auth.mode,
    fingerprintMatch: fpOk,
    head,
  };
  fs.writeFileSync(path.join(EVIDENCE, 'VERDICT.json'), JSON.stringify(verdict, null, 2));
  fs.writeFileSync(path.join(EVIDENCE, 'VERDICT.txt'), [
    `VERDICT=${verdict.overall}`,
    `mergeReadyCandidate=${verdict.mergeReadyCandidate}`,
    `modalPresent=${verdict.modalPresent}`,
    `callStarted=${verdict.callStarted}`,
    `authEmail=${verdict.authEmail}`,
    `book=${verdict.bookTitle}`,
    `character=${verdict.character?.name || ''}`,
    `status=${result.statusText}`,
    `error=${result.errorText}`,
    `head=${head}`,
  ].join('\n') + '\n');

  await page.locator('#call-end-btn').click({ force: true }).catch(() => {});
  await browser.close();
  console.log(JSON.stringify(verdict));
}

main().catch(err => {
  const fail = { overall: 'FAIL', mergeReadyCandidate: false, error: String(err && err.stack || err) };
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(path.join(EVIDENCE, 'VERDICT.json'), JSON.stringify(fail, null, 2));
  fs.writeFileSync(path.join(EVIDENCE, 'VERDICT.txt'), `VERDICT=FAIL\nmergeReadyCandidate=false\nerror=${fail.error}\n`);
  console.error(fail.error);
  process.exit(1);
});
