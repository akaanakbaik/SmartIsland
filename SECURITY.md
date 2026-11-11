# Security Policy

## Supported Versions

Report issues against the current `main` branch or the latest release, and include the exact commit or APK version. Older releases have no separately documented maintenance guarantee. This policy describes reporting scope; it does not promise fixes or release timelines on behalf of upstream maintainers.

| Version | Supported |
| --- | --- |
| Latest release | Current reporting scope |
| `main` | Current reporting scope |
| Older releases | Include reproduction on a current version when possible |

## Reporting A Vulnerability

Please do not publish exploit details, private notification content, or sensitive logs in a public issue.

Preferred reporting path:

1. Use GitHub private vulnerability reporting for this repository if it is enabled.
2. If private reporting is unavailable, open a minimal public issue asking for a private security contact. Do not include technical exploit details in that issue.

Helpful details to include privately:

- Affected commit, release, or build
- Android version and device model
- Reproduction steps
- Expected and actual impact
- Logs with personal notification content removed
- Any suggested fix or mitigation

## Scope

Relevant reports include issues related to notification data exposure, overlay abuse, intent handling, exported components, permission handling, local data storage, or build/release integrity.

Out of scope:

- Vulnerabilities requiring a rooted or already-compromised device
- Social engineering against maintainers or users
- Denial-of-service reports without a realistic security impact
- Reports based only on outdated dependencies without a practical exploit path
