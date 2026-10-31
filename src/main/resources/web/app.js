// Local Figma exports supply the visuals; this script adds navigation and account actions.
const routes = {
  'Forgot password?': 'forgot-password.html',
  'Home': 'index.html', 'About': 'about.html', 'Scrolls': 'browse.html', 'Browse Scrolls': 'browse.html',
  'Dashboard': 'dash.html', 'Upload Scroll': 'upload.html', 'My Scrolls': 'browse.html?mine=1',
  'Favourites': 'browse.html?favourites=1',
  'Profile': 'profile.html', 'Create account': 'register.html',
  "Don't have an account?  Create account": 'register.html',
  'Already have an account?  Login': 'login.html', '← Back to Scrolls': 'browse.html',
  'Cancel': 'browse.html'
};
const fieldNames = {
  'Username': 'username', 'Username or email': 'username', 'Password': 'password',
  'Confirm password': 'confirmPassword', 'Full name': 'fullName',
  'Email': 'email', 'Phone number': 'phone',
  'Unique scroll name': 'scrollName',
  'Binary file': 'binaryFile'
};
const page = location.pathname.split('/').pop() || 'index.html';
let currentUser = null;
let sessionRevision = 0;
let sessionPending = false;
let scrollRequestId = 0;

function activate(el, action) {
  el.tabIndex = 0;
  el.setAttribute('role', 'button');
  el.style.cursor = 'pointer';
  el.addEventListener('click', action);
  el.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      action();
    }
  });
}

for (const [label, name] of Object.entries(fieldNames)) {
  if (name === 'binaryFile' || page === 'profile.html') continue;
  const el = [...document.querySelectorAll('span')]
    .find(node => node.textContent.trim() === label);
  if (!el) continue;
  const field = document.createElement('input');
  field.type = ['password', 'confirmPassword'].includes(name) ? 'password'
    : label === 'Binary file' ? 'file'
      : label === 'Email' ? 'email' : label === 'Phone number' ? 'tel' : 'text';
  field.name = name;
  field.required = !['scrollName', 'idKey'].includes(name);
  if (name === 'scrollName') {
    field.style.width = '620px';
    field.style.height = '24px';
    field.style.fontSize = '14px';
    field.style.color = '#172b19';
  }
  field.setAttribute('aria-label', label === 'Username or email' ? 'Username' : label);
  field.placeholder = label === 'Username or email' ? 'Username' : label;
  field.autocomplete = ['password', 'confirmPassword'].includes(name)
    ? (page === 'register.html' ? 'new-password' : 'current-password')
    : name === 'username' ? 'username' : name;
  field.className = el.className;
  field.style.background = 'transparent';
  field.style.border = 'none';
  field.style.outlineOffset = '4px';
  el.replaceWith(field);
}

const notice = document.createElement('div');
notice.id = 'status-notice';
notice.setAttribute('role', 'status');
notice.setAttribute('aria-live', 'polite');
notice.style.cssText = 'position:fixed;bottom:0;left:0;right:0;z-index:999;background:#172b19;color:white;padding:10px;text-align:center;font:14px sans-serif';
document.body.append(notice);

function setStatus(message, error = false) {
  notice.textContent = message;
  notice.style.background = error ? '#7a1f1f' : '#172b19';
}

async function request(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || 'Request failed');
    error.status = response.status;
    throw error;
  }
  return data;
}

function formValues(names) {
  return Object.fromEntries(names.map(name => [name,
    document.querySelector(`input[name="${name}"]`)?.value ?? '']));
}

async function submitRegistration() {
  const button = [...document.querySelectorAll('span')]
    .find(node => node.textContent.trim() === 'Register');
  try {
    button?.setAttribute('aria-disabled', 'true');
    setStatus('Creating account…');
    const values = formValues([
      'username', 'password', 'confirmPassword', 'fullName', 'email', 'phone'
    ]);
    if (!values.confirmPassword) throw new Error('Confirm password is required');
    if (values.password !== values.confirmPassword) throw new Error('Passwords do not match');
    await request('/api/register', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
      body: new URLSearchParams(values)
    });
    location.href = 'login.html?registered=1';
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    button?.removeAttribute('aria-disabled');
  }
}

async function submitLogin() {
  const button = [...document.querySelectorAll('span')]
    .find(node => node.textContent.trim() === 'Login');
  try {
    button?.setAttribute('aria-disabled', 'true');
    setStatus('Signing in…');
    const values = formValues(['username', 'password']);
    await request('/api/login', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
      body: new URLSearchParams(values)
    });
    location.href = 'dash.html';
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    button?.removeAttribute('aria-disabled');
  }
}

async function logout() {
  invalidateSessionCards();
  try {
    await request('/api/logout', {method: 'POST'});
  } finally {
    invalidateSessionCards();
    updateDashboardVisibility(null);
    applySessionNavigation(null);
    location.href = 'index.html';
  }
}

