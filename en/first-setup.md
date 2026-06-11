# First-Time Setup

?> This step is only required on the first startup. Once configured, subsequent launches go straight to the main interface.

## Setup Entry Points at a Glance

| Launch Method | Setup Entry Point |
|--------------|-------------------|
| Desktop GUI (default) | GUI "Home" wizard |
| Local browser + `--no-gui` | Auto-opens `http://localhost:6999/setup.html` |
| Server / Docker (headless) | CLI `--setup` command |

---

## Method 1: GUI Wizard (Recommended for Desktop Users)

After installing and launching PixivDownloader, the GUI main window stays on the "Home" tab with a 7-step wizard. Follow the prompts.

### Step 1: Wait for Service Readiness

The first screen shows the backend startup status (real-time breathing indicator).

Wait until the status changes to "**Running**" (typically 5-15 seconds), then click "Next" to continue.

### Step 2: Set Admin Credentials and Run Mode

On the same page, enter the admin **username** and **password** (password must be at least 6 characters), and select a run mode:

| Mode | Use Case | Characteristics |
|------|----------|----------------|
| **Solo Mode** | Personal use | Login required, download settings stored on server |
| **Multi Mode** | Sharing server with others | Guests don't need login, supports quota and rate limiting |

?> This is for logging into the PixivDownloader web interface, **unrelated to your Pixiv account** — you can name it anything. Individual users should almost always choose "**Solo Mode**."

Click "Complete Setup" to proceed to the next step.

### Step 3: Configure HTTP Proxy

All of the PixivDownloader backend's outbound requests (downloading images, update checks, TTS speech) go through this proxy.

- **With a proxy tool** (Clash, V2Ray, etc.): enter the proxy address. Common config:
  - Host: `127.0.0.1`
  - Port: `7890` (Clash default)
- **Direct Pixiv access** (very rare): disable the proxy

?> Proxy settings support hot reload — you can change them anytime in the GUI "Configuration" page without restarting.

### Steps 4-7: Guided Walkthrough

The wizard guides you in turn to open the browser download page (step 4), browse the gallery (step 5), learn about advanced features (step 6), and finally reach the completion page (step 7). Follow along as needed.

Once you reach the final "Done" page, the GUI "Home" tab is automatically hidden, and subsequent launches go straight to the "Status" page — the wizard won't run again.

?> To re-run the wizard, delete the progress and completion marker files under `state/gui/`.

---

## Method 2: Browser Setup Wizard

For scenarios where you start with `--no-gui` but have a browser on the same machine (e.g., just wanting to save memory by avoiding the GUI window).

1. Start the service: `java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui`
2. The browser automatically opens `http://localhost:6999/setup.html`
3. Follow the page prompts to enter credentials, select mode, configure proxy, and click "Complete Setup"

!> `setup.html` **only accepts local browser** connections. It cannot be opened from a remote browser.

---

## Method 3: CLI Command (Server / Docker)

For server or Docker environments without a graphical interface or desktop browser.

### Interactive Initialization (Recommended)

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

Enter in sequence (password input is hidden):
1. Admin username
2. Password (at least 6 characters)
3. Confirm password
4. Run mode (enter `solo` or `multi`)
5. Whether to enable HTTP proxy (`y`/`n`)
6. Proxy host and port (when choosing `y`)

After initialization, start the service normally:

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
```

### Non-Interactive (Automation Scripts)

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin \
    --password='YourPassword' \
    --mode=solo \
    --proxy-enabled=true \
    --proxy-host=127.0.0.1 \
    --proxy-port=7890
```

!> The password appears in shell history and the process list — only recommended for automation environments.

### Docker Scenario

```bash
# Initialize first
docker compose run --rm app --setup

# Then run persistently
docker compose up -d
```

Do not run `docker compose up` before initialization is complete, otherwise the container will keep restarting with exit code 78 because it detects no initialization.

---

## After Setup

Visit `http://localhost:6999/pixiv-batch.html` to start using.

Next, read [First Download](/en/first-download) to learn the basic download flow.

---

## Follow-up Management

### Change Password

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --change-password
```

### Forgot Password

First stop the running service, then run (the old password is not required):

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

After a successful reset all existing sessions are invalidated. Log in again with the new password.

### Re-initialize

Stop the service, delete `state/setup_config.json`, then re-run `--setup` or go through the GUI wizard.

?> Re-initialization does **not** delete downloaded files or the database — only the account and run mode are reset.
