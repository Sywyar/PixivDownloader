# Operation CAPTCHA plugin

The official `captcha` plugin provides Turnstile, ALTCHA SHA-256 and ALTCHA PBKDF2 form components. It is optional: standard distributions do not preinstall it, while the full offline bundle includes it. After a component is bound to a compatible form and published, the server enforces its verification requirement.

CAPTCHA raises the cost of automated submissions. It does not replace network DDoS protection, reverse-proxy rate limits or access control. See [Network access and third-party services](/en/network-access) for external connections.

## Choose a verification method

| Method | User interaction | Where verification runs | Requirements |
| --- | --- | --- | --- |
| Turnstile online verification | In Managed mode, Start verification opens Cloudflare's widget, which decides whether further interaction is needed | The browser loads Cloudflare; the server validates the token with Cloudflare | Cloudflare account, Sitekey, Secret key and allowed hostname |
| ALTCHA SHA-256 | Listed as ALTCHA offline verification; the browser computes after a click and displays Verified | The browser solves a SHA-256 proof of work; the local service issues and verifies the challenge | A local signing key generated on first activation; no third-party account |
| ALTCHA PBKDF2 | Listed as ALTCHA PBKDF2 offline verification; the same interaction as SHA-256 | The browser solves a PBKDF2/SHA-256 proof of work; the local service verifies it | The same local signing key as SHA-256; no third-party account |

