const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

test('activity page renders UTC timestamps and event details', async () => {
  const html = readFileSync('src/main/resources/web/activity.html', 'utf8');
  const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
  const rows = {children: [], replaceChildren() { this.children = []; }, append(child) { this.children.push(child); }};
  const content = {hidden: true, inert: true};
  const status = {textContent: ''};
  const elements = {events: rows, 'activity-content': content, status};
  const document = {
    getElementById: id => elements[id],
    createElement: tag => ({tag, children: [], append(child) { this.children.push(child); }, textContent: ''})
  };
  const context = vm.createContext({document, Date, fetch: async () => ({ok: true, json: async () => ({events: [{
    timestamp: '2026-10-27T04:00:00Z', actorId: 'USR-1', actorUsername: 'reader',
    action: 'SCROLL_UPLOADED', targetId: 'SCR-1'
  }], warning: 'Recent activity is not yet saved to disk.'})})});
  vm.runInContext(script, context);
  await context.loadActivity();

  assert.equal(rows.children.length, 1);
  assert.equal(rows.children[0].children[0].textContent, '2026-10-27T04:00:00.000Z');
  assert.equal(rows.children[0].children[1].textContent, 'reader (USR-1)');
  assert.equal(rows.children[0].children[2].textContent, 'Scroll uploaded');
  assert.equal(rows.children[0].children[3].textContent, 'SCR-1');
  assert.equal(content.hidden, false);
  assert.match(status.textContent, /not yet saved to disk/);
});
