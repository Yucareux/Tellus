Translations are managed via Crowdin: https://crowdin.com/project/distant-horizons

# How to help:
1. Create a Crowdin account
2. Join the project
3. Pick a language from [the dashboard](https://crowdin.com/project/distant-horizons)
4. Start translating

# Notes:
- Keys ending with @tooltip are tooltips.
    - i.e. they appear when you hover your mouse over the option.
- Keep formatting codes intact, IE: §, %s, %d, %1$s.
- For newlines, utilize Shift + Enter instead of `\n`.

## To pull translations into the repo
- Download the [Crowdin CLI](https://github.com/crowdin/crowdin-cli/releases)
- Run `crowdin download --export-only-approved --skip-untranslated-files` in the project root.