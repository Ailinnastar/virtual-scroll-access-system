const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const script = readFileSync('src/main/resources/web/app.js', 'utf8');

function functionSource(startMarker, endMarker) {
  const start = script.indexOf(startMarker);
  const end = script.indexOf(endMarker, start);
  assert.ok(start >= 0 && end > start, `Could not locate ${startMarker}`);
  return script.slice(start, end);
}

const cardSource = functionSource('function scrollCard(', '\nasync function downloadScroll');
const downloadSource = functionSource('async function downloadScroll(', '\nfunction openScrollEditor');
const invalidateSource = functionSource('function invalidateSessionCards(', '\nasync function refreshSessionStatus');
const refreshBrowseSource = functionSource('async function refreshBrowseSession(', "\nif (page === 'dash.html')");

class Element {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.textContent = '';
    this.style = {};
    this.listeners = {};
  }

  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(name, value) { this[name] = value; }
  addEventListener(type, listener) { this.listeners[type] = listener; }
  click() { this.clicked = true; }
}

const admin = {idKey: 'USR-ADMIN', username: 'admin', type: 'ADMIN'};
const member = {idKey: 'USR-MEMBER', username: 'reader', type: 'GENERAL'};
const sample = {
  id: 'SCR-ONE', name: 'Download count guide', uploaderUsername: 'author',
  uploadDate: '2026-10-29', preview: 'Preview text', downloads: 7, owned: false
};

function cardHarness(user) {
  const context = vm.createContext({document: {createElement: tag => new Element(tag)}});
  vm.runInContext(`let currentUser = ${JSON.stringify(user)};` + cardSource, context);
  return context;
}

test('admin cards display independent zero and nonzero download counts without moving the preview', () => {
  const context = cardHarness(admin);
  for (const [id, downloads] of [['SCR-ZERO', 0], ['SCR-ONE', 7], ['SCR-TWO', 23]]) {
    const card = context.scrollCard({...sample, id, downloads});
    assert.match(card.children[1].textContent, new RegExp(`\\nDownloads: ${downloads}$`));
    assert.equal(card.children[2].tagName, 'pre');
    assert.equal(card.children[2].children[0].textContent, sample.preview);
    assert.equal(card.children[3].tagName, 'div');
  }
});