for (const el of document.querySelectorAll('span')) {
  const label = el.textContent.trim();
  if (el.hasAttribute('data-scroll-heading')) continue;
  if (label === 'Register' && page === 'register.html') {
    activate(el, submitRegistration);
  } else if (label === 'Login' && page === 'login.html') {
    activate(el, submitLogin);
  } else if (page === 'profile.html' && ['Edit profile', 'Change password'].includes(label)) {
    activate(el, () => openProfileEditor(label === 'Change password'));
  } else if (label === 'Log out') {
    activate(el, logout);
  }   else if (label === 'Upload Scroll' && page === 'upload.html' && el.classList.contains('v1_182')) {
    activate(el, submitUpload);
  } else if (label === 'Search' && page === 'browse.html') {
    activate(el, () => loadScrolls());
  } else if (routes[label]) {
    activate(el, () => { location.href = routes[label]; });
  } else if (label === 'Login') {
    activate(el, () => { location.href = 'login.html'; });

  } else if (['Search', 'Preview / Download', 'Edit my scroll', 'Delete',
    'Edit profile', 'Change password', 'Forgot password?'].includes(label)) {
    activate(el, () => setStatus('This feature is not implemented yet.', true));
  } else if (['Agile Development Guide', 'Database Systems Notes',
    'Software Testing Handbook', 'Design Patterns Overview'].includes(label)) {
    activate(el, () => { location.href = 'details.html'; });
  }
}

for (const field of document.querySelectorAll('input')) {
  field.addEventListener('keydown', event => {
    if (event.key !== 'Enter') return;
    if (page === 'register.html') submitRegistration();
    if (page === 'login.html') submitLogin();
    if (page === 'upload.html') submitUpload();
  });
}

function renderProfile(user) {
  for (const picture of document.querySelectorAll('[data-profile-picture]')) {
    picture.src = user?.picture || './images/v11_80.png';
    picture.alt = user ? `${user.fullName || user.username}'s profile picture` : 'Profile picture';
  }
  const userTypes = {GENERAL: 'General User', ADMIN: 'Admin', GUEST: 'Guest'};
  for (const element of document.querySelectorAll('[data-profile-field]')) {
    const field = element.dataset.profileField;
    element.textContent = user
      ? (field === 'type' ? userTypes[user.type] || user.type : user[field]) || '—'
      : '—';
  }
  for (const greeting of document.querySelectorAll('[data-session-greeting]')) {
    greeting.textContent = user ? `Welcome, ${user.username}!` : 'Welcome, Guest!';
  }
  for (const element of document.querySelectorAll('[data-session-name]')) {
    element.textContent = `${user ? user.username : 'Guest'}  ▾`;
    element.title = user ? user.username : 'Guest';
  }
}

const memberNavigationLabels = new Set([
  'Upload Scroll', 'My Scrolls', 'Profile', 'Log out', 'Edit my scroll', 'Delete'
]);

function hasMemberSearchFilters(user) {
  return user?.type === 'GENERAL' || user?.type === 'ADMIN';
}

function updateBrowseSearchOptions(user) {
  if (page !== 'browse.html') return;
  const form = document.getElementById('scroll-filters');
  if (!form) return;
  const memberFilters = hasMemberSearchFilters(user);
  const mine = new URLSearchParams(location.search).get('mine') === '1';
  for (const name of ['uploaderId', 'uploadDate']) {
    const input = form.querySelector(`input[name="${name}"]`);
    if (!input) continue;
    const label = input.parentElement;
    label.hidden = !memberFilters;
    // Inline display:grid would otherwise override the browser's hidden style.
    label.style.display = memberFilters ? 'grid' : 'none';
    input.disabled = !memberFilters || (mine && name === 'uploaderId');
    if (!memberFilters) input.value = '';
  }
  form.style.gridTemplateColumns = `repeat(${memberFilters ? 4 : 2},minmax(0,1fr)) auto`;
}

function applySessionNavigation(user) {
  updateBrowseSearchOptions(user);
  const favouritesLink = document.getElementById('favourites-link');
  if (hasMemberSearchFilters(user)) {
    const sidebar = document.querySelector('.v1_58, .v1_112, .v1_162, .v1_192, .v1_221');
    if (sidebar && !favouritesLink) {
      const link = document.createElement('a');
      link.id = 'favourites-link';
      link.href = 'browse.html?favourites=1';
      link.textContent = 'Favourites';
      link.className = 'favourites-link';
      if (isFavouritesView()) link.setAttribute('aria-current', 'page');
      sidebar.append(link);
    }
  } else {
    favouritesLink?.remove();
  }
  for (const element of document.querySelectorAll('span')) {
    const label = element.textContent.trim();
    if (memberNavigationLabels.has(label)) {
      element.hidden = !user;
    }
    if (!user && label === 'Preview / Download') element.textContent = 'Preview';
  }
  const activityLink = document.getElementById('activity-link');
  if (user?.type === 'ADMIN') {
    const sidebar = document.querySelector('.v1_58, .v1_112, .v1_162, .v1_192, .v1_221');
    if (sidebar && !activityLink) {
      const link = document.createElement('a');
      link.id = 'activity-link';
      link.href = 'activity.html';
      link.textContent = 'Activity log';
      link.className = 'admin-activity-link';
      sidebar.append(link);
    }
  } else {
    activityLink?.remove();
  }
  const guestLinks = document.getElementById('guest-auth-links');
  if (user) {
    guestLinks?.remove();
    return;
  }
  if (guestLinks || !document.querySelector('[data-session-name]')) return;
  const navigation = document.createElement('nav');
  navigation.id = 'guest-auth-links';
  navigation.setAttribute('aria-label', 'Guest account actions');
  navigation.style.cssText = 'position:fixed;top:14px;right:24px;z-index:1000;background:#172b19;padding:8px 14px;border-radius:12px;font:14px Inter,sans-serif';
  navigation.innerHTML = '<a href="login.html" style="color:#fdf9c4">Log in</a>'
    + '<span aria-hidden="true" style="color:#fdf9c4"> · </span>'
    + '<a href="register.html" style="color:#fdf9c4">Create account</a>';
  document.body.append(navigation);
}

