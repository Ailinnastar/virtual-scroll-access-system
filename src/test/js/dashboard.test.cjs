const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/web/app.js', 'utf8');

function dashboard(scrolls, user) {
  const counts = ['mine', 'total', 'downloads', 'uploaders'].map(key => ({dataset: {dashboardCount: key}}));
  const tiles = Array.from({length: 6}, () => ({hidden: true}));
  const library = {children: [], replaceChildren() { this.children = []; }, append(child) { this.children.push(child); }};
  const role = {};
  const context = vm.createContext({
    document: {
      querySelectorAll: selector => selector === '[data-dashboard-admin]' ? tiles : counts,
      querySelector: () => role,
      getElementById: () => library,
      createElement: () => ({})
    },
    scrollCard: scroll => ({id: scroll.id})
  });
  vm.runInContext(script.slice(script.indexOf('function renderDashboard(')), context);
  context.renderDashboard(scrolls, user);
  return {tiles, render: nextUser => context.renderDashboard(scrolls, nextUser), countNodes: counts, counts: Object.fromEntries(counts.map(node => [node.dataset.dashboardCount, node.textContent])), library, role};
}

test('dashboard displays actual counts and four most recent scrolls', () => {
  const scrolls = [
    {id: 'old', uploaderId: 'a', owned: true, downloads: 2, uploadDate: '2026-10-01'},
    {id: 'latest', uploaderId: 'b', owned: false, downloads: 3, uploadDate: '2026-10-21'},
    {id: 'third', uploaderId: 'a', owned: true, downloads: 0, uploadDate: '2026-10-19'},
    {id: 'second', uploaderId: 'b', owned: false, downloads: 1, uploadDate: '2026-10-20'},
    {id: 'fourth', uploaderId: 'c', owned: false, downloads: 0, uploadDate: '2026-10-18'}
  ];
  const result = dashboard(scrolls, {type: 'ADMIN'});
  assert.deepEqual(result.counts, {mine: '2', total: '5', downloads: '6', uploaders: '3'});
  assert.deepEqual(result.library.children.map(card => card.id), ['latest', 'second', 'third', 'fourth']);
  assert.match(result.role.textContent, /^Admin/);
  const updated = dashboard(scrolls.filter(scroll => scroll.uploaderId !== 'c'), {type: 'ADMIN'});
  assert.equal(updated.counts.total, '4');
  assert.equal(updated.counts.uploaders, '2');
  assert.equal(updated.counts.downloads, '6');
});

test('empty guest dashboard has zero counts and no template scrolls', () => {
  const result = dashboard([], null);
  assert.deepEqual(result.counts, {mine: '0', total: '—', downloads: '0', uploaders: '—'});
  assert.equal(result.library.children[0].textContent, 'No scrolls have been uploaded yet.');
  assert.match(result.role.textContent, /^Guest/);
  const html = readFileSync('src/main/resources/web/dash.html', 'utf8');
  assert.doesNotMatch(html, /Agile Development Guide|by Aria|Following|Favourites/);
});

for (const user of [null, {type: 'GUEST'}, {type: 'GENERAL'}, {type: 'UNKNOWN'}]) {
  test(`restricted tiles disappear when admin switches to ${user?.type || 'signed out'}`, () => {
    const result = dashboard([{id: 'one', uploaderId: 'a', downloads: 0, uploadDate: '2026-10-25'}], {type: 'ADMIN'});
    assert.ok(result.tiles.every(tile => !tile.hidden));
    result.render(user);
    assert.ok(result.tiles.every(tile => tile.hidden));
    for (const node of result.countNodes.filter(node => ['total', 'uploaders'].includes(node.dataset.dashboardCount))) {
      assert.equal(node.textContent, '—');
    }
    result.render({type: 'ADMIN'});
    assert.ok(result.tiles.every(tile => !tile.hidden));
    assert.equal(result.countNodes.find(node => node.dataset.dashboardCount === 'total').textContent, '1');
  });
}

test('admin counts refresh after deletion and handle an empty library', () => {
  assert.deepEqual(dashboard([], {type: 'ADMIN'}).counts,
    {mine: '0', total: '0', downloads: '0', uploaders: '0'});
  const html = readFileSync('src/main/resources/web/dash.html', 'utf8');
  assert.equal((html.match(/data-dashboard-admin hidden/g) || []).length, 6);
});
