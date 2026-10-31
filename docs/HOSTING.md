# VSAS hosting

[Open the live portfolio demo](https://ailinna-vsas-demo.onrender.com/)

## Free Render demo

Local development retains the classroom administrator default. Use production mode and a private administrator password for an internet-facing instance.

Use the Deploy link in the README, sign in to Render, and connect only the public portfolio repository. Review the Blueprint before deploying. It selects the free Docker web-service plan and adds no paid disk or database.

Render assigns an HTTPS URL and injects PORT; the Java server reads that port. VSAS_PRODUCTION requires a private administrator password before storage or the server is created. The Blueprint generates that password for you. Read it privately in your Render environment settings when signing in as the administrator; do not put it in GitHub or recruiter materials.

The free instance loses local filesystem changes across restarts/redeployments. Use fictional accounts and demonstration files. Free-instance hours are shared with TaskPilot and other free services on the same Render account. Deploying this code does not purchase a plan, provide permanent storage, or keep a service awake indefinitely.

## Persistent Docker server

Compose uses a named volume at `/app/data`, with the application running as a non-root user. Supply a private VSAS_ADMIN_PASSWORD of at least 16 characters. An HTTPS reverse proxy can forward to the loopback-only service at port 4082; set VSAS_SECURE_COOKIES=true when visitors use HTTPS.

Stop the application before copying its data directory for backup. Retain owner-only permissions on credential files. Multiple replicas must not share the file-backed repositories. Sessions do not survive restarts, even when user accounts and scrolls are retained.

Changing VSAS_ADMIN_PASSWORD does not reset an administrator password already stored in the data directory. Use the application's account-management process for an existing instance. Do not delete production data to reset a password.

## Verification checklist

- `/health` returns `OK`.
- Homepage, login and registration assets load.
- A synthetic user can register and sign in.
- Members can upload and retrieve their own harmless demonstration scroll.
- Guests cannot upload or access administrator actions.
- Persistent hosting retains accounts and files after restart; free demo hosting is disposable.

Platform details: https://render.com/docs/free and https://render.com/docs/web-services.

## Verification scope

VSAS is a university prototype. Automated tests are not a production security audit. Inherited disabled acceptance-test placeholders are excluded from passing-feature claims; the README's 61-test count refers specifically to the JavaScript tests.