function guestPageRequiresSignIn() {
  const mine = page === 'browse.html'
    && new URLSearchParams(location.search).get('mine') === '1';
  return mine || isFavouritesView() || ['upload.html', 'profile.html'].includes(page);
}

function isFavouritesView() {
  return page === 'browse.html' && new URLSearchParams(location.search).get('favourites') === '1';
}

function showGuestRestriction() {
  if (document.getElementById('guest-restriction')) return;
  for (const child of document.body.children) {
    if (['guest-auth-links', 'status-notice'].includes(child.id)) continue;
    child.inert = true;
    child.setAttribute('aria-hidden', 'true');
  }
  const message = document.createElement('section');
  message.id = 'guest-restriction';
  message.setAttribute('role', 'alert');
  message.style.cssText = 'position:fixed;inset:120px max(24px,calc((100vw - 560px)/2)) auto;z-index:1100;background:#f7fccc;color:#172b19;border:2px solid #798a23;border-radius:20px;padding:28px;text-align:center;font:16px Inter,sans-serif;box-shadow:0 12px 32px rgba(0,0,0,.25)';
  message.innerHTML = '<h2 style="margin-top:0">Sign in required</h2>'
    + '<p>Guests can browse, search, filter and preview scrolls. Sign in to use this member feature.</p>'
    + '<p><a href="login.html">Log in</a> · <a href="register.html">Create account</a>'
    + ' · <a href="browse.html">Continue as guest</a></p>';
  document.body.append(message);
}

function clearGuestRestriction() {
  if (!document.getElementById('guest-restriction')) return;
  document.getElementById('guest-restriction').remove();
  for (const child of document.body.children) {
    child.inert = false;
    child.removeAttribute('aria-hidden');
  }
}

// Discard both visible private state and in-flight results before checking a changed session.
function invalidateSessionCards() {
  sessionRevision++;
  scrollRequestId++;
  sessionPending = true;
  currentUser = null;
  for (const id of ['scroll-library', 'dashboard-scrolls']) {
    document.getElementById(id)?.replaceChildren();
  }
  updateDashboardVisibility(null);
}

async function refreshSessionStatus() {
  invalidateSessionCards();
  const revision = sessionRevision;
  const justRegistered = new URLSearchParams(location.search).has('registered');
  let user;
  try {
    user = await request('/api/session', {cache: 'no-store'});
  } catch {
    user = null;
  }
  if (revision !== sessionRevision) return undefined;
  sessionPending = false;
  currentUser = user;
  if (user) {
    clearGuestRestriction();
    renderProfile(user);
    applySessionNavigation(user);
    await loadDashboard(user);
    if (revision !== sessionRevision) return undefined;
    setStatus(`Signed in as ${user.fullName} (${user.type.toLowerCase()})`);
    if (user.type === 'ADMIN' && !document.getElementById('admin-link')) {
      const link = document.createElement('a');
      link.id = 'admin-link';
      link.href = 'admin.html';
      link.textContent = 'Admin';
      link.style.cssText = 'position:fixed;top:16px;right:24px;z-index:1000;background:#172b19;color:#fdf9c4;padding:8px 14px;border-radius:12px;text-decoration:none;font:14px Inter,sans-serif';
      document.body.append(link);
    }
    if (user.type !== 'ADMIN') document.getElementById('admin-link')?.remove();
    return user;
  } else {
    document.getElementById('admin-link')?.remove();
    renderProfile(null);
    applySessionNavigation(null);
    await loadDashboard(null);
    if (revision !== sessionRevision) return undefined;
    setStatus(justRegistered
      ? 'Account created successfully. Sign in with your username and password.'
      : 'Guest mode • Browse, search, filter and preview without signing in.');
    return null;
  }
}

function bindScrollFilePicker() {
  if (page !== 'upload.html') return;
  const zone = [...document.querySelectorAll('span')]
    .find(node => node.textContent.includes('Drop binary file'));
  const input = document.createElement('input');
  input.type = 'file';
  input.name = 'binaryFile';
  input.setAttribute('aria-label', 'Binary file');
  input.style.cssText = 'position:absolute;top:410px;left:260px;width:650px;height:120px;opacity:0;cursor:pointer;z-index:20';
  input.addEventListener('change', () => {
    const file = input.files && input.files[0];
    if (zone) zone.textContent = file ? file.name : 'Drop binary file here or click to browse';
  });
  document.body.append(input);
}

