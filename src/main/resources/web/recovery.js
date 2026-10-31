const resetForm = document.getElementById('recovery-reset');
const recoveryStatus = document.getElementById('recovery-status');
const token = new URLSearchParams(location.hash.slice(1)).get('token');
// Keep the credential out of URLs copied later, server logs, and referrer headers.
if (location.hash) history.replaceState(null, '', location.pathname);
if (token) {
  document.getElementById('recovery-request').hidden = true;
  document.getElementById('recovery-heading').textContent = 'Write a new chapter';
  document.getElementById('recovery-description').textContent = 'Choose and confirm a new password to restore access to your account.';
  resetForm.hidden = false;
}
resetForm.addEventListener('submit', async event => {
  event.preventDefault();
  const fields = new FormData(resetForm);
  const button = resetForm.querySelector('button');
  recoveryStatus.dataset.error = 'false';
  if (fields.get('password') !== fields.get('confirmPassword')) {
    recoveryStatus.textContent = 'Passwords must match.';
    recoveryStatus.dataset.error = 'true';
    return;
  }
  button.disabled = true;
  try {
    fields.set('token', token || '');
    const response = await fetch('/api/password/reset', {
      method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: new URLSearchParams(fields)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Unable to reset your password.');
    resetForm.reset();
    resetForm.hidden = true;
    recoveryStatus.textContent = data.message;
  } catch (error) {
    recoveryStatus.textContent = error.message;
    recoveryStatus.dataset.error = 'true';
  } finally { button.disabled = false; }
});
