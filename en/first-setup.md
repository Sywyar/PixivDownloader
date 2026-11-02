# First-time setup

Setup creates the PixivDownloader administrator account and configures the HTTP proxy used by the backend. This account signs you into this app. You save your Pixiv cookie separately on the download page.

After [installation](/en/installation), choose the entry that matches how you run the app:

| Environment | Initialize with | Then |
| --- | --- | --- |
| Local desktop | The desktop setup guide | Open the download page and sign in |
| Headless server or `--no-gui` | Run `--setup` first | Start with `--no-gui` |
| Docker | Run `docker compose run --rm app --setup` first | Run `docker compose up -d` |

## Set up on the desktop

1. Start the app and wait for the backend. If a required plugin is missing or the database is unavailable, follow [plugin management](/en/plugin-management) to restore it. Creating another account does not fix either condition.
2. Enter an administrator username and password in the guide. Passwords require at least 8 characters. The app recommends 12 or more and asks for confirmation for a shorter password.
3. Enter your HTTP proxy host and port. For example, use `127.0.0.1` and `7890` only if your local proxy actually listens there. Disable the proxy if you can reach the required services directly.
4. Save, open `http://localhost:6999/pixiv-batch.html`, and sign in with the account you just created.

Desktop and browser setup use solo mode (`solo`); they do not offer a multi-mode selector. Tabs and guide steps differ between desktop interfaces, so follow the prompts in your window.

The local browser page at `http://localhost:6999/setup.html` also accepts account and proxy settings, then redirects to login. It accepts only local access. A headless launch exits before initialization, so use the CLI below first.

The proxy applies to requests that use the host or task proxy route. Browser requests, SMTP, database connections and explicitly direct requests have separate routes. See [network access and third-party services](/en/network-access). For later changes, save through desktop settings and follow its apply or restart instruction.

## Set up from the command line

Enter the fully extracted distribution directory and stop any running instance using it. These commands work in PowerShell or Bash. Replace `APP.jar` with the actual application JAR filename and retain the adjacent `plugins/` directory.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --mode=solo
```

Follow the username, password and proxy prompts. The minimum password length is 8 characters; below the recommended 12 characters, the command asks you to reconsider or confirm. The CLI still accepts `--mode=solo` or `--mode=multi`; this guide uses `solo`. To invite other people to browse, see the [advanced usage guide](/en/usage-guide).

Successful setup writes the account state to `state/setup_config.json` and submitted proxy settings to `config/config.yaml`. Start the service:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

For automated input, provide all parameters explicitly:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

Replace the password, host and port with your values. Use `--proxy-enabled=false` when no proxy is needed. A command-line password may appear in shell history and process listings; prefer interactive input for routine setup.

## Set up in Docker

Prepare the image and writable mounts using the [Docker installation instructions](/en/installation). From the directory containing `docker-compose.yml`, run:

```bash
docker compose run --rm app --setup --mode=solo
docker compose up -d
docker compose logs -f app
```

Inside a container, `127.0.0.1` refers to that container. To use a host proxy, enter `host.docker.internal` as configured in compose and the actual proxy port. The host proxy must accept the connection.

A headless service started before setup exits with code `78`. A container with an automatic restart policy may restart repeatedly. Stop it, complete `--setup`, then start it again.

## Confirm setup succeeded

Sign in to the download page and check that the workbench opens. Continue with [first download](/en/first-download). Signing in proves that your local account works; Pixiv connectivity and cookie validity are separate checks.

If saving or startup fails, retain the error and matching logs under `log/`. Check for another running instance, an unwritable configuration directory, missing required plugins or an unavailable database. Repeatedly deleting setup state does not diagnose these problems.

## Change or reset the password

Stop the instance and run one of these commands from its distribution directory. Changing requires the old password; resetting locally does not:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --change-password
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

Choose one command. Success invalidates existing sessions. Restart and sign in with the new password. Neither command deletes works or the database.

If you need to recreate setup state, stop the app, back up `state/setup_config.json`, move that file aside, and initialize again. This resets the account, run mode and login state. Prefer `--reset-password` when password recovery is all you need.
