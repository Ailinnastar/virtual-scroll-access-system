const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const script = readFileSync('src/main/resources/web/app.js', 'utf8');

class FakeElement {
  constructor(tagName, textContent = '') {
    this.tagName = tagName;
    this.textContent = textContent;
    this.children = [];
    this.style = {};
    this.hidden = false;
    this.listeners = {};
  }

  append(...children) {
    this.children.push(...children);
  }

  addEventListener(type, listener) {
    this.listeners[type] = listener;
  }

  setAttribute(name, value) {
    this[name] = value;
  }
}

function actionLabels(user, owned) {
  const start = script.indexOf('function scrollCard(');
  const end = script.indexOf('\nasync function downloadScroll', start);
  const context = vm.createContext({
    document: {createElement: tag => new FakeElement(tag)}
  });
  vm.runInContext(
      `let currentUser = ${JSON.stringify(user)};`
      + 'function downloadScroll() {} function openScrollEditor() {} function deleteScroll() {}'
      + script.slice(start, end),
      context
  );
  const card = context.scrollCard({
    id: 'SCR-ONE', name: 'Guest guide', uploaderId: 'USR-ONE',
    uploadDate: '2026-10-22', preview: 'preview text', owned
  });
  const buttons = [];
  const visit = element => {
    if (element.tagName === 'button') buttons.push(element.textContent);
    for (const child of element.children) visit(child);
  };
  visit(card);
  return buttons;
}

test('guest scroll cards offer preview content without member actions', () => {
  assert.deepEqual(actionLabels(null, false), ['☆ Save']);
  assert.deepEqual(actionLabels({username: 'member'}, false), ['☆ Save', 'Download']);
  assert.deepEqual(actionLabels({username: 'owner'}, true), ['☆ Save', 'Download', 'Edit', 'Delete']);
});

test('guest navigation hides member links and presents sign-in choices', () => {
  const spans = ['Dashboard', 'Browse Scrolls', 'Upload Scroll', 'My Scrolls', 'Profile',
    'Log out', 'Preview / Download', 'Edit my scroll', 'Delete']
    .map(label => new FakeElement('span', label));
  const elements = new Map();
  const pageContent = new FakeElement('main');
  pageContent.id = 'page-content';
  const document = {
    querySelectorAll: selector => selector === 'span' ? spans : [],
    querySelector: selector => selector === '[data-session-name]' ? spans[0] : null,
    getElementById: id => elements.get(id) || null,
    createElement: tag => {
      const element = new FakeElement(tag);
      element.remove = () => elements.delete(element.id);
      return element;
    },
    body: {
      children: [pageContent],
      append(element) {
        this.children.push(element);
        elements.set(element.id, element);
      }
    }
  };
  const start = script.indexOf('const memberNavigationLabels');
  const end = script.indexOf('\nasync function refreshSessionStatus', start);
  const context = vm.createContext({document, page: 'profile.html', location: {search: ''}, URLSearchParams});
  vm.runInContext(script.slice(start, end), context);

  context.applySessionNavigation(null);
  for (const span of spans.filter(span => [
    'Upload Scroll', 'My Scrolls', 'Profile', 'Log out', 'Edit my scroll', 'Delete'
  ]
      .includes(span.textContent))) assert.equal(span.hidden, true);
  assert.equal(spans[0].hidden, false);
  assert.equal(spans[6].textContent, 'Preview');
  assert.ok(elements.has('guest-auth-links'));
  assert.equal(context.guestPageRequiresSignIn(), true);
  context.showGuestRestriction();
  assert.ok(elements.has('guest-restriction'));
  assert.equal(pageContent.inert, true);
  assert.equal(pageContent['aria-hidden'], 'true');

  context.applySessionNavigation({username: 'member'});
  for (const span of spans) assert.equal(span.hidden, false);
  assert.equal(elements.has('guest-auth-links'), false);
});

test('registration page includes confirmation and has no custom ID field', () => {
  const html = readFileSync('src/main/resources/web/register.html', 'utf8');
  assert.match(html, />Confirm password</);
  assert.doesNotMatch(html, /Custom User ID|v24_38/);
  assert.match(script, /Passwords do not match/);
  assert.match(script, /'confirmPassword'/);
});

test('admin operations start hidden and inert until an admin session is verified', () => {
  const html = readFileSync('src/main/resources/web/admin.html', 'utf8');
  assert.match(html, /id="admin-content" hidden inert/);
  assert.match(html, /adminContent\.hidden = false/);
  assert.match(html, /adminContent\.inert = false/);
});


test('scroll colours stay stable across renders and metadata displays uploader username', () => {
  const start = script.indexOf('function scrollCard(');
  const end = script.indexOf('\nasync function downloadScroll', start);
  const render = scroll => {
    const context = vm.createContext({document: {createElement: tag => new FakeElement(tag)}});
    vm.runInContext('let currentUser = null;' + script.slice(start, end), context);
    return context.scrollCard(scroll);
  };
  const scroll = {id: 'SCR-STABLE', name: 'First name', uploaderId: 'USR-00000001',
    uploaderUsername: 'forest-reader', uploadDate: '2026-10-25', preview: 'A preview'};
  const first = render(scroll);
  const refreshed = render({...scroll, name: 'Renamed scroll', downloads: 42});
  assert.equal(first.children[2]['data-scroll-tone'], refreshed.children[2]['data-scroll-tone']);
  assert.match(first.children[1].textContent, /Uploader: forest-reader/);
  assert.doesNotMatch(first.children[1].textContent, /USR-00000001/);
  assert.match(render({...scroll, uploaderUsername: null}).children[1].textContent, /Deleted user/);
});