Neither ALTCHA method asks users to identify images or type characters. Offline means no dependency on a third-party verification service; the browser still connects to your PixivDownloader instance. Computation time depends on the algorithm, parameters and device. The PBKDF2 name alone does not establish better speed or protection. See [ALTCHA proof of work](https://altcha.org/docs/integration/proof-of-work-captcha/).

Usually choose one method per form. **If a form contains several verification components, all are required.** They are not alternatives, and an unavailable Turnstile component does not automatically fall back to ALTCHA.

## Install and bind a form

1. Install and enable Operation CAPTCHA and Page editor through [plugin management and the market](/en/plugin-management).
2. Keep the generated key for offline verification, or configure Turnstile below.
3. Open the [page editor](/en/page-editor), select a compatible page such as Login, and drag a component from the CAPTCHA category into the form. Confirm its Protected operation binding. Resolve an ambiguous binding or placement outside the form before publishing.
4. Publish the page and review its requested resource permissions. Saving a draft alone does not change the live page's verification requirements.
5. Open the live page in a signed-out window. For login, enter the username and password, choose Start verification, then choose Log In after every required verification completes.

Editor and preview buttons do not run real verification. The live page does not submit login before proofs are complete; the server also validates proofs before checking the password and creating a session. Changing the username, password or remember-me option clears proofs. An incorrect password, verification failure or expiry requires fresh verification.

The plugin supports hot loading; refresh the page after normal installation and activation. Updating already loaded Java classes or host code in a development instance requires restarting that instance and refreshing the page.

## Configure offline verification

Both ALTCHA methods use the ALTCHA signing key. First activation generates 32 random bytes, which the host encrypts in the instance's `config/credentials/captcha.properties`, under the configured configuration root if overridden. Reactivation and restart reuse the key. It has no automatic expiry and normally needs no manual changes.

A manual replacement needs at least 32 characters. Saving an empty password field keeps the stored value. Clearing the saved credential makes running offline verification fail closed; reactivating the plugin generates a new key. Invalid existing values, corrupt storage and write failures never fall back to a temporary key.

Workers ship with the plugin and require no external CDN. Computation uses Web Crypto: access the page through HTTPS or a local origin the browser considers secure. Plain HTTP on a LAN IP may lack this API. See [Web Crypto secure-context requirements](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto).

## Obtain and configure Turnstile keys

### Get a Sitekey and Secret key

1. Sign in to the [Cloudflare dashboard](https://dash.cloudflare.com/) and open **Turnstile → Add widget**.
2. Enter a name, such as `PixivDownloader`.
3. Add the hostname visitors use, such as `download.example.com`, under Hostname management.
4. Choose **Managed** to let Cloudflare decide whether user interaction is needed.
5. Choose Create and copy this widget's Sitekey and Secret key. The Sitekey is public; keep the server-side Secret key private. See [Cloudflare's setup guide](https://developers.cloudflare.com/turnstile/get-started/widget-management/dashboard/).

Your website does not have to use Cloudflare's proxy. The widget and server-side validation still require access to Cloudflare. See the [integration overview](https://developers.cloudflare.com/turnstile/get-started/).

### Enter the settings

Open Settings → Operation CAPTCHA:

| Setting | Value |
| --- | --- |
| Turnstile sitekey | The Sitekey from the widget |
| Turnstile secret key | The Secret key from the same widget |
| Expected Turnstile hostname | The hostname visitors use, such as `download.example.com` |
| Allow official test configuration | Off when using keys you created |

Do not include a scheme, port or path: `https://download.example.com:6999/login.html` is not a hostname. Configure both Cloudflare's allowed hostnames and the expected hostname in the plugin. The plugin requires an exact match with the validation response. See [hostname requirements](https://developers.cloudflare.com/turnstile/additional-configuration/hostname-management/).

Save, place Turnstile in the form, and publish. Changing the sitekey, expected hostname or test mode requires republishing existing pages too. Rotating only the secret key invalidates outstanding challenges and requires fresh verification. Ordinary settings use `config/plugins/captcha.properties`; secrets use the encrypted credential file and never appear in page documents.

### Use your own keys on localhost

Create a separate development widget and match the browser address:

| Browser address | Cloudflare allowed hostname | Plugin expected hostname |
| --- | --- | --- |
| `http://localhost:6999` | `localhost` | `localhost` |
| `http://127.0.0.1:6999` | `127.0.0.1` | `127.0.0.1` |
| `https://download.example.com` | `download.example.com` | `download.example.com` |

Changing the port does not change the hostname. Switching between `localhost` and `127.0.0.1` requires updating the settings and republishing. **Running locally does not require Allow official test configuration.** Cloudflare permits local hostnames for development widgets and recommends excluding them from production widgets. See [local testing](https://developers.cloudflare.com/turnstile/troubleshooting/testing/).

### Check the integration with official test keys

To check the component and submission flow, use Cloudflare's public always-pass test values:

| Setting | Test value |
| --- | --- |
| Turnstile sitekey | `1x00000000000000000000AA` |
| Turnstile secret key | `1x0000000000000000000000000000000AA` |
| Expected Turnstile hostname | `example.com`, this plugin's official-test convention |
| Allow official test configuration | On |

Save and republish before testing. Here, `example.com` is used to validate the test response; you do not need to own or visit it, and the page can remain on `localhost`. Test sitekeys need matching test secrets. These values come from [Cloudflare's testing documentation](https://developers.cloudflare.com/turnstile/troubleshooting/testing/) and **provide no real bot protection**. Before production use, supply your own keys and actual hostname, turn test configuration off, and republish.

## Troubleshooting

| Symptom or log | Action |
| --- | --- |
| Component appears in the editor but not on the live page | Confirm publication succeeded, rather than only saving a draft, then refresh |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | Check the key named in the log; never include secrets in logs or issue reports |
| `CAPTCHA_CONFIG_INVALID` | Check the named sitekey, hostname or test-mode field and use a matching key pair |
| `PUBLISHED_POLICY_CHANGED` | Check the settings and republish the affected page |
| `CAPTCHA_REMOTE_UNAVAILABLE` or widget loading failure | Check browser and server connectivity, proxies and blocking rules for Cloudflare |
| `HOSTNAME_MISMATCH` | Compare the browser address, Cloudflare allowed hostnames and plugin expected hostname |
| Policy remains unavailable after adding ALTCHA | Check whether the form also requires an unconfigured Turnstile component; remove it and republish for offline-only use |
| Verification is requested again | Check for input edits, an incorrect password submission, expiry or changed plugin settings |

A message that a required login verification policy is unavailable summarizes the failure. Check the logs for the specific reason and configuration key. Disabling or removing the plugin does not remove published requirements: protected operations remain blocked. To remove a requirement, edit and republish the page.