async function submitUpload() {
  const button = document.querySelector('.v1_182');
  const file = document.querySelector('input[name="binaryFile"]')?.files?.[0];
  try {
    button?.setAttribute('aria-disabled', 'true');
    if (!file) throw new Error('Choose a scroll file to upload');
    setStatus('Uploading scroll…');
    const body = new FormData();
    body.append('name', document.querySelector('input[name="scrollName"]')?.value ?? '');
    body.append('file', file, file.name);
    await request('/api/scrolls', {method: 'POST', body});
    location.href = 'browse.html?mine=1';
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    button?.removeAttribute('aria-disabled');
  }
}

async function loadScrolls() {
  if (page !== 'browse.html' || sessionPending) return;
  const favourites = isFavouritesView();
  if (favourites && !hasMemberSearchFilters(currentUser)) {
    document.getElementById('scroll-library')?.replaceChildren();
    showGuestRestriction();
    return;
  }
  const revision = sessionRevision;
  const requestId = ++scrollRequestId;
  const mine = !favourites && new URLSearchParams(location.search).get('mine') === '1';
  document.title = favourites ? 'VSAS — Favourites' : mine ? 'VSAS — My Scrolls' : 'VSAS — Browse';
  const heading = document.querySelector('[data-scroll-heading]');
  if (heading) heading.textContent = favourites ? 'Favourites' : mine ? 'My Scrolls' : 'Browse Scrolls';
  const subtitle = document.querySelector('.v1_121');
  if (subtitle) subtitle.textContent = favourites ? 'Your saved scrolls, ready whenever you return.' : mine
    ? 'View and manage your uploaded scrolls.' : 'Explore the library of knowledge.';
  const selectedItem = document.querySelector(favourites ? '#favourites-link' : mine ? '.v1_117' : '.v1_115');
  selectedItem?.setAttribute('aria-current', 'page');
  const highlight = document.querySelector('.v1_114');
  if (highlight) highlight.style.top = favourites ? '332px' : mine ? '236px' : '140px';
  for (const node of document.querySelectorAll('[class^="v1_13"], [class^="v1_14"], [class^="v1_15"]')) {
    const match = node.className.match(/v1_(\d+)/);
    const number = match ? Number(match[1]) : 0;
    if (number >= 130 && number <= 154) node.style.display = 'none';
  }
  let library = document.getElementById('scroll-library');
  if (!library) {
    library = document.createElement('div');
    library.id = 'scroll-library';
    library.style.cssText = 'position:absolute;top:265px;left:210px;width:940px;height:430px;overflow:auto;z-index:6;display:flex;flex-wrap:wrap;gap:16px;align-content:flex-start';
    document.body.append(library);
  }
  const params = new URLSearchParams();
  if (mine) params.set('mine', '1');

  let hasFilters = false;

  const filterFields = [
    ['scrollSearch', 'name'],
    ['uploaderId', 'uploaderId'],
    ['scrollId', 'scrollId'],
    ['uploadDate', 'uploadDate']
  ];

  for (const [inputName, parameter] of filterFields) {
    if (mine && parameter === 'uploaderId') continue;
    if (!hasMemberSearchFilters(currentUser)
        && ['uploaderId', 'uploadDate'].includes(parameter)) continue;

    const value = document
        .querySelector(`input[name="${inputName}"]`)
        ?.value.trim() ?? '';

    if (value) {
      params.set(parameter, value);
      hasFilters = true;
    }
  }
  try {
    const endpoint = favourites ? '/api/favourites' : '/api/scrolls';
    const data = await request(endpoint + (params.toString() ? '?' + params : ''), {
      cache: 'no-store', headers: currentUser
        ? {'X-VSAS-User-ID': encodeURIComponent(currentUser.idKey), 'X-VSAS-Username': encodeURIComponent(currentUser.username)} : {}
    });
    if (revision !== sessionRevision || requestId !== scrollRequestId) return;
    library.replaceChildren();
    if (!data.scrolls.length) {
      const empty = document.createElement('p');
      empty.textContent = hasFilters
          ? 'No scrolls match your filters.'
          : favourites
              ? 'No favourites yet. Browse Scrolls and select a star to save one here.'
          : mine
              ? 'You have not uploaded a scroll yet.'
              : 'No scrolls have been uploaded yet.';
      empty.style.cssText = 'color:#f4e7ca;font:16px/1.6 Inter,sans-serif;margin:24px';
      if (favourites && !hasFilters) {
        const browse = document.createElement('a');
        browse.href = 'browse.html';
        browse.textContent = 'Browse Scrolls';
        browse.style.cssText = 'display:block;margin-top:12px;color:#edcf8d';
        empty.append(browse);
      }
      library.append(empty);
      return;
    }
    for (const scroll of data.scrolls) library.append(scrollCard(scroll));
  } catch (error) {
    if (revision !== sessionRevision || requestId !== scrollRequestId) return;
    library.replaceChildren();
    if (error.status === 401 || error.status === 409) {
      invalidateSessionCards();
      applySessionNavigation(null);
      if (guestPageRequiresSignIn()) showGuestRestriction();
      setStatus('Your session changed. Refresh the page or sign in again.', true);
      return;
    }
    setStatus(error.message, true);
  }
}

