# Operation CAPTCHA

The official `captcha` plugin provides six verification components for protected forms such as login: Turnstile, reCAPTCHA v2, ALTCHA SHA-256, ALTCHA PBKDF2, character CAPTCHA (offline) and slider puzzle (offline). It is not preinstalled in ordinary distributions; install it on demand from the plugin market or use the full-offline package. After you drag a component into a compatible form and publish the page, both frontend and backend enforce challenge-proof verification.

CAPTCHA raises the cost of automated submissions. It does not replace network-level DDoS protection, reverse-proxy rate limiting or access control. See [network access and third-party services](/en/network-access) for related external requests.

## Compare verification methods

| Component | User interaction | Verification location | Preparation |
| --- | --- | --- | --- |
| ALTCHA SHA-256 (offline) | Click and wait for the browser to complete SHA-256 proof of work | Challenges are issued and signatures checked locally, without a third-party service | A local signing key is generated automatically on first enablement |
| ALTCHA PBKDF2 (offline) | Click and wait for the browser to complete a PBKDF2/SHA-256 key-derivation proof | Challenges are issued and signatures checked locally, without a third-party service | Shares the local signing key with SHA-256 |
| Character CAPTCHA (offline) | Enter the 5 letters or digits in the image, case-insensitively, and confirm | Hutool generates the image locally; the server checks the answer in memory | No additional configuration |
| Slider puzzle (offline) | Drag the slider, or use arrow keys + Enter, to align the puzzle piece with the gap and confirm | Tianai generates the puzzle locally; the server checks the answer in memory | No additional configuration |
| Turnstile online verification | In Managed mode, click Start verification to load the Cloudflare widget | The browser loads Cloudflare; the server calls Siteverify | Cloudflare account, Sitekey, Secret key and allowed hostnames |
| reCAPTCHA v2 online verification | Tick the checkbox; Google may require an image challenge | The browser loads Google's widget; the server calls Siteverify | Google reCAPTCHA v2 Checkbox public key, secret key and hostname |

!> Usually one component per form is enough. **If a form contains multiple CAPTCHA components, all must be completed before submission.** An unavailable online service does not trigger an automatic fallback to an offline component.

## Install and bind to a form

1. Install and enable Operation CAPTCHA and Page editor in [plugin management](/en/plugin-management).
2. For Turnstile or reCAPTCHA v2, configure and save the keys and hostname described below in desktop Settings → Operation CAPTCHA first. Offline components need no advance configuration.
3. Open the [page editor](/en/page-editor), select the target page such as login, drag the desired component from Add component → CAPTCHA on the left, and confirm that Protected operation on the right is bound to the target form.
4. Click Publish and confirm the required external-resource permissions. Saving a draft alone does not activate CAPTCHA protection on the live page.
5. Open the live login page in a signed-out window, enter the username and password, complete the CAPTCHA, then click Log in.

Changing the username, password or Keep me signed in clears the current proof. Verify again after an incorrect password, failed verification or expired challenge.

## Offline component configuration and behavior

- **ALTCHA (SHA-256 / PBKDF2)**: first enablement generates a random 32-byte signing key, encrypted by the host and saved in `config/credentials/captcha.properties`. It has no expiry and normally needs no changes. A manual replacement must contain at least 32 characters; after clearing credentials, re-enable the plugin to generate a new key. Worker scripts are bundled with the plugin and need no external CDN. Browser computation relies on [Web Crypto `SubtleCrypto`](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto); use `localhost`, `127.0.0.1` or HTTPS, as browsers may disable Web Crypto on ordinary unencrypted LAN HTTP.
- **Character input and slider puzzle**: images are generated locally. Challenges last 120 seconds and are consumed once. Clicking Refresh image resets all verification state for the current form. These methods provide basic anti-automation protection only; the slider has no behavioral-trajectory model. Combine them with request rate limiting. For accessibility, prefer ALTCHA, which does not require recognizing images.

## Configure Cloudflare Turnstile

1. Sign in to the [Cloudflare dashboard](https://dash.cloudflare.com/), open **Turnstile → Add widget**, add the actual access hostname (such as `localhost` or `download.example.com`, without protocol, port or path), select **Managed**, then create the widget and obtain its Sitekey and Secret key.
2. Select Cloudflare Turnstile in desktop Settings → Operation CAPTCHA. Enter the **site public key** (Sitekey), **verification secret** (Secret key) and **expected hostname**. The hostname must exactly match the one used in the browser; update it when switching between `localhost` and `127.0.0.1`. Disable Allow official test configuration and save.
3. Publish the page with its Turnstile binding in the page editor. Changes to the public key, expected hostname or test switch require republishing; rotating only the secret key does not.

**Official test keys (for integration testing of submission only; no anti-automation protection)**: use public key `1x00000000000000000000AA`, secret key `1x0000000000000000000000000000000AA` and expected hostname `example.com`, enable Allow official test configuration, then republish the page. Before production use, replace them with real keys, disable test configuration and republish.

## Configure Google reCAPTCHA v2

1. In the [Google reCAPTCHA admin console](https://www.google.com/recaptcha/admin/create), create a **v2 → "I'm not a robot" Checkbox** site, not v3 or Invisible, and add the actual access hostname (`localhost` for local testing).
2. Select Google reCAPTCHA v2 in desktop Settings → Operation CAPTCHA, enter the **site public key**, **verification secret** and **expected hostname**, disable Allow official reCAPTCHA test configuration, and save.
3. Drag reCAPTCHA v2 online verification into the page editor and publish the page.

**Official test keys (for integration testing only; no real protection)**: use public key `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI` and secret key `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe`. You may leave the expected hostname blank; verification checks `testkey.google.com`. Enable Allow official reCAPTCHA test configuration and republish the page.

## Troubleshooting

| Symptom or log error code | Checks and action |
| --- | --- |
| The editor has a component but the live login page does not | Confirm that you clicked Publish rather than only saving a draft, then refresh the live page |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | Use the key name in the log to check whether a secret is empty or too short; never paste secrets into logs or Issues |
| `CAPTCHA_CONFIG_INVALID` | Check that the public key, expected hostname and test-mode switch match; test keys require the test switch |
| `PUBLISHED_POLICY_CHANGED` | After changing CAPTCHA settings, republish the corresponding page in the editor |
| `CAPTCHA_REMOTE_UNAVAILABLE` or the widget does not load | Check browser and backend network access and proxy settings for Cloudflare / Google |
| `HOSTNAME_MISMATCH` | Check that the browser address-bar hostname, provider's allowed hostname and plugin's Expected hostname all match |
| A policy is still unavailable after adding ALTCHA | Check whether the form still contains unconfigured Turnstile or reCAPTCHA components; remove unwanted components and republish |

Disabling or uninstalling `captcha` does not bypass a published page's verification requirements; protected operations remain rejected. To remove CAPTCHA, remove the components in the page editor and republish the page.

Login and invitation redemption share the host challenge and submission protocol. Direct API calls must satisfy the same published requirements. Configure only the online schemes you use; unused Turnstile or reCAPTCHA schemes require no test keys. See [Page editor](/en/page-editor) for configuring new form actions.
