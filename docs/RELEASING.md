# Releasing

The release pipeline runs inside GitHub Actions. There is no local signing or release step — everything happens on push of a `v*` tag.

## One-time setup — signing key

Generate a keystore once, on a machine you trust. Keep the raw `.jks` file offline (encrypted USB drive, password manager attachment, anywhere that is *not* the repo).

```bash
keytool -genkeypair -v \
    -keystore verbalogix-companion.jks \
    -alias verbalogix \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000
```

Encode it for transit:

```bash
openssl base64 -A < verbalogix-companion.jks > keystore.b64
```

## GitHub repository secrets

Add the following under **Settings → Secrets and variables → Actions → Repository secrets**:

| Secret | Value |
|---|---|
| `SIGNING_KEY_BASE64` | Contents of `keystore.b64` |
| `SIGNING_KEY_ALIAS` | The alias you passed to `keytool` (e.g. `verbalogix`) |
| `SIGNING_KEYSTORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_PASSWORD` | Key password (often the same as keystore password) |

The workflow fails early with a clear error if any of these are missing.

## Cutting a release

```bash
git tag v0.1.0
git push origin v0.1.0
```

The `release` workflow runs, builds a signed release APK, attaches it + a SHA-256 checksum to a new GitHub Release, and removes the decoded keystore from the CI workspace afterwards. The release page becomes the install source.

## Incident response — keystore compromise

If the keystore file or any of its passwords leak:

1. **Rotate the repository secrets immediately** — old ones become unusable for signing.
2. Generate a fresh keystore with a new alias.
3. Push a new release under a new version. Because the signature is different, existing installs will not auto-update; users must uninstall + reinstall.
4. Document the incident in `CHANGELOG.md` and in any F-Droid / Google Developer Verification channels where the old key was registered.

## Do not use Google Play App Signing

Google Play App Signing transfers key custody to Google. That breaks F-Droid's reproducible-build verification path (F-Droid would not be able to verify our APKs against a key Google holds). We manage the key ourselves.

## Reproducible builds (planned)

F-Droid's submission path ships *our* APK signature if the build is reproducible — anyone can rebuild from source and get a byte-identical output, which F-Droid verifies. Requirements:

- Pin AGP + Kotlin versions (already done in `libs.versions.toml`).
- Remove build-time timestamps from any `BuildConfig` fields.
- Avoid unpinned transitive versions.

Not implemented yet; tracked for v0.2.0.
