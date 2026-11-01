# Contributing

Thanks for helping. The most useful thing is trying it on a board we don't have.

## Ways to help

- **Report your board**, whether it worked or not: [board report](../../issues/new?template=board-report.yml).
- **Add a board profile:** [guide](docs/ADD-YOUR-BOARD.md).
- **Report what an app allows:** does a title link open the title? Say which app, version and country.
- **Fix bugs and improve the guides.**

## Set up

You need JDK 17 and the Android SDK (build-tools 34, platform 34).

```bash
./build.sh
tools/check.sh    # run this before every pull request
```

## Rules for the code

- Plain Java 8 language level, with no Gradle and no libraries. Minimum Android 8.
- Keep the home screen smooth: no allocations in `onDraw`, no slow work on the UI thread.
- On-screen TV text goes through `Ui.tr`. After changing text, run `python3 tools/translate.py`.
- New Jarvis actions must go on the fixed list in `Jarvis.java`. Anything hard to undo must wait for a yes.
- New phone commands go in `LinkService.handle`, with limits on what they accept.

## Please don't send

- API keys, `config.properties`, keystores or personal details.
- Anything that fakes a device, gets around DRM, or patches other companies' apps.
- Anything that opens factory menus or flashes firmware.
- Claims about boards you haven't tested.

By contributing, you agree your work is released under the [MIT License](LICENSE). Please follow our [Code of Conduct](CODE_OF_CONDUCT.md).
