# Vulcraft

A [Vulkan](https://www.vulkan.org/) renderer for **Minecraft 1.12.2** running on the
[Cleanroom](https://cleanroommc.com/) mod loader. Vulcraft is a fork of
[xCollateral/VulkanMod](https://github.com/xCollateral/VulkanMod) — an OpenGL → Vulkan
translation layer — re-targeted at 1.12.2 Cleanroom, with multi-draw batching and a
GPU-pass profiling harness built in.

> It is built on the Cleanroom mod-development template, so the sections below also
> apply to that template.

---

### ⚠️ WARNING: Custom Unimined Fork Required

This project does **not** build with upstream Unimined. It depends on a custom fork:

- Fork: [kappa-maintainer/Unimined](https://github.com/kappa-maintainer/Unimined)
  (declared as `xyz.wagyourtail.unimined` version `1.4.36-kappa` in `build.gradle`)
- Original: [unimined/Unimined](https://github.com/unimined/Unimined)

If you hit impossible field names or Scala-compiler errors from the remapper, report
them on the fork's issue tracker.

> LWJGL Vulkan/shaderc/VMA modules are **not** shipped by Cleanroom, so the mod carries
> those natives itself as `ContainedDeps` (see `lwjgl_natives` in `gradle.properties`).
> A Windows-only bundle is used during local development; a public release should set
> `lwjgl_natives` to cover all platforms.

---

## Building & Running

From the project root (uses the Gradle wrapper):

```bash
# Compile the mod
./gradlew compileJava

# Build the distributable jar (remapped)
./gradlew build

# Run the Minecraft client with the mod loaded (dev)
./gradlew runClient
```

The dev client writes its config, logs, and screenshots under `run/client/`
(`run/` is git-ignored — it is generated, not part of the source tree).

### Useful Gradle properties

Pass these with `-P` (the Gradle daemon is long-lived, so a shell export will **not**
reach a subsequent build):

| Property | Effect |
|---------|--------|
| `-Pvulkanmod.benchSize=WxH` | Launches the client at a fixed window size, used to measure GPU passes at two resolutions and tell fill/overdraw-bound work from vertex/draw-bound work. |

Other build toggles live in `gradle.properties` (`generate_sources_jar`,
`enable_shadow`, `use_access_transformer`, `is_coremod`, `lwjgl_natives`, etc.).

---

## Features

- **Vulkan backend** — translates Minecraft's OpenGL calls to Vulkan draw calls at
  runtime via Mixins, instead of going through the driver's OpenGL path.
- **Multi-draw** — chunk/geometry batches are coalesced into multi-draw calls to cut
  CPU overhead per frame.
- **Coremod** — `com.yuhan123.vulkanmod.ExampleLoadingPlugin` (`is_coremod = true`)
  hooks the early launch pipeline; an access transformer (`vulcraft_at.cfg`) opens up
  Minecraft internals.
- **Profiling / benchmark harness** — a lightweight per-frame GPU-pass timer that
  prints `[VKPROF]` lines to the log. Controlled by switches in the mod's config
  file (`vulcraft.properties`, which also reads `config/vulkanmod.properties`):

  | Key | Default | Meaning |
  |-----|---------|---------|
  | `profiling` | `0` | Enable per-frame `[VKPROF]` GPU-pass timing (off by default — it is verbose). |
  | `AUTOJOIN` | `0` | Skip the menu and drop straight into a world. |
  | `AUTOQUIT` | `0` | Quit the client after a short delay (for automated benchmarks). |
  | `SHOT` | — | Capture a screenshot N seconds after launch. |
  | `SHOT_TAG` | — | Tag applied to the screenshot filename. |
  | `PIN_CAMERA` | `0` | Freeze the camera so repeated runs are comparable. |

  A template with the current values is written next to the config file on first load,
  so the switches are discoverable.

---

## Project Layout

```
vulcraft/
├── build.gradle / settings.gradle / gradle.properties   # build + mod metadata
├── src/main/
│   ├── java/com/yuhan123/vulkanmod/                     # renderer, mixins, config
│   ├── java/net/minecraft/                              # small Forge/vanilla patches
│   ├── java-templates/                                  # Blossom-generated sources
│   └── resources/                                       # mixin json, shaders, assets
└── run/                                                 # generated dev workspace (git-ignored)
```

- **`src/main/java`** — ~170 Java files including **28 Mixin** classes that intercept
  GL/renderer calls and redirect them to the Vulkan backend.
- **`resources/assets/vulkanmod/shaders`** — the Vulkan shaders used to emulate the
  fixed-function GL pipeline.

---

## Compatibility & Known Issues

This is an early, in-progress renderer. Expect issues:

- **Windows-only** at the moment; other platforms are untested and may crash.
- Many rendering paths (transparency, fog, clouds, the main-menu panorama) are still
  being brought up and may show artifacts.
- The custom Unimined fork can surface remapper/field-name errors that do not occur
  with upstream.

---

### Credits

Thanks to the people and projects this is built on:

- [@xCollateral](https://github.com/xCollateral) — for the original VulkanMod source.
- [@CleanroomMC](https://cleanroommc.com/) — for the mod-development template this is based on.
- [@Karnatour](https://github.com/Karnatour) — for fixing the shadow plugin.
- [@ghostflyby](https://github.com/ghostflyby) — for the Kotlin branch.
- [@kappa-maintainer](https://github.com/kappa-maintainer) — for the custom Unimined fork.
