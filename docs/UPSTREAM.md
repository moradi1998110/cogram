# Upstream sources

Cogram is a personal, private project. It is not distributed.

| Part | Source | Revision |
| --- | --- | --- |
| Repository root | https://github.com/DrKLO/Telegram | `3de37c8cfb039452be7398909461da6288babf15` (13.1.1, build 7237) |
| `vendor/conversations` | https://codeberg.org/iNPUTmice/Conversations | `c84cad198a9c346fa3ec4cc7a7bb4a92a20a9465` |

The first commit of `main` imports Telegram unmodified, and the second imports Conversations unmodified. Later commits contain Cogram's own changes.

## License note

Telegram is GPLv2 (many files say "or later"; 76% have no license header). Conversations is GPLv3. Review this before giving the app or the code to anyone else, and before making this repository public. See `study.md`.

## Builds

GitHub Actions builds an arm64 debug APK when run manually or when a `v*` tag is pushed. A tag also creates a draft release with the APK attached.
