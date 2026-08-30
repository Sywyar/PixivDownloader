# Minimal feature plugin template

This directory is a standalone Maven project for a thin external PF4J plugin. It demonstrates a root `plugin.properties`, a PF4J entry point, `PixivPluginProvider`, `PixivFeaturePlugin`, and route/static/i18n contributions that can be admitted through an isolated plugin worker.

Build with `mvn clean verify`. PixivDownloader supplies Plugin API and PF4J from the parent classloader, so those dependencies remain `provided` and must not be copied into the output JAR.

Replace these values consistently before using the template:

| Template value | Replace with |
|---|---|
| `example-minimal-plugin` | Your Maven artifact id |
| `example-minimal` | Your globally unique lowercase plugin id, URL prefix, and i18n namespace |
| `com.example.pixivdownload.minimal` | Your Java package, including matching source directories |
| `ExampleMinimal*` | Your Java class prefix |
| `0.1.0` | Your artifact and `plugin.version` value |
| `plugin.requires=1.0` | The compatible PixivDownloader SDK major/minor requirement |
| `plugin.provider=Example Developer` | Your provider name |

After replacement, update every formal-locale i18n bundle and run `mvn clean verify` again. Keep feature id, routes, static paths, namespace, and tests aligned. Do not add dependencies on the host app/core/runtime, signature internals, installer code, official-plugin services, or root-context component scanning.

The verified descriptor declares `isolated-process`, `hot-reload`, and no `pixiv.configuration-classes`. Keep it declarative: configuration classes, controllers, queue behavior, schedules, and other in-process callbacks are not supported by this execution mode. If a plugin needs private persistence or behavioral capabilities, use only an execution mode and host capability surface that explicitly supports them; do not relabel unsupported code as isolated.
