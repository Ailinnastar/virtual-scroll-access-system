# Virtual Scroll Access System (VSAS)

Java 17 web application for a shared document library, developed as the SOFT2412/COMP9412 team project A2-T13-88. Features include guest browsing, account registration/login, scroll upload/download and owner deletion, favourites, profile management, and administrator tools and activity records.

[Deploy a free demo on Render](https://render.com/deploy?repo=https://github.com/Ailinnastar/virtual-scroll-access-system) | [Team attribution and source snapshot](CONTRIBUTORS.md) | [Hosting guide](docs/HOSTING.md)

The portfolio copy includes source, tests, static UI assets, and build configuration. It excludes existing accounts, uploaded files, course PDFs, private sprint records, and feedback documents. This is a shared university project; the repository is not a sole-authorship claim.

## Run locally

With JDK 17 installed:

```bash
./gradlew run --console=plain
```

Open `http://localhost:8080`. Local development retains the classroom administrator default; do not use that configuration for an internet-facing instance. Online production mode requires a private administrator password.

## Docker with persistent storage

```bash
export VSAS_ADMIN_PASSWORD="$(openssl rand -hex 24)"
docker compose up --build -d
```

Open `http://localhost:4082`. The administrator username defaults to `admin` and its password is the value generated in your shell. Store that value privately. A named Docker volume preserves accounts and scrolls across container restarts. Public hosting needs HTTPS and `VSAS_SECURE_COOKIES=true`; see the hosting guide.

## Free online demonstration

The Render Blueprint selects the free plan, generates a private administrator password, and uses HTTPS-only session cookies. Retrieve the generated administrator password privately from your own Render environment settings.

Free services sleep after inactivity and have ephemeral filesystems. Accounts and uploaded files can disappear on restart or redeployment. Use fictional profile details and harmless demo files. The free deployment has no durable-storage guarantee and shares Render's free-instance allowance with any other free services on your account.

## Verify

```bash
./gradlew --no-daemon build installDist
node --test src/test/js/*.cjs
```

The Docker image builds and tests the Java application. GitHub Actions also runs the JavaScript tests. Some inherited acceptance placeholders are disabled and do not count as passing features; coverage reports live under `build/reports/jacoco/`.

The server has a `/health` endpoint. Saved passwords use PBKDF2 hashes, and accounts are stored with owner-only file permissions. Sessions are in memory and are cleared on restart. This remains a university prototype, without a production security-audit claim.
