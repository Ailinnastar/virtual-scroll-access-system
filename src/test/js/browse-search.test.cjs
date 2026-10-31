const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const script = readFileSync('src/main/resources/web/app.js', 'utf8');

// Exercise the complete page script, including initial session lookup and event wiring.
class Element {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.parentElement = null;
    this.style = {};
    this.attributes = {};
    this.listeners = new Map();
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this.textContent = '';
  }

  append(...children) {
    for (const child of children) {
      child.parentElement = this;
      this.children.push(child);
    }
  }

  replaceChildren(...children) {
    for (const child of this.children) child.parentElement = null;
    this.children = [];
    this.append(...children);
  }

  remove() {
    if (!this.parentElement) return;
    const siblings = this.parentElement.children;
    siblings.splice(siblings.indexOf(this), 1);
    this.parentElement = null;
  }

  setAttribute(name, value) { this.attributes[name] = value; }
  hasAttribute(name) { return name in this.attributes; }
  removeAttribute(name) { delete this.attributes[name]; }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }

  async dispatch(type, event = {}) {
    await Promise.all((this.listeners.get(type) || []).map(listener => listener(event)));
  }

  descendants() {
    return this.children.flatMap(child => [child, ...child.descendants()]);
  }

  querySelectorAll(selector) {
    const namedInput = selector.match(/^input\[name="([^"]+)"\]$/);
    return this.descendants().filter(element => {
      if (namedInput) return element.tagName === 'input' && element.name === namedInput[1];
      return element.tagName === selector;
    });
  }

  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }

  reset() {
    for (const input of this.querySelectorAll('input')) input.value = '';
  }
}

async function browsePage(user, search = '') {
  let sessionUser = user;
  let sessionRequests = 0;
  const searches = [];
  const body = new Element('body');
  const browserEvents = new Element('window');
  const document = {
    body,
    createElement: tagName => new Element(tagName),
    getElementById: id => body.descendants().find(element => element.id === id) || null,
    querySelector: selector => body.querySelector(selector),
    querySelectorAll: selector => body.querySelectorAll(selector)
  };
  const location = {pathname: '/browse.html', search, href: '/browse.html' + search};
  const context = vm.createContext({
    document, location, URLSearchParams, window: browserEvents,
    fetch: async path => {
      if (path === '/api/session') {
        sessionRequests++;
        return {ok: Boolean(sessionUser), json: async () => sessionUser || {error: 'Not signed in'}};
      }
      if (path === '/api/logout') {
        sessionUser = null;
        return {ok: true, json: async () => ({message: 'Logged out'})};
      }
      assert.ok(path.startsWith('/api/scrolls'), 'Unexpected request: ' + path);
      searches.push(path);
      return {ok: true, json: async () => ({scrolls: []})};
    }
  });
  vm.runInContext(script, context);
  await new Promise(resolve => setImmediate(resolve));
  return {
    context, document, location, searches,
    input: name => document.querySelector('input[name="' + name + '"]'),
    form: () => document.getElementById('scroll-filters'),
    lastParams: () => new URL(searches.at(-1), 'http://vsas.test').searchParams,
    setSession: next => { sessionUser = next; },
    sessionRequests: () => sessionRequests,
    focus: () => browserEvents.dispatch('focus'),
    pageshow: persisted => browserEvents.dispatch('pageshow', {persisted}),
    submit: async () => {
      await document.getElementById('scroll-filters').dispatch('submit', {preventDefault() {}});
      await new Promise(resolve => setImmediate(resolve));
    }
  };
}

const account = type => ({idKey: 'USR-' + type, username: type.toLowerCase(), fullName: type, type});

function assertFilters(page, member) {
  for (const name of ['scrollSearch', 'scrollId']) {
    const input = page.input(name);
    assert.ok(input);
    assert.equal(input.disabled, false);
    assert.equal(input.parentElement.hidden, false);
  }
  for (const name of ['uploaderId', 'uploadDate']) {
    const input = page.input(name);
    assert.equal(input.parentElement.hidden, !member);
    const display = input.parentElement.style.display
        || input.parentElement.style.cssText.match(/display:([^;]+)/)?.[1];
    assert.equal(display, member ? 'grid' : 'none');
    assert.equal(input.disabled, !member);
  }
  const columns = page.form().style.gridTemplateColumns
      || page.form().style.cssText.match(/grid-template-columns:([^;]+)/)?.[1];
  assert.equal(columns,
      'repeat(' + (member ? 4 : 2) + ',minmax(0,1fr)) auto');
}

for (const user of [null, account('GUEST'), account('UNKNOWN')]) {
  test('browse hides member filters for ' + (user?.type || 'signed-out visitors'), async () => {
    const page = await browsePage(user);
    assertFilters(page, false);
  });
}

for (const type of ['GENERAL', 'ADMIN']) {
  test(type + ' retains all four working browse filters', async () => {
    const page = await browsePage(account(type));
    assertFilters(page, true);
    page.input('scrollSearch').value = '  Guide  ';
    page.input('scrollId').value = 'SCR-ONE';
    page.input('uploaderId').value = 'USR-OWNER';
    page.input('uploadDate').value = '2026-10-24';
    await page.submit();
    assert.deepEqual(Object.fromEntries(page.lastParams()), {
      name: 'Guide', uploaderId: 'USR-OWNER', scrollId: 'SCR-ONE', uploadDate: '2026-10-24'
    });
  });
}