function scrollCard(scroll) {
  const card = document.createElement('article');
  card.className = 'scroll-card';
  card.tabIndex = 0;
  card.setAttribute('aria-label', `${scroll.name} — focus to open preview`);
  card.style.cssText = 'width:215px;min-height:250px;background:#f7fccc;border:1px solid #bad9b4;border-radius:18px;padding:14px;box-shadow:0 8px 20px rgba(0,0,0,.08);color:#334038;font:14px Inter,sans-serif';
  const title = document.createElement('h3');
  title.textContent = scroll.name;
  title.style.cssText = 'margin:28px 0 8px;font-size:15px';
  const meta = document.createElement('p');
  meta.textContent = `Scroll ID: ${scroll.id}\nUploader: ${scroll.uploaderUsername || 'Deleted user'}\nUploaded: ${scroll.uploadDate}`;
  if (currentUser?.type === 'ADMIN') {
    const downloads = Number.isSafeInteger(scroll.downloads) && scroll.downloads >= 0
        ? scroll.downloads : '—';
    meta.textContent += `\nDownloads: ${downloads}`;
  }
  meta.style.cssText = 'margin:0 0 8px;font-size:12px';
  const preview = document.createElement('pre');
  const parchmentTones = ['gold', 'sage', 'rose', 'sky', 'lavender', 'peach'];
  // The immutable, randomly generated scroll ID keeps its colour stable on every device.
  let colourHash = 2166136261;
  for (const character of scroll.id) {
    colourHash = Math.imul(colourHash ^ character.charCodeAt(0), 16777619);
  }
  preview.setAttribute('data-scroll-tone', parchmentTones[(colourHash >>> 0) % parchmentTones.length]);
  const previewContent = document.createElement('span');
  previewContent.className = 'scroll-preview-content';
  previewContent.textContent = scroll.preview || '';
  preview.append(previewContent);
  preview.style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.6 ui-monospace,monospace;margin:0 0 10px';
  const actions = document.createElement('div');
  actions.style.display = 'flex';
  actions.style.gap = '8px';
  actions.append(favouriteButton(scroll));
  if (currentUser) {
    const download = document.createElement('button');
    download.type = 'button';
    download.textContent = 'Download';
    download.style.cssText = 'border:0;border-radius:12px;padding:6px 10px;background:#798a23;cursor:pointer';
    download.addEventListener('click', () => downloadScroll(scroll));
    actions.append(download);
  }
  if (currentUser && scroll.owned) {
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.textContent = 'Edit';
    edit.style.cssText =
        'border:0;border-radius:12px;padding:6px 10px;background:#d9e88f;cursor:pointer';
    edit.addEventListener('click', () => openScrollEditor(scroll));
    actions.append(edit);

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = 'Delete';
    remove.style.cssText = 'border:0;border-radius:12px;padding:6px 10px;background:#7a1f1f;color:white;cursor:pointer';
    remove.addEventListener('click', () => deleteScroll(scroll));
    actions.append(remove);
  }
  card.append(title, meta, preview, actions);
  return card;
}

function favouriteButton(scroll) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'favourite-toggle';
  const saved = Boolean(currentUser && scroll.favourite);
  button.textContent = saved ? '★ Saved' : '☆ Save';
  button.setAttribute('aria-pressed', String(saved));
  button.setAttribute('aria-label', `${saved ? 'Remove' : 'Add'} ${scroll.name} ${saved ? 'from' : 'to'} favourites`);
  button.addEventListener('click', () => toggleFavourite(scroll, button));
  return button;
}

function favouriteSignInPrompt() {
  setStatus('Sign in to save scrolls to your favourites. ');
  const link = document.createElement('a');
  link.href = 'login.html';
  link.textContent = 'Log in';
  link.style.color = '#fdf9c4';
  notice.append(link);
}

async function toggleFavourite(scroll, button) {
  if (button.disabled || sessionPending) return;
  if (!hasMemberSearchFilters(currentUser)) {
    favouriteSignInPrompt();
    return;
  }
  const revision = sessionRevision;
  const owner = currentUser.idKey;
  const ownerUsername = currentUser.username;
  const saved = Boolean(scroll.favourite);
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  try {
    // A different tab may have changed the cookie since these cards were rendered.
    const session = await request('/api/session', {cache: 'no-store'});
    if (revision !== sessionRevision) return;
    if (session.idKey !== owner || session.username !== ownerUsername) {
      await refreshBrowseSession();
      setStatus('Your account changed. Choose the scroll again in your current account.');
      return;
    }
    const result = await request('/api/favourites/' + encodeURIComponent(scroll.id), {
      method: saved ? 'DELETE' : 'PUT',
      headers: {'X-VSAS-User-ID': encodeURIComponent(owner), 'X-VSAS-Username': encodeURIComponent(ownerUsername)}
    });
    if (revision !== sessionRevision) return;
    scroll.favourite = result.favourite;
    button.textContent = scroll.favourite ? '★ Saved' : '☆ Save';
    button.setAttribute('aria-pressed', String(Boolean(scroll.favourite)));
    button.setAttribute('aria-label', `${scroll.favourite ? 'Remove' : 'Add'} ${scroll.name} ${scroll.favourite ? 'from' : 'to'} favourites`);
    setStatus(scroll.favourite
      ? `Saved ${scroll.name} to your favourites.` : `Removed ${scroll.name} from your favourites.`);
    // A filter request may have replaced this button or captured the previous saved state.
    // The new request invalidates those snapshots and renders the confirmed server state.
    await loadScrolls();
  } catch (error) {
    if (revision !== sessionRevision) return;
    if (error.status === 401 || error.status === 409) {
      await refreshBrowseSession();
      if (hasMemberSearchFilters(currentUser)) {
        setStatus('Your account changed. Choose the scroll again in your current account.');
      } else {
        favouriteSignInPrompt();
      }
    } else if (error.status === 404) {
      await loadScrolls();
      setStatus('This scroll is no longer available. The list has been refreshed.', true);
    } else {
      setStatus(`Could not ${saved ? 'remove' : 'save'} favourite: ${error.message}. Please try again.`, true);
    }
  } finally {
    button.disabled = false;
    button.removeAttribute('aria-busy');
  }
}

