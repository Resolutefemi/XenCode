# About and Updates

"Settings → About" shows app information and is also where you check for updates.

## Version and update checks

The card at the top shows the app icon, the app name, and a one-line description; the "Version" row below it shows the current version number.

**Tap the version row to check for updates manually**: it queries the GitHub releases list for a newer version (filtered by your chosen update channel). When a new version is found, a dialog shows "New version found" and lists **the full changelog between your current version and the latest**; tapping "Go to download" opens the Release page of **the exact detected version** in the browser to get the APK. If you are already up to date, it says "Already up to date"; on network failure it says "Check failed" with the error details.

## Automatic update checks

On by default. It checks asynchronously in the background when the app opens to the home screen, **at most once per day** (once checked that day, it is not repeated). When a new version is found the update dialog pops up automatically; network failures are handled silently so you are not disturbed.

After you turn it off, no more automatic checks happen, but tapping the version row still checks manually.

## Update channels

Defaults to "Stable":

- **Stable**: only checks for official releases, filtering out RC previews.
- **Latest**: includes RC previews.

Switch to Latest to try new features early; stay on Stable if you prefer stability.

Whichever channel you choose, "Go to download" always opens the Release page of the exact version shown in the dialog: if the Latest channel detects an RC preview, it opens that RC's page, never falling back to the older stable release.

## Other entries

- **GitHub repository**: opens the source repository in the system browser.
- **Open source license**: this project uses GPL-3.0; tap to open the full LICENSE file.
