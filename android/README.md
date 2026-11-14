# TNT Launcher для Android

Лаунчер Minecraft: Java Edition для телефонов и планшетов. Описание возможностей — в
[README в корне репозитория](../README.md).

## Сборка

Нужны JDK 17, Android SDK (platform 37, build-tools 36/37) и NDK 27.3.13750724.
ndk-build не работает в путях с пробелами, поэтому собирайте из папки без пробелов
(в Windows — через `build_android.bat` в корне, он делает это сам).

```bash
git submodule update --init --recursive
./gradlew :app_pojavlauncher:assembleDebug
```

APK: `app_pojavlauncher/build/outputs/apk/debug/`.

Java для игры в APK не вшита — лаунчер скачивает нужную версию (8 / 17 / 21 / 25) сам при первом запуске сборки.

## Лицензия и благодарности

TNT Launcher основан на [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android)
(форк [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)) и распространяется
под той же лицензией — [GNU LGPLv3](LICENSE).

Используемые проекты:

* [Boardwalk](https://github.com/zhuowei/Boardwalk): Apache License 2.0 / GPLv2
* [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher), [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android): LGPLv3
* Android Support Libraries: Apache License 2.0
* [GL4ES](https://github.com/AngelAuraMC/gl4es): MIT
* [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues): LGPL-2.1
* [Krypton Wrapper](https://github.com/BZLZHH/NG-GL4ES): MIT
* [ANGLE](https://chromium.googlesource.com/angle/angle): [лицензия](app_pojavlauncher/src/main/assets/licenses/ANGLE_LICENSE)
* [OpenJDK](https://github.com/AngelAuraMC/openjdk-multiarch-jdk8u): GPLv2 + Classpath Exception
* [LWJGL3](https://github.com/AngelAuraMC/lwjgl3): BSD-3
* [LWJGLX](https://github.com/AngelAuraMC/lwjglx)
* [Mesa 3D](https://gitlab.freedesktop.org/mesa/mesa): MIT
* [bhook](https://github.com/bytedance/bhook): MIT
* [libepoxy](https://github.com/anholt/libepoxy): MIT
* [virglrenderer](https://github.com/AngelAuraMC/virglrenderer): MIT
* [OpenAL-Soft](https://github.com/kcat/openal-soft): GPLv2 ([oboe](https://github.com/google/oboe): Apache 2.0, pffft)
* [SDL3](https://github.com/libsdl-org/SDL), [sdl2-compat](https://github.com/libsdl-org/sdl2-compat): zlib
* Головы скинов — [MCHeads](https://mc-heads.net); новости — Mojang и [Minecraft Wiki](https://minecraft.wiki) (CC BY-NC-SA)

Авторы TNT Launcher: **Zhenyok** ([GitHub](https://github.com/Zhenyok94720572)) — разработчик, **Molly** — моральная поддержка.
