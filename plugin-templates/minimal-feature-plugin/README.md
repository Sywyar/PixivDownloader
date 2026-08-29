# Minimal feature plugin template

This directory is a standalone Maven project for a thin external PF4J plugin. It demonstrates a root `plugin.properties`, a PF4J entry point, `PixivPluginProvider`, `PixivFeaturePlugin`, explicit Spring child-context configuration, an admin controller, and route/static/i18n contributions.

Build with `mvn clean verify`. PixivDownloader supplies Plugin API, PF4J, and Spring from the parent classloader, so those dependencies remain `provided` and must not be copied into the output JAR.

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

After replacement, update both i18n bundles and run `mvn clean verify` again. Keep feature id, routes, static paths, namespace, and tests aligned. Use the owner-scoped `PluginDataSource` when the plugin needs private persistence; never contribute tables to or connect directly to the host database. Do not add dependencies on the host app/core/runtime, signature internals, installer code, official-plugin services, or root-context component scanning.

The verified `pixiv.kind` and `pixiv.configuration-classes` entries in `plugin.properties` are authoritative at runtime. Keep the compatibility `configurationClasses()` result aligned with the descriptor for older hosts and SDK tooling.
