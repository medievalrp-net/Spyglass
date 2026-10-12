# Developer API

Other plugins can record their own events into Spyglass, search the log, add search keys, change how results look and react to new records. The API is licensed Apache-2.0, so any plugin can use it.

## Add the dependency

The API is on Maven Central as `net.medievalrp:spyglass-api`. Use the version matching the Spyglass you build against, and mark it `compileOnly` (Gradle) or `provided` (Maven). The Spyglass plugin supplies it at runtime, and bundling it causes conflicts.

::: code-group

```kotlin [Gradle (Kotlin)]
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.medievalrp:spyglass-api:1.1.1")
}
```

```groovy [Gradle (Groovy)]
dependencies {
    compileOnly 'net.medievalrp:spyglass-api:1.1.1'
}
```

```xml [Maven]
<dependency>
    <groupId>net.medievalrp</groupId>
    <artifactId>spyglass-api</artifactId>
    <version>1.1.1</version>
    <scope>provided</scope>
</dependency>
```

:::

In your `plugin.yml`, add `softdepend: [Spyglass]` (or `depend` if your plugin needs it).

## Get the API

```java
SpyglassApi sg = Bukkit.getServicesManager().load(SpyglassApi.class);
if (sg == null) {
    getLogger().warning("Spyglass not installed; logging integration off.");
    return;
}
```

`load()` returns `null` when Spyglass isn't installed, so always check.

## Full guide

The complete API guide covers recording events, block snapshots, queries, custom search keys and flags, custom display, commit listeners, custom rollback effects and threading rules:

**[API.md on GitHub](https://github.com/medievalrp-net/Spyglass/blob/main/API.md)**
