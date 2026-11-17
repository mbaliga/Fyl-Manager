# Fylz desktop Linux: owner verification checklist

Everything below is NEEDS-DEVICE-VALIDATION: nothing here has been seen on a real display.

1. Get the `.deb` / AppImage from a `linux-v*` tag run of `linux-desktop.yml` (or build locally,
   `docs/linux/README.md`). Install on Ubuntu 24.04 (the `.deb` is only installable on the release
   it was built on, or newer).
2. Launch `fylz` from the app menu. Expected: a window titled "Fylz" appears (the shell root is a
   `Window`; this is the first real-display check of that fix), listing your home/start folder.
3. Click a folder: it descends; "Up" returns. Click a `.zip`/`.tar.gz`: the archive view lists
   entries and a format name. Close it; the app exits cleanly.
4. Confirm no file in
   your tree was modified by browsing (the shell is read-only; the journal count shown in the footer is what the shell reads).
5. Confirm there is no network traffic from the process (`ss -tp | grep fylz` while running).
6. Check the AppImage runs on a second machine with a distro as new as the build host.
7. Report any "stack smashing" abort. On Qt 6 it was not seen in the writing container; on Qt 5 it
   is reproducible (see README). Also report what the Qt 5 informational CI job did.
8. Decide: the app id `io.github.mbaliga.Fylz`; arm64 leg (free runners are public-repo only);
   whether Flatpak is wanted.
