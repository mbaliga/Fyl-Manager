# Privacy Policy — Fylz

> **This is the working copy, not the hosted one.** The URL Play Console points at
> is **https://asystemofcells.com/fylz/privacy**. Keep the two in sync by hand.

**Last updated: 29 August 2026**

Fylz is a file manager for Android (package `io.github.mbaliga.fylz`), made by
A System of Cells. A file manager can see everything you have, so this policy is
specific about what it does with that.

## The short version

Fylz has no accounts, no advertising, no analytics and no tracking. **Your files
are never uploaded, indexed remotely or scanned.** We operate no servers and
receive nothing from the app.

## What the app collects

**Nothing.** No file names, no file contents, no directory listings, no usage data.

## All Files Access, and why the app asks for it

Fylz requests access to your device storage because browsing, moving, copying,
renaming and recovering files across that storage is the whole function of the app.
Android's more limited access model grants only the folders you pick one at a time,
which cannot support enumerating your volumes, moving files between them, or
keeping a durable record of a multi-file operation.

That access is used **only** to show you your files and to perform the operations
you ask for. Nothing is read in the background, nothing is catalogued for any
purpose of ours, and nothing is transmitted.

## Cloud, USB and network storage

When you connect a cloud, USB or third-party provider, the app uses Android's own
storage access framework and keeps the grant you gave so you are not asked
repeatedly. If you configure a remote provider such as SMB, SFTP or S3, the app
connects to **that server, with the credentials you supplied**. Those credentials
are stored encrypted on your device and are sent only to the server they belong to.

## Optional AI-assisted organisation

Off unless you set it up, and it works by proposing changes you approve. Nothing is
moved, renamed or deleted without your explicit say-so.

If you supply **your own API key** for a provider, then the information the feature
needs in order to make a proposal, which can include file names and, depending on
what you ask for, file contents, is sent to **that provider** over HTTPS, under
**that provider's** privacy policy.

- This is a real transfer to a third party and is worth stating plainly.
- It never happens unless you configured it and asked for it.
- We never see it. There is no proxy of ours in front of the request.
- If you do not use this feature, the app makes no AI requests at all.

## What is stored, and where

Your tabs, view preferences, theme, recycle-bin contents, the operation journal and
any provider credentials are stored in the app's private storage on your device.
Uninstalling Fylz deletes all of it.

The recycle bin **never expires on its own**. Nothing in it is deleted on a timer.
Permanent deletion is always an action you take.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | To browse and manage your files across device storage. Used only for what you ask for. |
| `INTERNET` | Only for remote providers you configured, and the optional AI feature. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | So a long copy or move keeps running when you leave the app. |
| `VIBRATE` | Haptic confirmation on destructive actions. |

## Children

Fylz is not directed at children and collects no personal information from anyone,
including children.

## Changes

If this policy changes, the "Last updated" date above changes with it, and the
revised policy is published at this same URL.

## Contact

Fylz is made by **A System of Cells**. Questions about this policy or the app:
fylz@asystemofcells.com
