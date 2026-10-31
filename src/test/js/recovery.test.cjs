const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/web/recovery.js', 'utf8');

function page(hash, passwords = ['new-secret', 'new-secret'], response = {ok: true, message: 'Password updated.'}) {
  const nodes = Object.fromEntries(['recovery-reset', 'recovery-status', 'recovery-request', 'recovery-heading', 'recovery-description'].map(id => [id, {hidden: id === 'recovery-reset', dataset: {}}]));
  const button = {};
  const form = nodes['recovery-reset'];
  form.querySelector = () => button;
  form.addEventListener = (type, listener) => { form.submit = listener; };
  form.reset = () => { form.cleared = true; };
  const calls = [];
  const history = [];
  vm.runInNewContext(script, {
    document: {getElementById: id => nodes[id]},
    location: {hash, pathname: '/forgot-password.html'},
    history: {replaceState: (...args) => history.push(args)},
    URLSearchParams,
    FormData: class extends Map { constructor() { super([['password', passwords[0]], ['confirmPassword', passwords[1]]]); } },
    fetch: async (path, options) => { calls.push({path, options}); return {ok: response.ok, json: async () => response}; }
  });
  return {nodes, form, button, calls, history};
}

test('recovery page without a link gives administrator instructions', () => {
  const view = page('');
  assert.equal(view.form.hidden, true);
  assert.equal(view.nodes['recovery-request'].hidden, false);
  assert.equal(view.calls.length, 0);
});

test('recovery removes token from history and submits it only in the reset body', async () => {
  const view = page('#token=private-token');
  assert.equal(view.form.hidden, false);
  assert.equal(view.history[0][2], '/forgot-password.html');
  await view.form.submit({preventDefault() {}});
  assert.equal(view.calls[0].path, '/api/password/reset');
  assert.equal(view.calls[0].options.body.get('token'), 'private-token');
  assert.equal(view.form.hidden, true);
  assert.equal(view.form.cleared, true);
  assert.equal(view.nodes['recovery-status'].textContent, 'Password updated.');
});

test('mismatched passwords do not submit and expired links display an error', async () => {
  const mismatch = page('#token=private', ['one', 'two']);
  await mismatch.form.submit({preventDefault() {}});
  assert.equal(mismatch.calls.length, 0);
  assert.equal(mismatch.nodes['recovery-status'].dataset.error, 'true');
  const expired = page('#token=private', undefined, {ok: false, error: 'Link expired'});
  await expired.form.submit({preventDefault() {}});
  assert.equal(expired.nodes['recovery-status'].textContent, 'Link expired');
  assert.equal(expired.button.disabled, false);
  assert.equal(expired.form.hidden, false);
});