test('guest searches send name and scroll ID, never hidden filter values', async () => {
  const page = await browsePage(null);
  page.input('scrollSearch').value = '  Guest guide  ';
  page.input('scrollId').value = 'SCR-ONE';
  page.input('uploaderId').value = 'STALE-UPLOADER';
  page.input('uploadDate').value = 'not-a-date';
  await page.submit();
  assert.deepEqual(Object.fromEntries(page.lastParams()), {name: 'Guest guide', scrollId: 'SCR-ONE'});
});

test('hidden guest values do not affect the empty-results message or clear behaviour', async () => {
  const page = await browsePage(null);
  page.input('uploaderId').value = 'STALE-UPLOADER';
  page.input('uploadDate').value = '2026-10-24';
  await page.submit();
  assert.equal(page.lastParams().size, 0);
  assert.equal(page.document.getElementById('scroll-library').children[0].textContent,
      'No scrolls have been uploaded yet.');
  page.input('scrollSearch').value = 'missing';
  await page.submit();
  assert.equal(page.document.getElementById('scroll-library').children[0].textContent,
      'No scrolls match your filters.');
  const clear = page.form().querySelectorAll('button').find(button => button.textContent === 'Clear');
  await clear.dispatch('click');
  // The existing click handler starts an asynchronous request without returning it.
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.lastParams().size, 0);
  assert.equal(page.input('scrollSearch').value, '');
  assert.equal(page.input('scrollId').value, '');
});

test('focus refreshes guest/general/admin changes in place without duplicating the form', async () => {
  const page = await browsePage(null);
  const form = page.form();
  for (const type of ['GENERAL', 'ADMIN', 'GENERAL']) {
    page.setSession(account(type));
    await page.focus();
    assertFilters(page, true);
    assert.equal(page.form(), form);
  }
  page.input('scrollSearch').value = 'retained name';
  page.input('scrollId').value = 'SCR-RETAINED';
  page.input('uploaderId').value = 'USR-OLD';
  page.input('uploadDate').value = '2026-10-24';
  page.setSession(null);
  await page.focus();
  assertFilters(page, false);
  assert.equal(page.input('uploaderId').value, '');
  assert.equal(page.input('uploadDate').value, '');
  assert.deepEqual(Object.fromEntries(page.lastParams()), {
    name: 'retained name', scrollId: 'SCR-RETAINED'
  });
});

test('an explicit GUEST role clears and hides previously available member filters', async () => {
  const page = await browsePage(account('ADMIN'));
  page.input('uploaderId').value = 'USR-OLD';
  page.input('uploadDate').value = '2026-10-24';
  page.setSession(account('GUEST'));
  await page.focus();
  assertFilters(page, false);
  assert.equal(page.input('uploaderId').value, '');
  assert.equal(page.input('uploadDate').value, '');
  assert.equal(page.lastParams().size, 0);
});

test('logout clears restricted filters before leaving the page', async () => {
  const page = await browsePage(account('GENERAL'));
  page.input('uploaderId').value = 'USR-OLD';
  page.input('uploadDate').value = '2026-10-24';
  await page.context.logout();
  assertFilters(page, false);
  assert.equal(page.input('uploaderId').value, '');
  assert.equal(page.input('uploadDate').value, '');
  assert.equal(page.location.href, 'index.html');
});

test('back/forward cache restoration rechecks the session and removes stale member options', async () => {
  const page = await browsePage(account('ADMIN'));
  page.input('uploaderId').value = 'USR-OLD';
  page.setSession(null);
  await page.pageshow(true);
  assertFilters(page, false);
  assert.equal(page.input('uploaderId').value, '');
  assert.equal(page.lastParams().size, 0);
});

test('normal pageshow does not duplicate the initial session lookup', async () => {
  const page = await browsePage(null);
  const requests = page.sessionRequests();
  await page.pageshow(false);
  assert.equal(page.sessionRequests(), requests);
  page.context.bindBrowseSearch();
  assertFilters(page, false);
  assert.equal(page.document.body.descendants().filter(element => element.id === 'scroll-filters').length, 1);
});

test('My Scrolls still keeps uploader scope fixed to the signed-in owner', async () => {
  const page = await browsePage(account('GENERAL'), '?mine=1');
  assert.equal(page.input('uploaderId').parentElement.hidden, false);
  assert.equal(page.input('uploaderId').disabled, true);
  assert.equal(page.input('uploaderId').placeholder, 'Your uploads only');
  assert.equal(page.input('uploadDate').disabled, false);
  page.input('uploaderId').value = 'ANOTHER-OWNER';
  page.input('uploadDate').value = '2026-10-24';
  await page.submit();
  assert.deepEqual(Object.fromEntries(page.lastParams()), {mine: '1', uploadDate: '2026-10-24'});
});