test('admin cards use an unavailable marker for absent or invalid counts rather than inventing zero', () => {
  const context = cardHarness(admin);
  for (const downloads of [undefined, null, -1, 1.5, '12', NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.match(context.scrollCard({...sample, downloads}).children[1].textContent, /\nDownloads: —$/);
  }
});

test('guest, general and unknown-role cards do not display the admin download count', () => {
  for (const user of [null, member, {type: 'GUEST'}, {type: 'UNKNOWN'}]) {
    assert.doesNotMatch(cardHarness(user).scrollCard(sample).children[1].textContent, /Downloads:/);
  }
});

function downloadHarness({page = 'browse.html', nextUser = admin, serverDownloads = 42,
  responseOk = true, responseError = 'Download not allowed', blobError = null} = {}) {
  const events = [];
  const links = [];
  const statuses = [];
  const library = new Element('section');
  const dashboard = new Element('section');
  const context = vm.createContext({
    page,
    nextUser,
    document: {
      createElement(tag) {
        const element = new Element(tag);
        if (tag === 'a') links.push(element);
        return element;
      },
      getElementById: id => ({'scroll-library': library, 'dashboard-scrolls': dashboard}[id])
    },
    URL: {
      createObjectURL() { events.push('blob-url'); return 'blob:download'; },
      revokeObjectURL(url) { events.push(`revoke:${url}`); }
    },
    async fetch(url) {
      events.push(url);
      return {
        ok: responseOk,
        json: async () => ({error: responseError}),
        async blob() {
          if (blobError) throw new Error(blobError);
          return {};
        }
      };
    },
    setStatus: (message, error = false) => statuses.push({message, error}),
    updateDashboardVisibility() {},
    hasMemberSearchFilters: user => ['ADMIN', 'GENERAL'].includes(user?.type),
    guestPageRequiresSignIn: () => false,
    showGuestRestriction() { events.push('restricted'); },
    bindBrowseSearch() { events.push('bind-search'); },
    async refreshSessionStatus() {
      events.push('session');
      context.invalidateSessionCards();
      assert.equal(library.children.length, 0, 'stale cards must be removed during session verification');
      assert.equal(dashboard.children.length, 0);
      vm.runInContext('currentUser = nextUser; sessionPending = false;', context);
      return nextUser;
    },
    async loadScrolls() {
      events.push('reload-scrolls');
      library.replaceChildren(context.scrollCard({...sample, downloads: serverDownloads}));
    }
  });
  vm.runInContext(`let currentUser = ${JSON.stringify(admin)};
    let sessionRevision = 0; let scrollRequestId = 0; let sessionPending = false;`
    + cardSource + invalidateSource + refreshBrowseSource + downloadSource, context);
  library.append(context.scrollCard(sample));
  dashboard.append(context.scrollCard(sample));
  return {context, events, links, statuses, library, dashboard};
}

test('successful browse download revalidates the session then renders the server count, not a local increment', async () => {
  const harness = downloadHarness();
  const originalScroll = Object.freeze({...sample, id: 'SCR/ONE', originalFileName: 'guide.bin'});
  await harness.context.downloadScroll(originalScroll);
  assert.deepEqual(harness.events, [
    '/api/scrolls/SCR%2FONE/download', 'blob-url', 'revoke:blob:download',
    'session', 'bind-search', 'reload-scrolls'
  ]);
  assert.equal(originalScroll.downloads, 7);
  assert.match(harness.library.children[0].children[1].textContent, /\nDownloads: 42$/);
  assert.equal(harness.links[0].download, 'guide.bin');
  assert.equal(harness.links[0].clicked, true);
  assert.deepEqual(harness.statuses.at(-1), {message: 'Downloaded Download count guide.', error: false});
});

test('a session change during download clears admin cards and renders member cards without counts', async () => {
  for (const nextUser of [member, null]) {
    const harness = downloadHarness({nextUser});
    assert.match(harness.library.children[0].children[1].textContent, /Downloads: 7/);
    await harness.context.downloadScroll(sample);
    assert.doesNotMatch(harness.library.children[0].children[1].textContent, /Downloads:/);
    assert.equal(harness.dashboard.children.length, 0);
  }
});

test('a rejected download does not refresh cards, mutate the count or create a download link', async () => {
  const harness = downloadHarness({responseOk: false});
  const originalCard = harness.library.children[0];
  const originalScroll = Object.freeze({...sample});
  await harness.context.downloadScroll(originalScroll);
  assert.deepEqual(harness.events, ['/api/scrolls/SCR-ONE/download']);
  assert.equal(harness.library.children[0], originalCard);
  assert.equal(originalScroll.downloads, 7);
  assert.equal(harness.links.length, 0);
  assert.deepEqual(harness.statuses.at(-1), {message: 'Download not allowed', error: true});
});

test('a failed response body does not predict a successful download or refresh the displayed count', async () => {
  const harness = downloadHarness({blobError: 'Connection lost'});
  await harness.context.downloadScroll(Object.freeze({...sample}));
  assert.deepEqual(harness.events, ['/api/scrolls/SCR-ONE/download']);
  assert.match(harness.library.children[0].children[1].textContent, /Downloads: 7$/);
  assert.equal(harness.links.length, 0);
  assert.deepEqual(harness.statuses.at(-1), {message: 'Connection lost', error: true});
});

test('dashboard downloads preserve the existing session refresh and default download filename', async () => {
  const harness = downloadHarness({page: 'dash.html'});
  await harness.context.downloadScroll(Object.freeze({...sample}));
  assert.deepEqual(harness.events, [
    '/api/scrolls/SCR-ONE/download', 'blob-url', 'revoke:blob:download', 'session'
  ]);
  assert.equal(harness.links[0].download, 'SCR-ONE.bin');
  assert.equal(harness.links[0].clicked, true);
  assert.equal(harness.statuses.at(-1).error, false);
});
