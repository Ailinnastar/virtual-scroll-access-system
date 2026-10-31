const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const script = readFileSync('src/main/resources/web/app.js', 'utf8');

test('activity sidebar link follows administrator session changes', () => {
  const elements = new Map();
  const sidebar = {children: [], append(child) { this.children.push(child); elements.set(child.id, child); }};
  const spans = [{textContent: 'Dashboard', hidden: false}];
  const document = {
    querySelectorAll: selector => selector === 'span' ? spans : [],
    querySelector: selector => selector === '[data-session-name]' ? spans[0]
      : selector.includes('.v1_58') ? sidebar : null,
    getElementById: id => elements.get(id) || null,
    createElement: () => ({style: {}, setAttribute() {}, remove() { elements.delete(this.id); }}),
    body: {append(child) { elements.set(child.id, child); }}
  };
  const start = script.indexOf('const memberNavigationLabels');
  const end = script.indexOf('\nasync function refreshSessionStatus', start);
  const context = vm.createContext({document, page: 'dash.html', location: {search: ''}, URLSearchParams});
  vm.runInContext(script.slice(start, end), context);

  context.applySessionNavigation({type: 'ADMIN', username: 'admin'});
  assert.equal(elements.get('activity-link')?.href, 'activity.html');
  assert.ok(sidebar.children.includes(elements.get('activity-link')));
  context.applySessionNavigation({type: 'GENERAL', username: 'member'});
  assert.equal(elements.has('activity-link'), false);
  context.applySessionNavigation({type: 'ADMIN', username: 'admin'});
  assert.equal(elements.get('activity-link')?.href, 'activity.html');
  context.applySessionNavigation(null);
  assert.equal(elements.has('activity-link'), false);
});
