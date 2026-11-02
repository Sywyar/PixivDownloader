# First-time setup

First-time setup creates an administrator account for PixivDownloader itself and configures the HTTP proxy that the backend uses to access external services. Save your Pixiv login cookie separately after opening the download page.

After [installation and startup](/en/installation), choose the initialization method for your environment:

| Environment | Initialization entry | After initialization |
| --- | --- | --- |
| Local desktop | Desktop onboarding, or `http://localhost:6999/setup.html` in a local browser | Open the download page and sign in |
| Headless server (`--no-gui`) | Run `--setup` first | Then start with `--no-gui` |
| Docker | Run `docker compose run --rm app --setup` first | Then run `docker compose up -d` |

## Initialize from the desktop or a local browser

1. Start the app and wait for the backend to become ready. If a required plugin is missing or the database is unavailable, follow [plugin management](/en/plugin-management) to repair it first. Reinitializing the account will not fix a plugin or database failure.
2. Set an administrator username and password. Passwords require at least 8 characters; 12 or more are recommended. A password of 8–11 characters requires a second confirmation.
3. Enter the HTTP proxy host and port for your network, such as `127.0.0.1` and `7890` for a local proxy. You can disable the proxy if the target services are directly reachable.
4. Save, then open `http://localhost:6999/pixiv-batch.html` and sign in with the administrator account you just created.

Desktop and browser setup always use solo mode (`solo`). `setup.html` allows local loopback access only; use the CLI below for a remote server or container. Save later changes to ordinary configuration on the desktop Settings page. See [network access and third-party services](/en/network-access) for the scope of proxy settings and each plugin's network route.

## Initialize from the command line (server / headless mode)

Open the fully extracted distribution directory and make sure no instance is running against the same data directory. Replace `APP.jar` below with the actual application JAR filename:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --mode=solo
```

Follow the prompts for the administrator username, password and proxy settings. Account state is written to `state/setup_config.json`, and proxy settings to `config/config.yaml`. Once setup is complete, start the headless service:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

Automation scripts can supply all parameters at once. Replace the password and proxy with actual values; command-line arguments appear in shell history and process listings:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

Use `--proxy-enabled=false` if no proxy is needed. The CLI still supports `--mode=solo` and `--mode=multi`; for read-only sharing with guests, prefer [guest invitations in the advanced reference](/en/usage-guide#guest-invitations).

## Initialize Docker

Prepare the image and mounted directories using the [Docker installation instructions](/en/installation#method-3-docker-deployment), then run these commands in the directory containing `docker-compose.yml`:

```bash
docker compose run --rm app --setup --mode=solo
docker compose up -d
docker compose logs -f app
```

Inside a container, `127.0.0.1` refers to the container itself. To use the host's proxy, enter `host.docker.internal` as the proxy host and make sure the host proxy accepts that connection. Starting the headless service before completing `--setup` exits with code `78`.

## Confirm the result and change the password

Sign in to the download page as the administrator, confirm that the workbench loads normally, then test a download using [your first download](/en/first-download). If saving or startup fails, retain the error and check `log/latest.log`, directory write permissions, port usage and database status. Do not troubleshoot by repeatedly deleting initialization files.

After stopping the running instance, you can change or reset the administrator password from the distribution directory:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --change-password
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

`--change-password` verifies the current password; `--reset-password` performs a forced local reset if you have forgotten it. Success invalidates all existing login sessions without affecting work files or the database. Only if you actually need to rebuild initialization state should you stop the app, back up the data, move `state/setup_config.json` aside and run `--setup` again.
