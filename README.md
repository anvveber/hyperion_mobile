# hyperion mobile

Android app for [hyperion](https://github.com/anvveber/hyperion) servers, a personal relationships manager.

The Android app is a small native app that opens your own server. It shows the same pages as the web version, adapted to the phone: the system bars take the colour of your theme, the back button moves back through pages, files open in the Android file picker, downloads land in your Downloads folder, and long-pressing the icon gives shortcuts to **Add person**, **Search** and **Change server**.

It does not use Google Play Services or Firebase, so it runs on GrapheneOS and other de-Googled phones with or without sandboxed Google Play. The only permission it asks for is network access.

## Installing

The app is not in an app store. Each build is published as a release on GitHub:

1. On your phone, open the [Releases page](https://github.com/anvveber/hyperion_mobile/releases) of this repository.
2. Open the newest release whose name starts with "Android app".
3. Download the `.apk` file under **Assets**.
4. Open the downloaded file. Android asks you to allow your browser to install apps the first time; allow it, then tap **Install**.

On GrapheneOS you can also use [Obtainium](https://obtainium.imranr.dev/) to install and update the app straight from the GitHub releases. Add the repository URL and set the release title filter to the regular expression `^Android app \d`, so it skips the web app releases and previews.

## First launch

The app asks for your server address, for example `https://eye.example.org`. It checks the address before continuing:

- If the server answers like hyperion, the app opens its login page.
- If a login page in front of the server answers (Pangolin, Authelia, Authentik and similar), the app opens it. Sign in to it first, then to hyperion. The app keeps both logins.
- If something else answers, the app says so and offers **Continue anyway**.
- If nothing answers, check the address and your connection. **Continue anyway** is still offered, for example when you set the app up before the server is reachable.

Sign in with your hyperion account as you would in a browser. The app keeps you signed in until you sign out or the session expires.

To switch to another server later, long-press the app icon and choose **Change server**, or tap **Change server** on the error screen.

## Servers behind Pangolin

The Pangolin login page opens inside the app, and so do other pages on the same parent domain (for example `pangolin.example.org` next to `eye.example.org`), so the login cookie ends up in the app. You stay signed in for as long as Pangolin's session lasts. When it expires, Pangolin's login page appears again.

Logins that go through Google do not work inside apps: Google blocks its sign-in page in embedded browsers. Use a Pangolin password, PIN or another identity provider for this resource.

Pangolin's **Bypass Auth** access rules are not needed. A bypass rule on a path such as `/` would let anyone on the internet reach hyperion's own login page without passing Pangolin first.

## Notifications

Browser push notifications need Google's push service, which de-Googled phones do not have. Use the ntfy channel instead: install the ntfy app (available on F-Droid), subscribe to a topic, and add it in Settings, Notifications. See [Notifications](https://github.com/anvveber/hyperion/blob/master/docs/src/content/docs/features/notifications.md#sending-reminders-to-ntfy).

## Updating

Install the newer `.apk` over the old one. Your server address and login are kept. If Android says the app is not compatible with the installed version, the two builds were signed with different keys (see below); uninstall the old one first.

## Building the APK yourself

The **Android App** workflow in GitHub Actions builds the APK on every pull request and every push to `main`:

- Pull requests publish to a pre-release called "Android app preview", replaced on every build.
- Merges to `main` publish a numbered release, for example "Android app 1.0.12".

To build locally instead, install Android Studio (or the Android command-line tools and a JDK 17) and run `./gradlew assembleRelease` in this repository.

### Signing key

Android only installs an update over an existing app if both are signed with the same key. Without a key of your own, every build is signed with a throwaway key, so each update needs an uninstall first and you have to sign in again. Set up a key once:

1. Create a keystore. On any machine with Docker, run this in an empty folder, and pick a password when asked:

   ```bash
   docker run --rm -it -v "$PWD:/out" eclipse-temurin:17 \
     keytool -genkeypair -keystore /out/hyperion-release.jks -alias hyperion \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=hyperion"
   ```

2. Turn it into text: `base64 -w0 hyperion-release.jks > hyperion-release.jks.txt`
3. On GitHub, open the repository's **Settings**, then **Secrets and variables**, then **Actions**, and add two repository secrets:
   - `ANDROID_KEYSTORE_BASE64`: the contents of `hyperion-release.jks.txt`
   - `ANDROID_KEYSTORE_PASSWORD`: the password from step 1
4. Keep `hyperion-release.jks` and its password somewhere safe, such as a password manager. If you lose them, the next build cannot update installed copies.

The first build signed with your key cannot update a copy signed with the throwaway key, so uninstall that one once.

## Technical details

- Package name `io.github.anvveber.hyperion`. Kotlin, framework views, `androidx.core` and `androidx.webkit` only.

- Requires Android 10 or newer.
- The app is a WebView showing your server. It only injects a small script into pages from your server's own address, to save files the web app builds in the page (exports, vCards, backups) and to read the theme colour.
- Pages on other addresses, such as a reverse-proxy or single sign-on login, also open inside the app so their login cookies stay with it. Links that the web app opens in a new tab, and `mailto:`, `tel:`, `geo:` and messenger links, open in the matching app on the phone.
- The app identifies itself by adding `HyperionAndroid/<version>` to the WebView user agent. The web app uses this to hide its "install app" prompt.
- Plain `http://` addresses are allowed for home-network setups, with a warning. Certificates from a private certificate authority work when that CA is installed in Android's settings. Invalid certificates are never accepted.
- WebView Safe Browsing and usage metrics are turned off, and app data is excluded from Android backups.