async function downloadScroll(scroll) {
  try {
    setStatus('Downloading ' + scroll.name + '…');
    const response = await fetch('/api/scrolls/' + encodeURIComponent(scroll.id) + '/download');
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(data.error || 'Download failed');
    }
    const blob = await response.blob();
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = scroll.originalFileName || scroll.id + '.bin';
    link.click();
    URL.revokeObjectURL(link.href);
    if (page === 'browse.html') await refreshBrowseSession();
    else if (page === 'dash.html') await refreshSessionStatus();
    setStatus('Downloaded ' + scroll.name + '.');
  } catch (error) {
    setStatus(error.message, true);
  }
}

function openScrollEditor(scroll) {
  const dialog = document.createElement('dialog');
  dialog.style.cssText =
      'border:1px solid #bad9b4;border-radius:18px;padding:24px;width:380px;color:#172b19';

  const form = document.createElement('form');
  form.style.cssText = 'display:grid;gap:14px';

  const heading = document.createElement('h2');
  heading.textContent = 'Edit uploaded scroll';

  const nameLabel = document.createElement('label');
  nameLabel.textContent = 'Scroll name';

  const nameInput = document.createElement('input');
  nameInput.type = 'text';
  nameInput.required = true;
  nameInput.maxLength = 80;
  nameInput.value = scroll.name;
  nameInput.style.cssText =
      'display:block;width:100%;box-sizing:border-box;padding:8px;margin-top:5px';
  nameLabel.append(nameInput);

  const fileLabel = document.createElement('label');
  fileLabel.textContent = 'Replacement file (optional)';

  const fileInput = document.createElement('input');
  fileInput.type = 'file';
  fileInput.style.cssText = 'display:block;margin-top:5px';
  fileLabel.append(fileInput);

  const buttons = document.createElement('div');
  buttons.style.cssText = 'display:flex;justify-content:flex-end;gap:8px';

  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.textContent = 'Cancel';

  const save = document.createElement('button');
  save.type = 'submit';
  save.textContent = 'Save changes';

  buttons.append(cancel, save);
  form.append(heading, nameLabel, fileLabel, buttons);
  dialog.append(form);
  document.body.append(dialog);

  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => dialog.remove(), {once: true});

  form.addEventListener('submit', async event => {
    event.preventDefault();
    save.disabled = true;

    try {
      const body = new FormData();
      body.append('name', nameInput.value.trim());

      if (fileInput.files.length > 0) {
        body.append('file', fileInput.files[0]);
      }

      setStatus('Updating ' + scroll.name + '…');

      await request(
          '/api/scrolls/' + encodeURIComponent(scroll.id),
          {
            method: 'PUT',
            body
          }
      );

      dialog.close();
      setStatus('Updated scroll successfully.');
      await loadScrolls();
    } catch (error) {
      setStatus(error.message, true);
      save.disabled = false;
    }
  });

  dialog.showModal();
  nameInput.focus();
}

async function deleteScroll(scroll) {
  if (!confirm('Delete "' + scroll.name + '"? Only you can remove a scroll you uploaded.')) return;
  try {
    setStatus('Deleting ' + scroll.name + '…');
    await request('/api/scrolls/' + encodeURIComponent(scroll.id), {method: 'DELETE'});
    setStatus('Deleted ' + scroll.name + '.');
    await loadScrolls();
    if (page === 'dash.html') await refreshSessionStatus();
  } catch (error) {
    setStatus(error.message, true);
  }
}

