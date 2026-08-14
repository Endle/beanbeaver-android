# BeanBeaver — Privacy Policy

_Last updated: 2026-08-13_

BeanBeaver ("the app") turns photos of grocery receipts into itemized,
Beancount-formatted text **entirely on your device**. This policy explains what
the app does and does not do with your information.

## Data we collect

**None.** BeanBeaver does not collect, sell, or share any personal or financial
information, and sends nothing to its developer. There is no account system, no
analytics, no advertising, and no BeanBeaver server — there is nothing for your
data to be sent to. The one case where the app transmits anything at all is an
export you set up and start yourself, described below.

## How your data is processed

- **On-device only.** Receipt images you pick (or capture with the document
  scanner) are processed locally: optical character recognition (OCR), parsing,
  and categorization all run on your phone using models bundled inside the app.
- **Network use, and its one purpose.** Scanning, parsing and categorization
  never touch the network. BeanBeaver does hold the Internet permission, for a
  single feature: **GitHub sync**, which you have to configure before it can do
  anything. Once you have connected a repository and filed a receipt, the app
  uploads *that receipt* to *your* repository — its Beancount text, its parsed
  `.json`, and the receipt image itself — over an authorization you granted to
  your own GitHub account. Receipts you do not file are never uploaded, and
  nothing is ever sent anywhere else.
- **Images.** Photos are read from the images you explicitly select via the
  Android photo picker, or — in the builds that use ML Kit — captured through Google Play
  services' document scanner. BeanBeaver does not access your photo library
  beyond the specific images you choose — it cannot read what is already in it.
- **Saving a photo back to your library.** If you use **"Save to Camera Roll"**
  (Receipts → a receipt → the ⋮ menu), a copy of that one receipt's photo is
  written to your photo library, under a "BeanBeaver" album. That copy sits
  outside the app's storage, so none of BeanBeaver's own delete controls —
  clearing a photo, deleting a receipt, Delete All Receipts — can reach it;
  removing it means deleting it from your photo library yourself. Nothing is
  written to your library unless you ask for it this way.
- **Storage.** Results stay in the app unless you explicitly export or share
  them using your device's own share feature, at which point they are handled by
  the destination you pick.

## Third-party components

BeanBeaver is published in more than one build, and what separates them is
whether the build contains any Google code. Settings > About names the build you
are running.

**The Google Play and SafeHaven builds** are identical in this respect: the
in-app document scanner is provided by **Google Play
services (ML Kit)**. When first used, Google Play services may download the
scanner module; that download is handled by Google Play services, not by
BeanBeaver, and is governed by
[Google's Privacy Policy](https://policies.google.com/privacy).

ML Kit is used for **the document-capture screen and nothing else** — it frames
and straightens the photo you are taking. It does not read your receipt. All
text recognition, parsing and categorization are BeanBeaver's own, running
offline on models bundled in the app, and no other ML Kit or Google Play
services feature is included.

**The F-Droid build** contains no Google Play services code at all. It replaces
that one screen with the Android photo picker; everything after the photo is
identical.

## Permissions

- **Internet**: used only by GitHub sync, as described above. BeanBeaver requests
  it even in builds you never connect to GitHub, because a permission is declared
  once for the whole app rather than granted per feature.
- **Camera** _(optional)_: in the builds that use ML Kit, the document scanner's capture
  screen belongs to Google Play services and runs under its own permissions, so
  BeanBeaver itself does not request camera access. Photos picked from your
  library need none either.

"Save to Camera Roll" needs no permission at all: BeanBeaver adds the photo
through Android's own media store, which lets an app create entries it owns
without granting it any view of the rest of your library.

BeanBeaver requests no location, contacts, or account permissions.

## Children's privacy

BeanBeaver is not directed at children and collects no data from anyone.

## Changes to this policy

If this policy changes, the "Last updated" date above will change accordingly.

## Contact

Questions about this policy: **litimetal@gmail.com**
