# VowiDroid

VowiDroid is a standalone IMS and WiFi Calling client for Android that runs
entirely in user mode: a phone and messaging app that connects to the
carrier's Wi-Fi calling service by itself.

The whole stack runs inside the app:
- the ePDG tunnel (IKEv2, EAP-AKA, ESP in UDP),
- an in-app IP stack for the tunnel's inner address (UDP, TCP, fragments),
- IMS registration with ipsec-3gpp,
- SIP, SMS over IMS, USSD, and voice calls with AMR-WB, AMR and G.711.

Android is used only for what only Android can do: read the SIM, let the SIM
answer the network's authentication challenge, pick the Wi-Fi network, encode
audio and route call audio. VowiDroid does not use Android's IMS framework or
the phone's radio.

A line runs on either of these:
- **The phone's own SIM** (Android 12+), after a one-time SIM-access grant
  from root or adb.
- **A SIM in a USB smart-card reader** (USB CCID), on any phone with USB host
  support. VowiDroid drives the reader itself.

A line is the subscriber that the SIM's IMSI names. It follows its card from
one slot to another, into a reader and back, and onto a replacement card that
keeps the IMSI, with its texts, calls and settings.

- [What it does](#what-it-does)
- [Getting started](#getting-started)
- [Everyday use](#everyday-use)
- [How it works](#how-it-works)
- [Building and testing](#building-and-testing)
- [Carrier data](#carrier-data)
- [Known gaps](#known-gaps)
- [User agreement](#user-agreement)
- [License](#license)
- [References](#references)

## What it does

- **Calls:** hold and resume, call waiting, Add call and conference calls
  (TS 24.147), the in-call keypad, and Bluetooth, car and headset audio
  through a self-managed Telecom account. Calls can appear in the phone's own
  call history (Android 10+) and on cars and watches (Android 12+).
- **Messages:** SMS over IMS with delivery reports, conversation
  notifications with reply, conversation shortcuts and bubbles (Android 11+),
  Direct Share (Android 10+), drafts, quick responses, blocked numbers and a
  conversations widget.
- **Carrier services:** voicemail waiting (RFC 3842 and SMS indications),
  USSD with menus (TS 24.390), call forwarding codes, caller ID per line
  (TS 24.607), a call-waiting switch, and the emergency (E911) address
  through the carrier's entitlement server (GSMA TS.43).
- **SIM life cycle:** Eject, cards moved between slots and readers, airplane
  mode, and a notification when a new card is put in.
- **Notifications:** each line has its own group of categories in Android's
  settings (Incoming calls, Missed calls, Messages, Voicemail), so every
  number can ring and alert differently; the line's settings page lists
  them with their state and opens each. The background service's
  notification reads "<line> online"; Settings → Notifications shows
  whether it is on and opens Android's page for it, where it can be turned
  off (the service keeps running, and Android 13+ lists it among the
  shade's active apps instead).
- **Status at a glance:** a green banner at the top of the tabs says
  "Incoming call" while one rings and "Return to call" while one is in
  progress, and opens the call screen. A red card at the top of the Phone
  and Messages lists, in the style of the "No line is ready" card, is
  pinned while Android refuses VowiDroid something for lack of a
  permission (a SIM it could not read, a text or call it could not show, a
  call without a microphone); it names the permissions, says what was
  refused last, opens the Permissions page, and goes once the permission
  is granted. A second card names each line that is still connecting,
  one page per line with a page counter to swipe through, with the step it
  is on; when a line fails, the step it failed at, the reason and the error
  code, with when the next attempt comes. IMSI privacy on Wi-Fi is a
  switch on each line, on by default.


## Getting started

### What you need

- **A phone.** Android 12 or newer to use the phone's own SIM. For a SIM in
  a USB reader, any phone from Android 8 with USB host support.
- **A SIM whose carrier offers Wi-Fi calling** through the standard ePDG,
  with the line provisioned for it (a line that can use Wi-Fi calling in
  the phone's own settings qualifies). Tried live so far: T-Mobile US and its
  MVNOs, and giffgaff on O2 UK.
- **A network with internet access.** Wi-Fi is the usual one; the tunnel also
  runs over mobile data, Ethernet or a VPN, whichever Android is using.
  Some carriers answer their ePDG only from their home country: giffgaff's
  (O2's) does not answer from abroad, and connects through a VPN with a UK
  exit.
- **Root, or a computer with adb,** for the one-time SIM-access grant when the
  phone's own SIM is used. A SIM in a USB reader needs neither.

### Install

There is no store listing. Build the debug APK as described under
[Building and testing](#building-and-testing) and install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### First launch

1. **The user agreement** opens first. Nothing runs, not even the background
   service, until it is accepted. Decline closes the app.
2. **The Permissions page** lists what VowiDroid needs from Android, each with
   a green tick or a red cross: Phone, SIM access, Notifications, Microphone,
   Calls over the lock screen, Unrestricted battery use, and Contacts
   (optional). A tap on a red item asks for it. On a ROM with its own battery
   layer (Samsung's One UI, Xiaomi's HyperOS, Huawei's EMUI, OPPO's ColorOS,
   vivo's OriginOS) a further item names the rule to change, with a grey
   question mark where the ROM will not say whether it is set, and opens the
   ROM's page.
3. **SIM access** is the one item a tap cannot grant. It is the
   `USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER` app-op, which Android lets only root
   or a computer grant; granted once, it stays until the app is uninstalled.
   While it is missing, a card at the top of the page explains how:
   - On a rooted phone, tap **Grant with root**.
   - Otherwise, turn on USB debugging, connect the phone to a computer with
     adb and run the command below. The page shows it with a Copy button, and
     VowiDroid checks the SIM again when you come back to it.

     ```bash
     adb shell appops set com.vowi.vowidroid USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow
     ```

     To check the grant, this prints `allow`:

     ```bash
     adb shell appops get com.vowi.vowidroid USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
     ```

   The page can be reopened later from **Settings → Permissions** or the
   **Permissions** shortcut on the launcher icon. Whenever Android refuses
   VowiDroid a read for lack of a permission, a red card at the top of the
   Phone and Messages lists says what was refused and opens the page.
4. **Turn off Wi-Fi calling for that SIM in Android's settings,** so the phone
   and VowiDroid do not both register with the carrier. With some carriers the
   phone's own VoLTE stops working while VowiDroid's registration is up.

### Add a line

Open **Settings → Add a line** and choose the SIM. Its WiFi Calling turns on
right away. While it connects, a card at the top of the Phone and Messages
lists shows the step it is on (reading the SIM, finding the ePDG, opening the
tunnel, registering with IMS); the title turns to "<line> online" and the
line shows "Registered" with its number once the registration is up. With
two lines the card has a page per line, with a counter to swipe through.

For a SIM in a USB reader: turn the SIM's PIN off in a phone first, since
VowiDroid does not ask for it. Put the SIM in a USB CCID smart-card reader
and plug the reader into the phone. Android offers to open VowiDroid, which
also gives VowiDroid the reader; otherwise **Add a line** asks for access.
Then add the SIM as above. A SIM in a reader also gives up its SMS centre
number, which the phone's own SIM cannot.

### Removable eSIM cards

A removable eSIM (an eUICC on a plastic card, such as 9eSIM or eSTK.me)
presents the profile you enabled with EasyEUICC or OpenEUICC as an ordinary
USIM, so it works in a phone slot or a USB reader like any SIM. Only the
enabled profile is visible, and each profile is its own line.

Such a card can also let VowiDroid read it over the card's own channel
(Android's Open Mobile API), the way eSIM manager apps reach it. That channel
keeps working after you switch the SIM off in Android's settings, so the
phone stays off the cellular network while VowiDroid keeps the line
registered over Wi-Fi, and it needs no adb step. The card decides. A card
with no access rules at all is allowed on phones that follow Android's
default; a 9eSIM carrying a T-Mobile profile worked that way on a Xiaomi,
with the line registered seconds after the SIM was switched off. A card that
carries access rules (ARA-M) must list the SHA-1 of VowiDroid's signing
certificate in them, which **Settings → Advanced → Direct card access** shows
along with the state of each slot; cards that list only the EasyEUICC hash
refuse other apps, and cards whose owner can edit the rules, such as
eSTK.me, take the hash from that screen.

### When a line does not connect

The status card names the step that failed and the reason, and counts down
to the next attempt (10 s, then 20 s, then every 30 s). The usual causes:

- **"no ePDG address … answered"** at the tunnel step: the carrier's ePDG is
  not reachable from this network. Some carriers answer only from their home
  country; a VPN with an exit there works, with the line's **Network** set
  to Automatic or VPN only.
- **"the P-CSCF did not acknowledge the SIP request" or "closed by the
  P-CSCF" after the tunnel came up, typically over a VPN**: the path under
  the tunnel is narrower than Wi-Fi's 1500 bytes (many VPNs run at 1280),
  so full-size packets were fragmented and lost. VowiDroid now reads the
  network's MTU and sizes its packets to fit; the log says "inner MTU …"
  when it had to shrink them.
- **A bare private code such as 16377, right after IKE_SA_INIT**: some
  carriers ask the ePDG for the phone's IMEI and allow only devices they
  know. Set one under the line's **Device identity (IMEI)**: the phone's own,
  one you type, or a generated one. The log then says whether the ePDG asked
  and what was sent.
- **"refused by the carrier"**: the ePDG turned the subscriber away (TS
  24.302, e.g. NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED). The line is not
  provisioned for Wi-Fi calling, or the carrier does not allow it from this
  kind of client. No retry until the SIM goes in again or you tap Reconnect.
- **"the carrier asked for a pause"**: a NETWORK_FAILURE with a back-off
  timer; the line retries when it ends.
- **SIM access is off**, airplane mode, or a PIN-locked SIM: the line's row
  under Settings says so, and the red card names the missing permission.
- **A carrier that refuses the hidden IMSI**: the line retries with the
  plain identity by itself; its **Hide the IMSI** switch shows what is in use.

**Settings → Advanced → Logs** shows what the tunnel, registration and calls
are doing, step by step.

## Everyday use

**Calls.** The Phone tab holds the call history and the dialpad. With more
than one line, the dialpad and the new-message screen show a line chooser;
the chosen line is filled in and ticked. Long-press 1 for voicemail, 0 for
"+". The in-call screen has mute, speaker and audio routing (Bluetooth, car,
wired headset), hold, the keypad, Add call, Swap, Merge and Manage for
conference calls, and quick text replies when declining. Calls are
self-managed Telecom calls, so they appear in the phone's own call history
(a switch under Settings) and on cars and watches. Emergency numbers are
handed to the phone's own dialer.

**Texts.** The Messages tab lists conversations, with search, archive, pin,
mute, block and delete; a swipe archives. A text sent or received in an
archived conversation brings it back. Notifications offer Reply and Mark as
read, and conversations can be bubbles and Direct Share targets. A text the
service centre refuses shows as not sent with the centre's reason and a
"Check SMS center" link to the line's settings.

**Each line's settings** (tap the line under Settings): rename, eject the
SIM, the network the tunnel uses, Hide the IMSI, the device identity (an
IMEI: the phone's own, one you type, or a generated one; sent to the ePDG
when it asks, once the ePDG has proven itself, and used as the line's SIP
instance), caller ID, voicemail number, call forwarding, the emergency
address, the SMS centre, the line's notification categories, and the
details of the last connection (state, ePDG, carrier profile, network,
identity, last error and its code).

**Settings** also has the default line for calls and texts, call waiting,
whether calls show in the phone's history, blocked numbers, quick responses,
the Line status notification, the language (System default, English or
简体中文), the logs, the user agreement and the about box.

**Keeping it running.** The background service holds a wake lock and a Wi-Fi
lock so keepalives and re-registration go on with the screen off. What stops
it is the ROM: allow it under the Permissions page's battery items (Android's
allow-list and the ROM's own rule), or the ROM stops the service once the
screen has been off for a while. If the ROM kills the service anyway, it
comes back the next time the app is opened.

### The SMS centre

Sending texts needs the SIM's SMS centre number. A SIM in a USB reader gives
it up directly; for the phone's own SIM, Android lets only privileged apps and
the default SMS app read it, so VowiDroid falls back to the carrier's usual
number from `app/src/main/assets/sms_centers.json`, a hand-kept table by home
network (T-Mobile US, AT&T, the main UK, German, French, Spanish, Italian,
Dutch, Canadian and Australian carriers) with a source for each entry; an
MVNO with its own centre on its host's network, such as giffgaff on O2, has
an entry keyed by its carrier profile. The iPhone bundles carry no SMS
centre, so the bundle importer never touches that file. When the service
centre refuses a text (an RP-ERROR after the IMS core accepted it), the text
shows as not sent with the centre's reason and a "Check SMS center" link to
the line's settings. Each line shows the number in use and where it came from under
**Messaging → SMS center** in its settings, and takes your own entry there,
which wins over both. The entry is stored by ICCID, so a swapped card never
inherits it. Verizon, Bell and Telus publish no number, so those lines need
an entry.

### Messages for the SIM card

Carriers configure a SIM remotely with text messages addressed to the card
itself (SMS-PP data download, TS 31.111), for example right after an
activation. A SIM in a USB reader gets them: VowiDroid hands each one to
the card the way a phone's modem does, returns the card's answer to the
network, and sends any text the card asks to send in reply. A SIM in the
phone's slot cannot be reached that way, since Android keeps the card's
toolkit for the modem; VowiDroid then refuses the message as a temporary
failure, so the network keeps it and can deliver it through the modem, and
shows a notification saying so.

### Notifications

Android's settings list VowiDroid's notification categories: **Line status**
(the service's "<line> online" notification), **Ongoing call**, **SIM cards**,
and one group per line, named after it, with Incoming calls, Missed calls,
Messages and Voicemail. Each line's settings page shows its four categories
with their state (On, Silent or Off) and opens Android's page for each. The
Line status notification has a row under Settings → Notifications that says
whether it is shown and opens its page in Android's settings; turned off
there, the service keeps running without it. Only Android's page can do
that: an app may not hide its own foreground-service notification (the
system puts a category the app muted back to "low" each time the service
posts on it). Samsung's One UI hides categories from an app's notification
page until *Settings → Notifications → Advanced settings → Manage
notification categories for each app* is turned on, so on a Galaxy the rows
in VowiDroid are the way in.

The notification's **Stop** action stops VowiDroid. The lines stay listed,
marked as stopped, and a card at the top of the Phone and Messages screens
offers **Start**; opening the app afresh starts it as well.

### The emergency address (E911)

US carriers must know where a Wi-Fi call comes from, so that a 911 call
over Wi-Fi reaches the right dispatcher, and they take that address through
their entitlement server (GSMA TS.43), the server Android phones use to
activate Wi-Fi calling. A line's settings have **Emergency address**:
VowiDroid signs in to the server with EAP-AKA (the SIM answers the
challenge), shows whether the carrier has an address and the terms on file,
and opens the carrier's own page for entering or changing the address. The
page reports back through the TS.43 JavaScript hooks, and VowiDroid asks
the server again until the carrier has recorded the change.

Carriers may answer only from certain networks: T-Mobile US answers only
from US addresses. When mobile data is available and the SIM is in the
phone, the check goes over mobile data even while Wi-Fi is the default
route; otherwise it goes over Wi-Fi. The server is set per line under
**Entitlement server**; T-Mobile US is prefilled. Android's own client sends
the IMEI as the terminal ID, which apps cannot read, so VowiDroid sends a
stable ID of its own, which T-Mobile accepted.

## How it works

```text
Compose UI ───────────────────▶ VowiCore (lines, cards, messages, calls)
CallManager (Telecom) ────────▶   ├─ WifiCallingEngine, one per line
CoreService (foreground) ─────▶   │    ├─ CarrierProfiles (assets/carrier_profiles.json)
PhoneSims (TelephonyManager) ─┐   │    ├─ TunnelConnector → IkeHandshake → IkeSession
OmapiSims (OMAPI channel) ────┤   │    │
UsbSimReaders (USB CCID) ─────┴──▶│    │     ESP dataplane → NetStack (own IP stack) + ipsec-3gpp
  identity + AKA                  │    ├─ ImsSession (REGISTER, refresh, dispatch)
                                  │    ├─ SmsOverIms, UssdRuntime, voicemail subscription
                                  │    └─ CallRuntime (INVITE, PRACK, UPDATE, REFER, BYE) + RtpMedia
                                  ├─ CardMatcher + LinePlanner (which line's card is where)
                                  ├─ SmsStore (SQLite), LineStore, CallLogStore
                                  └─ CoreLog (Settings → Advanced → Logs)
```

The protocol stack lives under `app/src/main/java/com/vowi/vowidroid/core/`:

| Package | What it holds |
| --- | --- |
| `ike` | IKEv2 codec and crypto, EAP-AKA, the UDP 4500 transport, the handshake, the session runtime (keepalives, DPD, rekeys), address failover and the dataplane |
| `esp` | tunnel-mode ESP with anti-replay and traffic selectors |
| `net` | the in-app IPv4/IPv6 stack: UDP, a compact TCP, fragment reassembly, ICMP echo |
| `ipsec` | ipsec-3gpp transport-mode ESP for SIP |
| `sip` | SIP messages, TCP/UDP links, the protected server port, AKAv1-MD5, the security agreement |
| `ims` | identities, the registration session, dialogs, event subscriptions, voicemail and USSD |
| `sms` | the SMS TPDU codec and SMS over IMS |
| `call` | SDP, RTP, G.711, AMR/AMR-WB payloads (RFC 4867), DTMF (RFC 4733), conference state (RFC 4575) and the call runtime; `AmrCodec` uses Android's MediaCodec |
| `carrier` | carrier profile matching over `carrier_profiles.json`, including each carrier's exact IKE/ESP proposal and tunnel settings from its iPhone bundle |
| `sim` | SIM identity and AKA types, the USB CCID driver, USIM reads and AUTHENTICATE, and matching lines to cards |

### The ePDG connection

A carrier's ePDG name often resolves to several addresses behind a load
balancer, and some of them never answer `IKE_AUTH`. A client that uses only
the first address, or that moves to a second socket on port 4500 after NAT
detection, fails at random, because load balancers that key on the UDP
5-tuple drop the second half of the handshake. So the IKE transport:

1. Resolves the ePDG through the Wi-Fi network's own resolver, with the 3GPP
   alternate MNC spelling and a DNS-over-HTTPS fallback that carries the
   carrier's EDNS client subnet. It tries every address in turn and remembers
   the one that worked.
2. Starts on UDP 4500 with the non-ESP marker from the first packet, so one
   socket and one NAT mapping carry the whole IKE SA and all ESP. Port 500
   with a float to 4500 is the fallback for each address.
3. Negotiates IKEv2 fragmentation (RFC 7383) and reads whole datagrams, so
   large `IKE_AUTH` responses with certificates are not lost to IP fragment
   drops.
4. Binds the socket to the network the line uses: Android's default (Wi-Fi,
   mobile data, Ethernet, or a VPN when one is up), or one kind the user
   picks per line. The line reconnects when that network changes.
5. Runs NAT keepalives and DPD on the same socket. A missed DPD reconnects
   instead of leaving a dead tunnel.
6. Offers the carrier's own proposal. Carriers accept the one IKE and ESP
   proposal their iPhone bundle lists (one algorithm per transform type), so
   each profile in `carrier_profiles.json` carries that proposal as
   `ike.exact_proposal` and `esp.exact_proposal`, imported from the bundles
   by `tools/import_carrier_bundles.py`. The handshake sends it alone; only
   if the ePDG answers `NO_PROPOSAL_CHOSEN` does it fall back to the broader
   offer (AES-CBC 128/256, SHA-256/SHA-1, DH 14), and to the SHA-1/DH 2 set
   for carriers without a profile. The stack speaks AES-CBC 128/256 with
   HMAC-SHA1-96 and HMAC-SHA2-256/384/512 (RFC 4868) for PRF and integrity
   on both SAs, and MODP groups 2, 5, 14, 15, 16 and 18 (RFC 3526). The
   bundle's `IPSecMTU`, NAT-T keepalive interval and dead-peer-detection
   switch come along as the profile's `tunnel` block.
7. Asks for P-CSCF addresses under the standard attribute IDs (20, 21) and
   the private ones many ePDGs still use (16384, 16386, 16389, 16390), as
   Apple's default carrier settings do.
8. Reads the ePDG's 3GPP reasons (TS 24.302): a refusal such as
   NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED stops the retries until the card is put
   in again or the user asks, and NETWORK_FAILURE pauses for the time the
   network names, or 30 minutes.
9. Keeps the IMSI off the air where the carrier's settings ask for it. The IKE
   identity is `anonymous@realm`, and the EAP identity carries the permanent
   username encrypted under the carrier's RSA key (OAEP, SHA-256), the
   scheme iPhones use (Apple patent US10833876B2, the WBA IMSI Privacy
   Protection). Which identity the network derives the EAP-AKA keys from is
   read off its own MAC, so no guess goes on the wire. A carrier that refuses
   the protected identity gets the clear one once more, so privacy never
   costs the line. T-Mobile US accepts it, for its MVNOs too.
10. Checks the ePDG's certificate for the carriers whose settings validate
   it, against the carrier's own CA from its bundle when there is one
   (Verizon's is private), and insists on a certificate where the carrier
   does.

### Inside the tunnel: an in-app IP stack

The inner address the ePDG assigns needs a TCP/IP stack. A `VpnService` TUN
would let the kernel do it, but it shows the VPN key icon, needs the VPN
consent dialog and displaces any VPN the user runs. Kernel IPsec needs a
privileged permission or root. So VowiDroid carries its own small stack:
IPv4 and IPv6, UDP, a compact TCP (one SIP connection out, one listener in)
and fragment reassembly. SIP is a few kilobytes on a low-loss path, so a
compact TCP is enough. ipsec-3gpp is applied on the same packet path.

### Calls

Calls use a **self-managed** Telecom `ConnectionService`. Android handles
audio focus and Bluetooth, car and headset routing; VowiDroid draws its own
call screens and runs SIP and RTP itself. `CallAudioEngine` connects
`AudioRecord` and `AudioTrack`, with the platform echo canceller, to the
call's RTP media at the codec's sample rate.

Hold and call waiting follow 3GPP TS 24.610 and TS 24.615:
- **Hold** is a re-INVITE whose SDP only sends (`a=sendonly`, or
  `a=inactive` when the other side already holds the call). Resume offers
  `a=sendrecv` again. A hold from the other side is answered with
  `a=recvonly` and shown on the call screen.
- **Call waiting:** a second call during a connected call rings with a 180
  that carries the call-waiting `Alert-Info`, and the call-waiting tone plays
  in the call. Answering it holds the first call. **Swap** holds the live
  call, then resumes the held one. A third call gets 486 Busy Here.
- While a call is on hold, VowiDroid sends a frame of silence every 160 ms
  (AMR `NO_DATA`), so the network does not see a dead media stream.
- The connections declare hold support, so Android can also hold a
  VowiDroid call when a call in another app is answered.

`CoreService` is a foreground service that holds a partial wake lock and a
Wi-Fi lock, so keepalives and re-registration keep running in Doze. Tunnel
sockets are bound to the Wi-Fi network; a Wi-Fi change reconnects the line.

## Building and testing

Requirements: JDK 17 and the Android SDK (platform 35, build tools 35). No
NDK is needed.

On macOS without Android Studio:

```bash
brew install openjdk@17
```

```bash
brew install --cask android-commandlinetools
```

```bash
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-35" "build-tools;35.0.0"
```

Then, with `JAVA_HOME` set to the JDK 17 home and `ANDROID_HOME` to the SDK
root (Gradle also needs `local.properties` with `sdk.dir=<SDK root>`):

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A signed release build needs a keystore and a `keystore.properties` file in
the project root. Both are ignored by git, and the properties file holds the
passwords in clear text, so keep it private (a leading `~/` in `storeFile`
means your home directory):

```bash
keytool -genkeypair -v -keystore ~/.android/vowidroid-release.jks -alias vowidroid -keyalg RSA -keysize 4096 -validity 10000
```

```
storeFile=~/.android/vowidroid-release.jks
storePassword=...
keyAlias=vowidroid
keyPassword=...
```

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Android
refuses to install it over a debug build because the signatures differ, so
uninstall the debug build first; that deletes the app's lines and messages.
Keep the keystore and its passwords safe: an update installs only over a
build signed with the same key.

Unit tests run on the JVM. They include byte-for-byte comparisons with
known-answer vectors, a fake ePDG that takes the tunnel up, a fake P-CSCF
that registers over ipsec-3gpp and exchanges texts and calls, a scripted USB
CCID reader with a virtual UICC, and the IP stack wired back to back:

```bash
./gradlew :app:testDebugUnitTest
```

## Carrier data

Each carrier's ePDG name, exact IKE and ESP proposals, tunnel settings,
identity-privacy and certificate rules and P-CSCF attribute IDs come from
its iPhone carrier bundle, imported into
`app/src/main/assets/carrier_profiles.json` (655 profiles). The proposals
are read from each bundle's `carrier.plist` (`TechSettings.IKE` and
`TechSettings.ChildSAs`). To refresh them from a bundles folder, run the
importer; it matches bundles to profiles by their SIM keys, leaves profiles
without a bundle alone, keeps the JSON's key order, and prints which bundles
it could not place:

```bash
python3 -I tools/import_carrier_bundles.py --bundles ../iPhone
```

The SMS centres are a separate, hand-kept table, `sms_centers.json`, since
the bundles carry none; see [The SMS centre](#the-sms-centre).

## Known gaps

- Tried live on T-Mobile US and on giffgaff (O2 UK, through a UK VPN): the
  tunnel and the IMS registration, through the phone's SIM and through a USB
  reader, outgoing calls with AMR audio, and texts in both directions. Not
  tried live yet: hold, call waiting, Add call and Merge, USSD and forwarding
  codes, bubbles, and calls in the system call history.
- The emergency address flow was run live against T-Mobile US: the SIM signed
  in, and T-Mobile reported the line entitled with an address on file and
  opened its address page. Changing an address through that page has not
  been tried.
- Call forwarding sends the standard codes over USSD, because there is no Ut
  (XCAP) client. Many IMS networks do not act on those codes.
- Manage conference has no Split for a private side call.
- No MMS, RCS or video calls.
- The IKE DEVICE_IDENTITY (IMEI) is not sent, because Android does not reveal
  the IMEI to apps.

## User agreement

On first launch VowiDroid shows its user agreement and starts nothing,
not even the background service, until it is accepted; Decline closes the
app. It covers acceptable use, emergency calls, what the app sends where
(only to the carrier, plus Google Public DNS for the gateway lookup), and
the warranty and liability disclaimers. The text is in
[`app/src/main/assets/agreement/`](app/src/main/assets/agreement/) in
English and Simplified Chinese, and stays readable under
Settings → Advanced → User agreement. Raising `UserAgreement.VERSION`
asks every user to accept it again.

## License

VowiDroid is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option)
any later version. It is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE)
for the full text.

Copyright © 2026 the VowiDroid developer.

The libraries it builds on (AndroidX and Jetpack Compose, Kotlin
coroutines, Gson) are under the Apache License 2.0, which the GPL v3 can
include.

## References

- **[VoCat](https://github.com/MengMengCode/VoCat)**, a web control panel
  and engineering toolkit for cellular modems, with WiFi Calling (the IKEv2/ePDG tunnel, EAP-AKA, IMS
  registration and IMS SMS) in Go. VowiDroid does the same work on a phone's
  own SIM, in an independent Kotlin implementation.
- **[fasferraz/SWu-IKEv2](https://github.com/fasferraz/SWu-IKEv2)**, Fabricio
  Ferraz's IKEv2/IPsec SWu client dialer in Python, with EAP-AKA through a
  USIM. The reference for the whole SWu side in user space, from the IKE
  exchanges through ESP to the inner address; his write-up is
  "[Untrusted Non-3GPP IP Access - SWu IKEv2 UE Emulator](https://fabricioapps.blogspot.com/2017/10/untrusted-non-3gpp-ip-access-swu-ikev2.html)"
  (2017).
- **[Learning VoWifi, VoLTE, and IMS: because I'm too Millennial to make a phone call](https://worthdoingbadly.com/vowifi/)**
  by zhuowei (2021), a hands-on account of reaching a carrier's ePDG with
  SWu-IKEv2 and talking to IMS from a laptop, with the pitfalls along the way.
- **Apple's iPhone carrier bundles**
  (`/System/Library/Carrier Bundles/iPhone/<Carrier>.bundle/carrier.plist`),
  from iOS 27.0.1. They are the source of the carrier profiles: which ePDG
  to name, the one IKE and ESP proposal each carrier accepts, tunnel
  settings, identity privacy, certificate checks and the P-CSCF attribute
  IDs.
- **AOSP's Dialer and Messaging apps** (Apache License 2.0)
- **Standards:** 3GPP TS 33.402 (SWu security), TS 24.302 (ePDG access and
  its reasons), TS 24.229 (IMS SIP), TS 24.341 (SMS over IMS), TS 24.011
  (SMS RP layer), TS 24.147 (conference), TS 24.610 and 24.615 (hold and
  call waiting), TS 24.390 (USSD), TS 24.607 (caller ID), GSMA TS.43
  (entitlement); IETF RFC 7296 (IKEv2), RFC 4187 (EAP-AKA), RFC 7383 (IKEv2
  fragmentation), RFC 3526 and RFC 4868 (MODP groups and HMAC-SHA2), RFC 3261
  and RFC 3329 (SIP and its security agreement), RFC 4867 (AMR over RTP),
  RFC 4733 (DTMF), RFC 4575 (conference events) and RFC 3842 (voicemail
  waiting).