function bindBrowseSearch() {
  if (page !== 'browse.html') return;
  if (document.getElementById('scroll-filters')) {
    updateBrowseSearchOptions(currentUser);
    return;
  }

  // Hide the original placeholder search controls.
  for (let number = 122; number <= 129; number++) {
    const element = document.querySelector('.v1_' + number);
    if (element) element.style.display = 'none';
  }

  const mine = new URLSearchParams(location.search).get('mine') === '1';

  const form = document.createElement('form');
  form.id = 'scroll-filters';
  form.setAttribute('aria-label', 'Search and filter scrolls');
  form.style.cssText =
      'position:absolute;top:180px;left:225px;width:915px;z-index:7;' +
      'display:grid;grid-template-columns:repeat(4,minmax(0,1fr)) auto;' +
      'gap:12px;align-items:end;font:13px Inter,sans-serif';

  const fields = [
    ['scrollSearch', 'Scroll name', 'text'],
    ['uploaderId', 'Uploader ID', 'text'],
    ['scrollId', 'Scroll ID', 'text'],
    ['uploadDate', 'Upload date', 'date']
  ];

  for (const [name, title, type] of fields) {
    const label = document.createElement('label');
    label.textContent = title;
    label.style.cssText = 'color:#fdf9c4;display:grid;gap:6px';

    const input = document.createElement('input');
    input.name = name;
    input.type = type;
    input.setAttribute('aria-label', title);
    input.style.cssText =
        'width:100%;min-width:0;height:42px;padding:8px;' +
        'border:1px solid #bad9b4;border-radius:12px;' +
        'background:#fdf9c4;color:#172b19;font:13px Inter,sans-serif';

    if (name === 'uploaderId' && mine) {
      input.disabled = true;
      input.placeholder = 'Your uploads only';
    }

    label.append(input);
    form.append(label);
  }

  const actions = document.createElement('div');
  actions.style.cssText = 'display:flex;gap:6px';

  const search = document.createElement('button');
  search.type = 'submit';
  search.textContent = 'Search';

  const clear = document.createElement('button');
  clear.type = 'button';
  clear.textContent = 'Clear';

  for (const button of [search, clear]) {
    button.style.cssText =
        'height:42px;padding:8px 12px;border:1px solid #bad9b4;' +
        'border-radius:12px;background:#7c4625;color:#fdf9c4;cursor:pointer';
  }

  form.addEventListener('submit', event => {
    event.preventDefault();
    loadScrolls();
  });

  clear.addEventListener('click', () => {
    form.reset();
    loadScrolls();
  });

  actions.append(search, clear);
  form.append(actions);
  document.body.append(form);
  updateBrowseSearchOptions(currentUser);
}

if (page === 'upload.html') {
  const scrollIdLabel = [...document.querySelectorAll('span')]
    .find(node => node.textContent.trim() === 'Scroll ID');
  if (scrollIdLabel) {
    scrollIdLabel.textContent = 'Scroll ID is assigned automatically';
    scrollIdLabel.style.width = '280px';
  }
}

async function initialisePage() {
  const user = await refreshSessionStatus();
  if (user === undefined) return;
  if (!user && guestPageRequiresSignIn()) {
    showGuestRestriction();
    return;
  }
  bindScrollFilePicker();
  bindBrowseSearch();
  await loadScrolls();
}

initialisePage();

// Recheck the public browse page after account changes in another tab or a back navigation.
async function refreshBrowseSession() {
  const user = await refreshSessionStatus();
  if (user === undefined) return;
  if ((!user || !hasMemberSearchFilters(user)) && guestPageRequiresSignIn()) {
    showGuestRestriction();
    return;
  }
  bindBrowseSearch();
  await loadScrolls();
}

if (page === 'dash.html') {
  window.addEventListener('focus', refreshSessionStatus);
  window.addEventListener('pageshow', event => {
    if (event.persisted) return refreshSessionStatus();
  });
}

if (page === 'browse.html') {
  window.addEventListener('focus', refreshBrowseSession);
  window.addEventListener('pageshow', event => {
    if (event.persisted) return refreshBrowseSession();
  });
}

if (['browse.html', 'dash.html'].includes(page)) {
  window.addEventListener('pagehide', invalidateSessionCards);
}

