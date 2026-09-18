# Munro Map

All 282 Munros on a pan-and-zoom map, your GPS position, and a list sorted by
distance from wherever you are. Works with no signal and no data connection.

This is version 0.1. There is **no basemap yet** — no coastline, no contours, no
paths. The dots are the Munros themselves, and because Scotland's mountains are
where they are, they draw a recognisable outline of the Highlands on their own.
The map pack comes next.

---

## Getting the APK onto your phone

You do not need Android Studio, a developer account, or any money.

### 1. Make a GitHub account

<https://github.com/signup> — free.

### 2. Create a repository

Click the **+** at the top right, then **New repository**. Any name.
Leave it Private if you like. Do **not** tick "Add a README".

### 3. Upload these files

On the empty repository page, click **uploading an existing file**.
Unzip the folder on your Mac, then drag *the contents* of the `munro-map`
folder into the browser — not the folder itself. You should end up with
`app`, `gradle`, `.github`, `build.gradle.kts`, `settings.gradle.kts` and
`gradle.properties` at the top level of the repository.

Scroll down, click **Commit changes**.

### 4. Wait for the build

Click the **Actions** tab. A job called "Build APK" starts on its own.
Yellow dot means running, green tick means done. Give it 3–6 minutes —
the first run is the slowest because it downloads everything from scratch.

If it goes red, click into the run, open the failed step, and send me the
error text. That's normal for a first build and it is my problem to fix,
not yours.

### 5. Download it

Click the finished run. At the bottom, under **Artifacts**, there's
`munro-map-apk`. Click it — you get a zip. Inside is `app-debug.apk`.

### 6. Install it

Easiest route: upload the APK to Google Drive from your Mac, then open Drive
on the phone and tap the file.

Android will refuse the first time and offer you a settings screen. Allow
installs from whichever app you're opening it with (Drive, Files, Chrome),
tap back, tap the file again. It installs.

Samsung may also show a "Blocked by Play Protect" dialog. **More details →
Install anyway.** That warning means "Google hasn't scanned this app", which is
true, because it was built five minutes ago from your own repository.

### 7. First run

The app will ask for location permission. Grant it **While using the app**.
The first GPS fix outdoors takes 30 seconds or so. Indoors it may take minutes,
or never — that's the phone, not the app.

---

## Using it

- **Map tab** — drag to pan, pinch to zoom. Tap a dot for name, height, grid
  reference and straight-line distance from you. Names appear once you zoom in.
- **Centre on me** — jumps to your position.
- **Show all** — zooms back out to the whole of Scotland.
- **Nearest tab** — every Munro sorted by distance from you, with a compass
  direction.

---

## Building it on your Mac instead

Only needed if you want to change things yourself.

1. Install Android Studio (free, ~10 GB with the SDK).
2. **File → Open**, pick this folder.
3. Wait for Gradle sync. First one is slow.
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The APK lands in `app/build/outputs/apk/debug/`.

---

## What's deliberately not here yet

- **Basemap.** Contours, hillshading, coastline and the OpenStreetMap path
  network. This needs a ~1 GB tile pack built by a separate pipeline and
  downloaded on first run.
- **Routes.** Walkhighlands route descriptions and GPX files are free for
  personal use but cannot be redistributed, so they can't be bundled. The plan
  is the OpenStreetMap footpath network drawn on the basemap, plus the ability
  to import your own GPX files.
- **Bagging tick-list.** Easy to add once the rest is settled.

---

## Safety

This is a hobby app with a user base of one, built in an afternoon, and it has
never been tested on a hill. Treat it as a supplement to navigation, not a
replacement. Map and compass go in the bag regardless.

---

## Data

Munro names, heights and grid references come from the
[Database of British and Irish Hills](https://www.hill-bagging.co.uk/dobih/),
licensed **CC BY 3.0**, via the Wikipedia *List of Munro mountains* table.
OS grid references were converted to WGS84, so summit positions are accurate to
roughly 100 m — the precision of the source grid references, not a limit of the
conversion.

No third-party libraries. No network access. No analytics. Nothing leaves the
phone.
