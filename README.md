# Keycloak TOTP API Extension

> **Fork notice**: This repository is a fork of the original [medihause/keycloak-totp-api](https://github.com/medihause/keycloak-totp-api) project. Updates made in 2026 and beyond are mostly produced with Claude-assisted code.

This Keycloak extension enables generating, registering, and verifying TOTP (Time-Based One-Time Password) credentials via API. It provides a set of endpoints to manage TOTP credentials for users programmatically.

## Features

- Generate TOTP secrets and QR codes
- Register TOTP credentials for users
- Verify TOTP codes

## Compatibility

| Keycloak runtime | Status     | How to build                                                            |
|------------------|------------|-------------------------------------------------------------------------|
| 26.x (26.0–26.6) | Supported  | Default — `./gradlew shadowJar` (SPI pinned to 26.0.6, ABI-compatible)  |
| 25.x             | Unverified | `./gradlew shadowJar -PkeycloakVersion=25.0.6` (may need source tweaks) |
| ≤ 24.x           | Unsupported| Jakarta/SPI breaking changes, not retargeted                            |
| 27.x+            | Unverified | Re-validate SPI usages before bumping                                   |

Requires **JDK 21+** at runtime, Gradle **9.1+** to build.

The targeted Keycloak SPI version lives in [`gradle.properties`](./gradle.properties) (`keycloakVersion`). Override per-build with `-PkeycloakVersion=X.Y.Z` — the `version` of the produced jar tracks the major (`1.2.0-kcXX`).

## Building the Project

This project uses Gradle for building. To build the project, follow these steps:

1. Clone this repository:
   ```
   git clone https://github.com/medihause/keycloak-totp-api.git
   cd keycloak-totp-api
   ```
2. Build the project using the `shadowJar` task:
   ```
   ./gradlew shadowJar
   ```

This will create a JAR file in the `build/libs` directory.

## Installation

### Downloading the Extension

1. Go to the [Releases](https://github.com/medihause/keycloak-totp-api/releases) page of this repository.
2. Download the latest release, making sure to choose the JAR file with the 'all' suffix (e.g., `keycloak-totp-api-1.0.0-all.jar`), as it includes all necessary dependencies.

### Installing the Extension

#### Standalone (without container)

1. Copy the downloaded JAR file to the `providers` folder in your Keycloak installation directory.
2. Run the following command to build Keycloak with the new extension:

   ```bash
   ${KEYCLOAK_HOME}/bin/kc.sh build
   ```

#### Docker

When using Docker, you need to make the extension available to the Keycloak container. You can do this by:

1. Mounting the JAR file into the container:
   
   Add this volume mount to your Docker run command or docker-compose file:
   ```
   -v /path/to/keycloak-totp-api-1.0.0-all.jar:/opt/keycloak/providers/keycloak-totp-api-1.0.0-all.jar
   ```

   OR

2. Copying the JAR file into a custom Docker image:
   
   If you're building a custom Keycloak image, add this line to your Dockerfile:
   ```
   COPY keycloak-totp-api-1.0.0-all.jar /opt/keycloak/providers/
   ```

After adding the extension, make sure to build the Keycloak image if you're using a custom Dockerfile.

## Local development

A `compose.yaml` is provided to run Keycloak with the freshly built `.jar` mounted as a provider, a seeded realm, and a JVM debug socket exposed.

### 1. Build the extension

```bash
./gradlew shadowJar
```

The compose file mounts `build/libs/keycloak-totp-api.jar` into the container. Rebuild + `docker compose restart keycloak` reloads the extension.

### 2. Start Keycloak

```bash
docker compose up
```

What you get:

| Endpoint                  | URL                                              |
|---------------------------|--------------------------------------------------|
| Admin console             | http://localhost:8080 (`admin` / `admin`)        |
| Imported test realm       | `totp-test` (seeded from `dev/realm/`)           |
| JDWP debug socket         | `localhost:8787` (`suspend=n`)                   |

The seeded realm contains:

- realm role `manage-totp`
- service-account client `totp-api-admin` (`client_credentials`, secret `totp-api-admin-secret`) granted the `manage-totp` role
- a test user `alice` (password `alice`) with a stable UUID `11111111-1111-1111-1111-111111111111`

### 3. Attach a debugger to the running JVM

The container starts Keycloak with `-agentlib:jdwp=...,address=*:8787`, so any JDWP client can attach without restarting Keycloak.

- **IntelliJ IDEA**: `Run → Edit Configurations → + → Remote JVM Debug`, host `localhost`, port `8787`, module classpath = this project. Set breakpoints in the Kotlin sources under `src/main/kotlin/...` and hit one of the API endpoints to trigger them.
- **VS Code**: install the "Debugger for Java" extension and add a launch entry with `"type": "java", "request": "attach", "hostName": "localhost", "port": 8787`.

Because the .jar is rebuilt as `shadowJar`, source line numbers are preserved — breakpoints map directly back to the Kotlin files.

### 4. Exercise the API with Bruno

A [Bruno](https://www.usebruno.com/) collection lives under `dev/bruno/`. Open Bruno, "Open Collection", point it at `dev/bruno/`, then select the `Local` environment.

Recommended run order:

1. **01 - Get service account token** — performs `client_credentials` against `totp-test`, stashes `access_token` in the env.
2. **02 - Generate TOTP** — calls `/generate`, stashes `encoded_secret`. Scan the returned QR code (it is base64-encoded in `qrCode`) or feed `encoded_secret` to `oathtool --totp -b "$encoded_secret"` to obtain a current code.
3. **03 - Register TOTP** — replace `initialCode` with the code from step 2 and run within 5 minutes (TTL of the pending secret).
4. **04 - Verify TOTP** — replace `code` with a fresh code from the same authenticator.

## API Endpoints

### Generate TOTP Secret

Generates a new TOTP secret and QR code for a user.

- **Method**: GET
- **URL**: `{{BASE_URL}}/realms/{{REALM}}/totp-api/{{USER_ID}}/generate`
- **Response**:
  ```json
  {
    "encodedSecret": "OFIWESBQGBLFG432HB5G6TTLIVIEGU2O",
    "qrCode": "iVBO...."
  }
  ```
  The `qrCode` is a base64-encoded image.

### Register TOTP Credential

Registers a TOTP credential for a user. **The `encodedSecret` must match a secret previously issued by `/generate` for the same user within the last 5 minutes.** Arbitrary secrets are rejected to prevent an authorized caller from injecting a pre-known seed.

- **Method**: POST
- **URL**: `{{BASE_URL}}/realms/{{REALM}}/totp-api/{{USER_ID}}/register`
- **Request Body**:
  ```json
  {
    "deviceName": "DeviceOne",
    "encodedSecret": "OFIWESBQGBLFG432HB5G6TTLIVIEGU2O",
    "initialCode": "128356",
    "overwrite": true
  }
  ```
  Set `overwrite` to `true` to replace an existing TOTP credential.
  Constraints:
  - `deviceName`: 1–128 chars, `[\w.\- ]` only
  - `initialCode`: digits-only, length must match the realm OTP policy `digits` setting
- **Response**: `201 Created`
  ```json
  {
    "message": "TOTP credential registered"
  }
  ```
- **Errors**:
  - `400` invalid input or no matching pending secret (call `/generate` first)
  - `409` credential already exists and `overwrite=false`

### Verify TOTP Code

Verifies a TOTP code for a user. The endpoint is wired to Keycloak's `BruteForceProtector`: repeated failures will temporarily lock the target user (per realm policy).

- **Method**: POST
- **URL**: `{{BASE_URL}}/realms/{{REALM}}/totp-api/{{USER_ID}}/verify`
- **Request Body**:
  ```json
  {
    "deviceName": "DeviceOne",
    "code": "866359"
  }
  ```
- **Response**:
  ```json
  {
    "message": "TOTP code is valid"
  }
  ```
- **Errors**:
  - `401` invalid TOTP code
  - `404` no matching credential
  - `429` user temporarily locked by brute-force protection

## Authentication

All API requests must be authenticated. The requester must:

1. Be authenticated and provide a valid bearer token.
2. Be a service account.
3. Have the `manage-totp` realm role.

## Auditing

All operations emit Keycloak events (`UPDATE_TOTP` / `LOGIN` with `device` detail), surfacing through any configured event listener (jboss-logging, email, custom SPI).
