const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/web/app.js', 'utf8');
const html = readFileSync('src/main/resources/web/profile.html', 'utf8');

async function loadProfile(user) {
  const fields = [...html.matchAll(/data-profile-field="([^"]+)"/g)]
    .map(match => ({dataset: {profileField: match[1]}, textContent: '—'}));
  const pictures = [...html.matchAll(/data-profile-picture/g)].map(() => ({}));
  const picture = pictures[0];
  const name = {textContent: 'Guest  ▾'};
  const label = {textContent: 'Username', hasAttribute() { return false; }};
  const document = {
    getElementById() { return null; },
    querySelector() { return null; },
    querySelectorAll(selector) {
      if (selector === '[data-profile-picture]') return pictures;
      if (selector === 'span') return [label];
      if (selector === '[data-profile-field]') return fields;
      if (selector === '[data-session-name]') return [name];
      return [];
    },
    createElement() { return {setAttribute() {}, style: {}}; },
    body: {children: [], append(element) { this.children.push(element); }}
  };
  const context = vm.createContext({document, location: {pathname: '/profile.html', search: ''},
    URLSearchParams, fetch: async path => {
      assert.equal(path, '/api/session');
      return {ok: Boolean(user), json: async () => user || {error: 'Not signed in'}};
    }});
  vm.runInContext(script, context);
  await new Promise(resolve => setImmediate(resolve));
  return {fields: Object.fromEntries(fields.map(field => [field.dataset.profileField, field.textContent])), name, picture, pictures, render: context.renderProfile};
}

test('profile renders each signed-in account and preserves display labels', async () => {
  for (const type of ['GENERAL', 'ADMIN']) {
    const user = {username: `user-${type}`, fullName: '<Alex & Sam>', email: 'alex@example.org',
      phone: '0400000000', idKey: `id-${type}`, type};
    const result = await loadProfile(user);
    assert.deepEqual(result.fields, {...user, type: type === 'GENERAL' ? 'General User' : 'Admin'});
    assert.equal(result.name.textContent, `${user.username}  ▾`);
    assert.equal(result.name.title, user.username);
  }
});

test('signed-out profile clears account details and shows Guest', async () => {
  const result = await loadProfile(null);
  assert.equal(Object.keys(result.fields).length, 6);
  assert.ok(Object.values(result.fields).every(value => value === '—'));
  assert.equal(result.name.textContent, 'Guest  ▾');
});


test('profile shows the saved picture and otherwise uses the default avatar', async () => {
  const user = {username: 'alex', fullName: 'Alex', type: 'GENERAL', picture: 'data:image/png;base64,example'};
  assert.equal((await loadProfile(user)).picture.src, user.picture);
  assert.equal((await loadProfile(null)).picture.src, './images/v11_80.png');
});


test('every page with an account toolbar uses the session username binding', () => {
  for (const page of ['dash', 'browse', 'upload', 'details', 'profile']) {
    const markup = readFileSync(`src/main/resources/web/${page}.html`, 'utf8');
    assert.match(markup, /data-session-name>Guest  ▾<\/span>/);
    assert.doesNotMatch(markup, />Aria  ▾</);
  }
});


test('picture replacement updates the toolbar and profile avatar, and logout resets both', async () => {
  const user = {username: 'alex', fullName: 'Alex', type: 'GENERAL', picture: 'data:image/png;base64,first'};
  const result = await loadProfile(user);
  assert.equal(result.pictures.length, 2);
  for (const picture of result.pictures) assert.equal(picture.src, user.picture);
  const replacement = {...user, picture: 'data:image/png;base64,replacement'};
  result.render(replacement);
  for (const picture of result.pictures) {
    assert.equal(picture.src, replacement.picture);
    assert.equal(picture.alt, "Alex's profile picture");
  }
  result.render(null);
  for (const picture of result.pictures) assert.equal(picture.src, './images/v11_80.png');
});

test('every account toolbar includes a session-bound picture with a default avatar', () => {
  for (const page of ['dash', 'browse', 'upload', 'details', 'profile']) {
    const markup = readFileSync(`src/main/resources/web/${page}.html`, 'utf8');
    assert.match(markup, /<img[^>]*data-profile-picture[^>]*src="\.\/images\/v11_80\.png"/);
  }
});