async function openProfileEditor(passwordMode) {
  try {
    const user = await request('/api/session');
    document.getElementById('profile-editor')?.remove();
    const dialog = document.createElement('dialog');
    dialog.id = 'profile-editor';
    dialog.style.cssText = 'border:1px solid #798a23;border-radius:18px;padding:28px;background:#f7fccc;color:#172b19;width:min(400px,80vw);font:14px Inter,sans-serif';
    const heading = document.createElement('h2');
    heading.id = 'profile-editor-heading';
    heading.textContent = passwordMode ? 'Change password' : 'Edit profile';
    dialog.setAttribute('aria-labelledby', heading.id);
    const form = document.createElement('form');
    const fields = passwordMode
      ? [['currentPassword', 'Current password', 'password'], ['newPassword', 'New password', 'password'], ['confirmPassword', 'Confirm new password', 'password']]
      : [['fullName', 'Full name', 'text'], ['email', 'Email', 'email'], ['phone', 'Phone', 'tel']];
    for (const [name, title, type] of fields) {
      const label = document.createElement('label');
      label.textContent = title;
      label.style.cssText = 'display:block;margin:16px 0';
      const input = document.createElement('input');
      input.name = name;
      input.type = type;
      input.required = true;
      input.value = passwordMode ? '' : user[name];
      input.autocomplete = type === 'password'
        ? (name === 'currentPassword' ? 'current-password' : 'new-password') : name === 'fullName' ? 'name' : type;
      input.style.cssText = 'display:block;box-sizing:border-box;width:100%;padding:10px;margin-top:6px;border:1px solid #798a23;border-radius:8px';
      label.append(input);
      form.append(label);
    }
    const errorMessage = document.createElement('p');
    errorMessage.setAttribute('role', 'alert');
    const save = document.createElement('button');
    save.type = 'submit';
    save.textContent = 'Save changes';
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.textContent = 'Cancel';
    cancel.addEventListener('click', () => dialog.close());
    for (const button of [save, cancel]) {
      button.style.cssText = 'padding:10px 16px;margin-right:8px;border:1px solid #798a23;border-radius:10px;cursor:pointer';
    }
    form.append(errorMessage, save, cancel);
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (save.disabled) return;
      save.disabled = true;
      errorMessage.textContent = '';
      try {
        const data = new FormData(form);
        if (passwordMode && data.get('newPassword') !== data.get('confirmPassword')) {
          throw new Error('New passwords do not match');
        }
        const updated = await request(passwordMode ? '/api/password' : '/api/profile', {
          method: 'POST',
          headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
          body: new URLSearchParams(data)
        });
        if (!passwordMode) renderProfile(updated);
        dialog.close();
        await refreshSessionStatus();
        setStatus(passwordMode ? 'Password changed successfully.' : 'Profile updated successfully.');
      } catch (error) {
        errorMessage.textContent = error.message;
      } finally {
        save.disabled = false;
      }
    });
    dialog.append(heading, form);
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
  } catch (error) {
    setStatus(error.message, true);
  }
}

const picturePicker = document.querySelector('#profile-picture-file');
picturePicker?.addEventListener('change', async () => {
  const file = picturePicker.files?.[0];
  if (!file) return;
  try {
    if (!['image/png', 'image/jpeg', 'image/gif'].includes(file.type)) {
      throw new Error('Choose a PNG, JPEG or GIF image');
    }
    if (file.size > 2 * 1024 * 1024) throw new Error('Choose a picture of 2 MB or smaller');
    picturePicker.disabled = true;
    setStatus('Uploading profile picture…');
    const user = await request('/api/profile/picture', {
      method: 'POST',
      headers: {'Content-Type': file.type},
      body: file
    });
    renderProfile(user);
    setStatus('Profile picture updated successfully.');
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    picturePicker.disabled = false;
    picturePicker.value = '';
  }
});


async function loadDashboard(user) {
  if (page !== 'dash.html') return;
  const revision = sessionRevision;
  updateDashboardVisibility(user);
  try {
    const data = await request('/api/scrolls', {
      cache: 'no-store', headers: user
        ? {'X-VSAS-User-ID': encodeURIComponent(user.idKey), 'X-VSAS-Username': encodeURIComponent(user.username)} : {}
    });
    if (revision !== sessionRevision || user !== currentUser) return;
    renderDashboard(data.scrolls, user);
  } catch (error) {
    if (revision !== sessionRevision || user !== currentUser) return;
    const library = document.getElementById('dashboard-scrolls');
    if (library) library.textContent = `Unable to load scrolls: ${error.message}`;
    for (const count of document.querySelectorAll('[data-dashboard-count]')) count.textContent = '—';
  }
}

function renderDashboard(scrolls, user) {
  updateDashboardVisibility(user);
  const counts = {
    mine: scrolls.filter(scroll => scroll.owned).length,
    total: scrolls.length,
    downloads: scrolls.reduce((total, scroll) => total + scroll.downloads, 0),
    uploaders: new Set(scrolls.map(scroll => scroll.uploaderId)).size
  };
  for (const count of document.querySelectorAll('[data-dashboard-count]')) {
    const restricted = ['total', 'uploaders'].includes(count.dataset.dashboardCount);
    count.textContent = restricted && user?.type !== 'ADMIN'
      ? '—' : String(counts[count.dataset.dashboardCount]);
  }
  const role = document.querySelector('[data-dashboard-role]');
  if (role) {
    const types = {GENERAL: 'General User', ADMIN: 'Admin', GUEST: 'Guest'};
    role.textContent = `${user ? types[user.type] || user.type : 'Guest'} • Knowledge grows when it is shared.`;
  }
  const library = document.getElementById('dashboard-scrolls');
  if (!library) return;
  library.replaceChildren();
  if (!scrolls.length) {
    const empty = document.createElement('p');
    empty.textContent = 'No scrolls have been uploaded yet.';
    library.append(empty);
    return;
  }
  const recent = [...scrolls].sort((a, b) => b.uploadDate.localeCompare(a.uploadDate));
  for (const scroll of recent.slice(0, 4)) library.append(scrollCard(scroll));
}

function updateDashboardVisibility(user) {
  for (const tile of document.querySelectorAll('[data-dashboard-admin]')) {
    tile.hidden = user?.type !== 'ADMIN';
  }
  if (user?.type !== 'ADMIN') {
    for (const count of document.querySelectorAll('[data-dashboard-count]')) {
      if (['total', 'uploaders'].includes(count.dataset.dashboardCount)) count.textContent = '—';
    }
  }
}
