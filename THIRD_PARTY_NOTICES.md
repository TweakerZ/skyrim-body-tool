# Third-party notices

Skyrim Body Tool is licensed under GPL-3.0 (see `LICENSE`). It incorporates or
derives from the following projects.

## BodySlide and Outfit Studio

- Authors: Ousnius, Caliente and contributors
- Source: https://github.com/ousnius/BodySlide-and-Outfit-Studio
- Licence: GNU General Public License v3.0

The native build core in `app/src/main/cpp/bs_build.cpp` reimplements the
outfit build pipeline from BodySlide and Outfit Studio: slider set and preset
parsing, shape morph application, zap handling, weight variants and NIF
output. The semantics of slider values, defaults and output naming follow that
project. The full GPL-3.0 text is in `LICENSE`.

## nifly

- Author: Ousnius
- Source: https://github.com/ousnius/nifly (git submodule, pinned at `40584be`)
- Licence: GNU General Public License v3.0

Used for NIF parsing, mesh manipulation and writing. The full GPL-3.0 text is
in `LICENSE`; the submodule also ships its own copy.

## tinyxml2 11.0.0

- Author: Lee Thomason
- Source: https://github.com/leethomason/tinyxml2
- Licence: zlib

Vendored under `app/src/main/cpp/third_party/tinyxml2`.

```
This software is provided 'as-is', without any express or implied
warranty. In no event will the authors be held liable for any
damages arising from the use of this software.

Permission is granted to anyone to use this software for any
purpose, including commercial applications, and to alter it and
redistribute it freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must
not claim that you wrote the original software. If you use this
software in a product, an acknowledgment in the product documentation
would be appreciated but is not required.

2. Altered source versions must be plainly marked as such, and
must not be misrepresented as being the original software.

3. This notice may not be removed or altered from any source
distribution.
```

## Test and reference data

The following were used during development to validate preset application and
the outfit build path. No assets from them are redistributed in this
repository or in the APK.

- **CBBE** — Caliente and Ousnius. Slider sets, presets and reference body.
- **Remodeled Armor for CBBE BodySlide HDT Replacer** — CT77. Armor/refit
  slider sets used to verify outfit and clothing builds.

## Android and Kotlin libraries

- Jetpack Compose, Material 3, AndroidX — Android Open Source Project,
  Apache License 2.0.
- Kotlin standard library and kotlinx.coroutines — JetBrains, Apache License
  2.0.
