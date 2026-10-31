const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const script = readFileSync('src/main/resources/web/app.js', 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const response = (data, status = 200) => ({ok: status < 400, status, json: async () => data});
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
};
const account = name => ({idKey: 'USR-' + name, username: name, fullName: name, type: 'GENERAL'});
const scroll = (id = 'SCR-ONE', name = 'A saved scroll') => ({id, name,
  uploaderId: 'USR-UPLOADER', uploaderUsername: 'uploader', uploadDate: '2026-10-28',
  preview: 'Scroll content', owned: false, downloads: 0});

class Element {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.style = {};
    this.attributes = {};
    this.listeners = new Map();
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this._text = '';
  }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = value; this.children = []; }
  append(...children) {
    children.forEach(child => { child.parentElement = this; this.children.push(child); });
  }
  replaceChildren(...children) { this._text = ''; this.children = []; this.append(...children); }
  remove() {
    if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(child => child !== this);
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
  descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
  querySelectorAll(selector) {
    return this.descendants().filter(element => selector.split(', ').some(part => {
      const namedInput = part.match(/^input\[name="([^"]+)"\]$/);
      if (namedInput) return element.tagName === 'input' && element.name === namedInput[1];
      if (part.startsWith('#')) return element.id === part.slice(1);
      if (part.startsWith('.')) return element.className === part.slice(1);
      const attribute = part.match(/^\[([\w-]+)\]$/);
      if (attribute) return element.hasAttribute(attribute[1]);
      return element.tagName === part;
    }));
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  reset() { this.querySelectorAll('input').forEach(input => { input.value = ''; }); }
}

function server(user = account('A')) {
  return {user, scrolls: [scroll()], saved: new Map(), calls: [], intercept: null};
}

async function openPage(backend = server(), search = '') {
  const body = new Element('body');
  const sidebar = new Element('aside');
  sidebar.className = 'v1_112';
  body.append(sidebar);
  const window = new Element('window');
  const document = {
    body, createElement: tag => new Element(tag),
    getElementById: id => body.descendants().find(element => element.id === id) || null,
    querySelector: selector => body.querySelector(selector),
    querySelectorAll: selector => body.querySelectorAll(selector)
  };
  const context = vm.createContext({document, window, URLSearchParams,
    location: {pathname: '/browse.html', search, href: '/browse.html' + search},
    fetch: async (path, options = {}) => {
      const method = options.method || 'GET';
      backend.calls.push({path, method, options});
      const intercepted = backend.intercept?.(path, options);
      if (intercepted !== undefined) return intercepted;
      if (path === '/api/session') return response(backend.user || {error: 'Not signed in'}, backend.user ? 200 : 401);
      if (path === '/api/logout') { backend.user = null; return response({}); }
      const expectedUser = options.headers?.['X-VSAS-User-ID']
        && decodeURIComponent(options.headers['X-VSAS-User-ID']);
      if (expectedUser && expectedUser !== backend.user?.idKey) return response({error: 'Account changed'}, 409);
      const expectedUsername = options.headers?.['X-VSAS-Username']
        && decodeURIComponent(options.headers['X-VSAS-Username']);
      if (expectedUsername && expectedUsername !== backend.user?.username) return response({error: 'Account changed'}, 409);
      const url = new URL(path, 'http://vsas.test');
      const favouriteId = url.pathname.match(/^\/api\/favourites\/(.+)$/)?.[1];
      if (url.pathname.startsWith('/api/favourites') && !backend.user) return response({error: 'Not signed in'}, 401);
      const saved = backend.saved.get(backend.user?.idKey) || new Set();
      if (favouriteId) {
        const id = decodeURIComponent(favouriteId);
        if (method === 'PUT' && !backend.scrolls.some(item => item.id === id)) return response({error: 'Not found'}, 404);
        if (method === 'PUT') saved.add(id);
        else if (method === 'DELETE') saved.delete(id);
        else assert.fail('Unexpected method: ' + method);
        backend.saved.set(backend.user.idKey, saved);
        return response({favourite: method === 'PUT'});
      }
      assert.ok(['/api/scrolls', '/api/favourites'].includes(url.pathname), path);
      return response({scrolls: backend.scrolls
        .filter(item => url.pathname !== '/api/favourites' || saved.has(item.id))
        .filter(item => !url.searchParams.get('name') || item.name.includes(url.searchParams.get('name')))
        .map(item => ({...item, favourite: saved.has(item.id)}))});
    }
  });
  vm.runInContext(script, context);
  await flush();
  return {backend, context, document, window,
    library: () => document.getElementById('scroll-library'),
    status: () => document.getElementById('status-notice'),
    stars: () => body.querySelectorAll('.favourite-toggle'),
    focus: () => window.dispatch('focus'),
    mutations: () => backend.calls.filter(call => ['PUT', 'DELETE'].includes(call.method))};
}

test('save and unsave use authenticated API calls and accessible star state, surviving page reload', async () => {
  const backend = server();
  const page = await openPage(backend);
  assert.equal(page.document.getElementById('favourites-link').href, 'browse.html?favourites=1');
  const star = page.stars()[0];
  assert.equal(star.attributes['aria-pressed'], 'false');
  await star.dispatch('click');
  assert.equal(star.textContent, '★ Saved');
  assert.equal(star.attributes['aria-pressed'], 'true');
  assert.match(star.attributes['aria-label'], /Remove A saved scroll from favourites/);
  assert.deepEqual(page.mutations().map(call => call.method), ['PUT']);
  assert.equal(page.mutations()[0].options.headers['X-VSAS-User-ID'], 'USR-A');
  assert.equal(page.mutations()[0].options.headers['X-VSAS-Username'], 'A');
  const reopened = await openPage(backend);
  assert.equal(reopened.stars()[0].attributes['aria-pressed'], 'true');
  await reopened.stars()[0].dispatch('click');
  assert.equal(reopened.stars()[0].attributes['aria-pressed'], 'false');
  assert.deepEqual(page.mutations().map(call => call.method), ['PUT', 'DELETE']);
});

test('guests receive a working sign-in link and cannot load a private favourites list', async () => {
  const backend = server(null);
  const page = await openPage(backend);
  assert.equal(page.document.getElementById('favourites-link'), null);
  await page.stars()[0].dispatch('click');
  assert.equal(page.status().querySelector('a').href, 'login.html');
  assert.equal(page.mutations().length, 0);
  const privatePage = await openPage(backend, '?favourites=1');
  assert.ok(privatePage.document.getElementById('guest-restriction'));
  assert.equal(backend.calls.filter(call => call.path.startsWith('/api/favourites')).length, 0);
});

test('Unicode account names and administrator-issued IDs are safely encoded in account headers', async () => {
  const backend = server({...account('A'), idKey: '用户-甲', username: '读者+甲'});
  const page = await openPage(backend);
  await page.stars()[0].dispatch('click');
  const headers = page.mutations()[0].options.headers;
  assert.equal(headers['X-VSAS-User-ID'], encodeURIComponent('用户-甲'));
  assert.equal(headers['X-VSAS-Username'], encodeURIComponent('读者+甲'));
  assert.equal(page.stars()[0].attributes['aria-pressed'], 'true');
  for (const call of backend.calls.filter(call => call.path.startsWith('/api/scrolls'))) {
    assert.equal(call.options.headers['X-VSAS-Username'], encodeURIComponent('读者+甲'));
    assert.equal(call.options.headers['X-VSAS-User-ID'], encodeURIComponent('用户-甲'));
  }
});

test('removing the last favourite updates the list and displays the empty state', async () => {
  const backend = server();
  backend.saved.set('USR-A', new Set(['SCR-ONE']));
  const page = await openPage(backend, '?favourites=1');
  assert.equal(page.document.title, 'VSAS — Favourites');
  assert.equal(page.document.getElementById('favourites-link').attributes['aria-current'], 'page');
  await page.stars()[0].dispatch('click');
  assert.equal(page.stars().length, 0);
  assert.match(page.library().textContent, /No favourites yet/);
  assert.match(page.status().textContent, /Removed/);
});

test('favourites filtering uses the private endpoint, retains member filters, and distinguishes no matches', async () => {
  const page = await openPage(server(), '?favourites=1');
  const values = {scrollSearch: 'Missing', scrollId: 'SCR-ID', uploaderId: 'USR-UPLOADER', uploadDate: '2026-10-28'};
  for (const [name, value] of Object.entries(values)) page.document.querySelector(`input[name="${name}"]`).value = value;
  await page.context.loadScrolls();
  const call = page.backend.calls.at(-1);
  const url = new URL(call.path, 'http://vsas.test');
  assert.equal(url.pathname, '/api/favourites');
  assert.deepEqual(Object.fromEntries(url.searchParams), {
    name: 'Missing', scrollId: 'SCR-ID', uploaderId: 'USR-UPLOADER', uploadDate: '2026-10-28'
  });
  assert.equal(call.options.headers['X-VSAS-User-ID'], 'USR-A');
  assert.equal(call.options.headers['X-VSAS-Username'], 'A');
  assert.equal(page.library().textContent, 'No scrolls match your filters.');
});

test('a failed save preserves the original state, permits retry and prevents duplicate clicks while loading', async () => {
  const page = await openPage();
  const pending = deferred();
  page.backend.intercept = (path, options) => options.method === 'PUT' ? pending.promise : undefined;
  const star = page.stars()[0];
  const saving = star.dispatch('click');
  await flush();
  assert.equal(star.disabled, true);
  assert.equal(star.attributes['aria-busy'], 'true');
  await star.dispatch('click');
  assert.equal(page.mutations().length, 1);
  pending.resolve(response({error: 'Disk unavailable'}, 500));
  await saving;
  assert.equal(star.attributes['aria-pressed'], 'false');
  assert.equal(star.disabled, false);
  assert.equal(star.attributes['aria-busy'], undefined);
  assert.match(page.status().textContent, /Could not save favourite.*Disk unavailable/);
  page.backend.intercept = null;
  await star.dispatch('click');
  assert.equal(star.attributes['aria-pressed'], 'true');
});

test('a scroll deleted by another user is removed on refresh and cannot be saved', async () => {
  const page = await openPage();
  page.backend.scrolls = [];
  await page.stars()[0].dispatch('click');
  assert.equal(page.stars().length, 0);
  assert.match(page.status().textContent, /no longer available/);
});

test('account switching before a click refreshes the account and does not mutate its favourites', async () => {
  const page = await openPage();
  page.backend.user = account('B');
  await page.stars()[0].dispatch('click');
  assert.equal(page.mutations().length, 0);
  assert.match(page.status().textContent, /account changed/);
  assert.equal(page.stars()[0].attributes['aria-pressed'], 'false');
});

test('a cookie switch after session preflight is rejected using the expected account header', async () => {
  const page = await openPage();
  page.backend.intercept = (path, options) => {
    if (options.method === 'PUT') page.backend.user = account('B');
  };
  await page.stars()[0].dispatch('click');
  assert.equal(page.backend.saved.size, 0);
  assert.match(page.status().textContent, /account changed/);
});

for (const afterPreflight of [false, true]) {
  test(`reusing a user ID cannot redirect favourites to a replacement account ${afterPreflight ? 'after' : 'before'} preflight`, async () => {
    const page = await openPage();
    const replacement = {...account('B'), idKey: 'USR-A'};
    if (afterPreflight) {
      page.backend.intercept = (path, options) => {
        if (options.method === 'PUT') page.backend.user = replacement;
      };
    } else {
      page.backend.user = replacement;
    }
    await page.stars()[0].dispatch('click');
    assert.equal(page.backend.saved.size, 0);
    assert.match(page.status().textContent, /account changed/);
    assert.equal(page.mutations().length, afterPreflight ? 1 : 0);
  });
}

for (const listFinishesFirst of [false, true]) {
  test(`a successful save replaces stale browse results arriving ${listFinishesFirst ? 'before' : 'after'} the mutation`, async () => {
    const page = await openPage();
    const originalButton = page.stars()[0];
    const oldList = deferred();
    const mutation = deferred();
    let interceptedList = false;
    page.backend.intercept = (path, options) => {
      if (path === '/api/scrolls' && !interceptedList) {
        interceptedList = true;
        return oldList.promise;
      }
      if (options.method === 'PUT') return mutation.promise;
    };
    const loading = page.context.loadScrolls();
    const saving = originalButton.dispatch('click');
    await flush();
    if (listFinishesFirst) {
      oldList.resolve(response({scrolls: [{...scroll(), favourite: false}]}));
      await loading;
      assert.notEqual(page.stars()[0], originalButton);
    }
    page.backend.saved.set('USR-A', new Set(['SCR-ONE']));
    mutation.resolve(response({favourite: true}));
    await saving;
    if (!listFinishesFirst) {
      oldList.resolve(response({scrolls: [{...scroll(), favourite: false}]}));
      await loading;
    }
    assert.equal(page.stars()[0].attributes['aria-pressed'], 'true');
    assert.equal(page.stars()[0].textContent, '★ Saved');
    assert.match(page.status().textContent, /Saved/);
  });
}

test('focus immediately clears private cards and ignores a stale list response from the previous account', async () => {
  const backend = server();
  backend.saved.set('USR-A', new Set(['SCR-ONE']));
  const page = await openPage(backend, '?favourites=1');
  const oldRequest = deferred();
  backend.intercept = path => path === '/api/favourites' ? oldRequest.promise : undefined;
  const loadingOld = page.context.loadScrolls();
  backend.user = account('B');
  backend.intercept = null;
  const refreshing = page.focus();
  assert.equal(page.library().children.length, 0);
  await refreshing;
  assert.match(page.library().textContent, /No favourites yet/);
  oldRequest.resolve(response({scrolls: [{...scroll(), favourite: true}]}));
  await loadingOld;
  assert.equal(page.stars().length, 0);
  assert.match(page.library().textContent, /No favourites yet/);
});

test('overlapping session checks cannot restore an older account after a newer response', async () => {
  const page = await openPage(server(), '?favourites=1');
  const stale = deferred();
  page.backend.intercept = path => path === '/api/session' ? stale.promise : undefined;
  const first = page.focus();
  page.backend.user = account('B');
  page.backend.intercept = null;
  await page.focus();
  stale.resolve(response(account('A')));
  await first;
  assert.equal(vm.runInContext('currentUser.idKey', page.context), 'USR-B');
  assert.match(page.library().textContent, /No favourites yet/);
});

test('a later filter result wins when earlier requests finish out of order', async () => {
  const page = await openPage();
  const old = deferred();
  page.backend.intercept = path => path === '/api/scrolls' ? old.promise : undefined;
  const first = page.context.loadScrolls();
  page.document.querySelector('input[name="scrollSearch"]').value = 'Missing';
  await page.context.loadScrolls();
  old.resolve(response({scrolls: [scroll()]}));
  await first;
  assert.equal(page.library().textContent, 'No scrolls match your filters.');
});

test('logout and back-forward restoration clear private cards and recover after a later sign-in', async () => {
  const backend = server();
  backend.saved.set('USR-A', new Set(['SCR-ONE']));
  const page = await openPage(backend, '?favourites=1');
  assert.equal(page.stars().length, 1);
  const loggingOut = page.context.logout();
  assert.equal(page.library().children.length, 0);
  await loggingOut;
  await page.window.dispatch('pageshow', {persisted: true});
  assert.equal(page.stars().length, 0);
  assert.ok(page.document.getElementById('guest-restriction'));
  backend.user = account('B');
  await page.focus();
  assert.equal(page.document.getElementById('guest-restriction'), null);
  assert.match(page.library().textContent, /No favourites yet/);
  await page.window.dispatch('pagehide');
  assert.equal(page.library().children.length, 0);
});
