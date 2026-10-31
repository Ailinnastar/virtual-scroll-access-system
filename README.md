# Virtual Scroll Access System (VSAS)

Java 17 web application for a shared document library, developed as the SOFT2412/COMP9412 team project A2-T13-88. Features include guest browsing, account registration/login, scroll upload/download and owner deletion, favourites, profile management, and administrator tools and activity records.

[Open VSAS](https://ailinna-vsas-demo.onrender.com/) | [Deploy your own demo](https://render.com/deploy?repo=https://github.com/Ailinnastar/virtual-scroll-access-system) | [Team and contributions](CONTRIBUTORS.md) | [Hosting guide](docs/HOSTING.md)

## Project highlights

- **4-person team:** a shared university software project with documented individual contributions.
- **61 JavaScript tests:** covering session-aware navigation, search, favourites, profiles, account recovery, and administrator views.
- **Java 17 application:** Gradle builds, JUnit tests, coverage reports, and a Docker deployment workflow.
- **Document-sharing workflows:** browse, upload, download, save favourites, and manage your own documents.
- **Administration and activity records:** account tools, download statistics, and operation history.

### My contribution

Ailinna's recorded responsibilities include document upload, browsing, downloading and owner deletion; administrator features and statistics; activity logging; and Scrum coordination. See [team attribution](CONTRIBUTORS.md) for the shared project's contributors and source history.

## Run locally

With JDK 17 installed:

```bash
./gradlew run --console=plain
```

Open `http://localhost:8080`. The [hosting guide](docs/HOSTING.md) explains local administrator setup and private credentials for public deployment.

## Docker with persistent storage

```bash
export VSAS_ADMIN_PASSWORD="$(openssl rand -hex 24)"
docker compose up --build -d
```

Open `http://localhost:4082`. The administrator username defaults to `admin` and its password is the value generated in your shell. Store that value privately. A named Docker volume preserves accounts and scrolls across container restarts. Public hosting needs HTTPS and `VSAS_SECURE_COOKIES=true`; see the hosting guide.

## Free online demonstration

The Render Blueprint selects the free plan, generates a private administrator password, and uses HTTPS-only session cookies. Retrieve the generated administrator password privately from your own Render environment settings.

Use fictional profile details and harmless demonstration files when exploring the public app. The [hosting guide](docs/HOSTING.md) covers the free demo's storage behaviour and persistent Docker hosting.

## Verify

```bash
./gradlew --no-daemon build installDist
node --test src/test/js/*.cjs
```

The Docker image builds and tests the Java application. GitHub Actions also runs the 61 JavaScript tests. Coverage reports live under `build/reports/jacoco/`.

The server has a `/health` endpoint. Saved passwords use PBKDF2 hashes, accounts use owner-only file permissions, and public deployment supports HTTPS-only session cookies. See the [hosting guide](docs/HOSTING.md) for operational details.
