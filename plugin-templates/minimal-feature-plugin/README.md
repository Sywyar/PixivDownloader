# Minimal feature plugin template

This directory is a standalone Maven project for a thin external PF4J plugin. It demonstrates a root `plugin.properties`, a PF4J entry point, `PixivPluginProvider`, `PixivFeaturePlugin`, explicit Spring child-context configuration, an admin controller, route/static/i18n contributions, and one plugin-owned schema contribution.

Build with `mvn clean verify`. PixivDownloader supplies Plugin API, PF4J, and Spring from the parent classloader, so those dependencies remain `provided` and must not be copied into the output JAR.

Replace these values consistently before using the template:

| Template value | Replace with |
|---|---|
| `example-minimal-plugin` | Your Maven artifact id |
| `example-minimal` | Your globally unique lowercase plugin id, URL prefix, and i18n namespace |
| `example_minimal` | Your lowercase SQL-name prefix |
| `com.example.pixivdownload.minimal` | Your Java package, including matching source directories |
| `ExampleMinimal*` | Your Java class prefix |
| `0.1.0` | Your artifact and `plugin.version` value |
| `plugin.requires=1.0` | The compatible Plugin API major/minor requirement |
| `plugin.provider=Example Developer` | Your provider name |

After replacement, update both i18n bundles and run `mvn clean verify` again. Keep feature id, routes, static paths, namespace, schema owner, and tests aligned. Do not add dependencies on the host app/core/runtime, signature internals, installer code, official-plugin services, or root-context component scanning.
