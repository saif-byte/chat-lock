# Phone Tools (private chat lock)

Shows up on the phone as **Phone Tools** with a plain toolbox icon. To rename it, edit `app_name` and `service_label` in `app/src/main/res/values/strings.xml` and push.

Locks **only the chats/DMs** inside Instagram, X (Twitter) and Snapchat. Feeds, reels, profiles and search stay open; the moment you open your inbox or a conversation, Phone Tools asks for your PIN or fingerprint.

- **No internet permission** – the app physically cannot send data anywhere.
- **No third-party libraries**, no analytics, no ads, no backups. Only the Android framework.
- **Battery-friendly** – no background polling, no wake-locks, no always-on notification. It reacts only to screen changes, and only looks closely while Instagram, X or Snapchat is open.

Requires Android 10 or newer.

---

## Get the APK (no Android Studio needed)

1. Create a free account at github.com and make a **new repository** (Private is fine).
2. On the new repo page, click **“uploading an existing file”**, then drag in **everything inside this folder** (including the `.github` folder) and click **Commit changes**.
   - If the `.github` folder doesn't upload: click **Add file → Create new file**, type the name `.github/workflows/build.yml`, paste the contents of that file, and commit.
3. Open the **Actions** tab. A “Build APK” run starts automatically (takes ~3–5 min). If Actions asks you to enable workflows, click the green button, then **Run workflow**.
4. When it finishes (green tick), go to the repo's **Releases** (right side of the main page) and download **PhoneTools.apk** – easiest straight from your phone's browser. (It's also under the run's **Artifacts** as a zip.)

### Optional but recommended: keep the same signing key

Without this, each build is signed with a throwaway key, so to install a newer build you'd have to uninstall the old one first (and set your PIN again).

Repo → **Settings → Secrets and variables → Actions → New repository secret**. Add four secrets, copying names and values from `ChatLock-signing-secrets.txt`:

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Keep that text file private and **never** upload it into the repo itself.

---

## Install and set up on your phone

1. Open the downloaded APK. Allow “Install unknown apps” for your browser/file manager when asked.
2. Open **Phone Tools** → **Set PIN**.
3. Tap **Turn on in Accessibility settings** → find **Phone Tools** (sometimes under *Downloaded apps* / *Installed services*) → switch it on.
   - **Android 13+:** if the switch is greyed out with “Restricted setting”, go back to Phone Tools → **Open App info** → tap **⋮** (top-right) → **Allow restricted settings**, then turn the service on.
4. Done. Open Instagram or X and go to your messages – the lock screen appears.

### How locking behaves
- Unlocking keeps chats open while you're in them.
- Leaving chats (to the feed, home screen, or another app) locks them again – immediately by default, or after 30 s / 1 min / 5 min if you choose.
- Turning the screen off always locks again.
- Opening a chat from a notification also shows the lock.
- 5 wrong PINs → 30-second wait.
- The Phone Tools app itself also asks for the PIN.
- **Leave chats** on the lock screen sends you to the home screen.

### Some phones kill background services
On Xiaomi / Redmi / POCO, Oppo, Vivo, Realme, Huawei, Samsung: if the lock stops working after a while, set Phone Tools's battery setting to **“No restrictions / Unrestricted”** (App info → Battery). This does not increase battery use – the app is idle when nothing changes on screen.

---

## If detection stops working after an Instagram / X update

These apps don't publish how their screens are built and change them often. Phone Tools recognises chat screens by the screen's internal names and a few labels such as “Message…” or “Start a message”.

1. In Phone Tools, turn on **Show detection test messages**.
2. Open Instagram/X/Snapchat and move between the feed and your chats – small messages say whether a chat screen was detected and why.
3. If chats aren't detected, add a word that appears on the chat screen to **Extra words** (e.g. the inbox's title text), or edit the lists at the top of `ChatDetector.java` and push – GitHub builds a new APK automatically.

## What this can't do
- Android lets you turn off any accessibility service in Settings, so someone with your unlocked phone could disable Phone Tools there. For stronger protection, also use a screen lock on the phone.
- Notification previews can still show message text. To hide it: Settings → Notifications → hide sensitive content on lock screen, or turn off previews in Instagram/X/Snapchat.
