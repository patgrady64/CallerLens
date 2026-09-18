# CallerLens

**Know who’s calling — and how often.**

CallerLens is an Android default-phone-app MVP that puts objective call history directly on the incoming-call screen. Instead of automatically deciding that a caller is spam, CallerLens shows how often the number has called, when it last called, and how those previous calls ended so the user can decide what to do.

## MVP features

- Requests Android's **default Phone/Dialer role** using `RoleManager.ROLE_DIALER`.
- Implements an `InCallService` and replaces the normal incoming/ongoing call UI while CallerLens is the default phone app.
- Shows the best caller name Android provides, followed by the phone number.
- Reads the local call log and shows a 7-day frequency summary during an incoming call.
- Shows previous **missed**, **rejected**, and **answered** counts.
- Provides explicit user-controlled actions: **Answer**, **Block Number**, **Send to Voicemail**, and **Reject**.
- Never auto-blocks a number based on frequency.
- Adds a user-selected blocked number to Android's system blocked-number provider when supported.
- Includes a simple outgoing dial field and uses `TelecomManager.placeCall()`.
- Shows recent incoming callers and their 30-day call counts on the home screen.
- Includes a minimal ongoing-call screen with mute, speaker, and hang-up controls.

## Project requirements

- Android Studio **Quail 4 / 2026.1.4** or compatible
- Android Gradle Plugin **9.3.0**
- Gradle **9.5.0**
- Kotlin **2.4.10** via AGP 9 built-in Kotlin
- JDK **17+**
- `compileSdk 36.1` (API 36 with minor API level 1)
- `targetSdk 36`
- `minSdk 29`
- A real Android phone with cellular calling is strongly recommended for testing. Emulator telephony behavior is not a substitute for carrier testing.

## First run

1. Open the project in Android Studio.
2. Let Gradle sync and install any requested Android SDK components.
3. Build and install the `app` module on a real Android phone.
4. Open CallerLens.
5. Tap **Set as default phone app**.
6. Android will show a system dialog listing eligible phone apps. Choose **CallerLens** and confirm.
7. Back in CallerLens, tap **Grant permissions** and allow call history, contacts, phone calls, and notifications when Android asks.
8. Place a test call to the device from another phone.

## Making CallerLens the default calls app

CallerLens does not silently make itself the default. Android requires the user to approve that role.

The app requests it with:

```kotlin
val roleManager = getSystemService(RoleManager::class.java)
val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
roleLauncher.launch(intent)
```

When the system dialog appears, select **CallerLens**. On most Android versions you can also change this later through:

**Settings → Apps → Default apps → Phone app → CallerLens**

The exact wording can vary by phone manufacturer.

CallerLens must remain eligible for the dialer role. Android requires a default phone app to handle `ACTION_DIAL` and provide both incoming and ongoing call UI through `InCallService`. This MVP includes those pieces.

## Incoming-call experience

A typical incoming call is intended to look like:

```text
John Smith
(410) 555-1234

This number has called you 12 times in the last 7 days

Last call: Today at 8:42 AM

9 missed • 2 rejected • 1 answered

[ ANSWER ]

[ BLOCK ]   [ VOICEMAIL ]   [ REJECT ]
```

The frequency summary is computed locally from `CallLog.Calls`. CallerLens does not upload the user's call history.

## What the buttons do

### Answer

Uses `Call.answer(...)` through the current `InCallService` call.

### Block Number

Adds the number to Android's `BlockedNumberContract` provider and rejects the current call. Android restricts that provider; the default phone app is one of the app types allowed to read/write it. CallerLens first checks `BlockedNumberContract.canCurrentUserBlockNumbers()`.

This is still **manual blocking**: CallerLens never blocks a caller merely because it has called frequently. The user must press **Block Number**.

### Send to Voicemail

For ordinary carrier calls, Android does not expose a universal API that means "force this exact PSTN call into voicemail." The MVP uses `Call.reject(false, null)`, which normally causes the carrier to route the declined call to voicemail when voicemail is configured.

Because the carrier controls the network-side result, this behavior must be tested with the target phone/carrier. On a carrier without voicemail or with unusual call handling, rejecting a call may simply end it.

### Reject

Uses the Telecom reject API with `REJECT_REASON_DECLINED` on Android 11+ (and the legacy reject call on Android 10). This records the action semantically as a user decline when supported.

Because both **Send to Voicemail** and **Reject** ultimately involve declining an incoming cellular call, some carriers may treat them identically. They are separate in the UI now so carrier/device behavior can be tested before deciding whether to keep both in the production version.

## Incoming-call notification and lock screen

Android expects a default phone app to post an incoming-call notification and use a full-screen intent for the lock-screen call UI. CallerLens declares `USE_FULL_SCREEN_INTENT` and posts a high-importance call notification.

On Android 14+, the user/system can restrict full-screen-intent access. If a device does not open the full incoming screen over the lock screen, check the phone's **Special app access / Full-screen notifications** setting for CallerLens. The exact settings path varies by manufacturer.

## Permissions

CallerLens currently asks for:

- `READ_CALL_LOG` — call frequency and previous outcomes.
- `READ_CONTACTS` — contact display names supplied to the in-call UI.
- `CALL_PHONE` — placing outgoing calls from CallerLens.
- `POST_NOTIFICATIONS` — incoming/ongoing call notification on Android 13+.
- `USE_FULL_SCREEN_INTENT` — incoming-call full-screen UI when appropriate.

`BIND_INCALL_SERVICE` is declared on the `InCallService` component and is a system binding permission, not a normal runtime prompt.

## Important Android behavior

### Emergency calls

Android always retains special handling for emergency calls. A third-party default dialer should place calls using `TelecomManager.placeCall()`, which this MVP does, so Android can route emergency calls through the system's required emergency calling path.

### Caller names

CallerLens prefers Android's contact-derived display name when available, then the caller display name supplied by the connection/network, then `Unknown Caller`. A network-supplied name is carrier/device dependent and is not guaranteed for every number.

### Call-log timing

The currently ringing call normally is not yet a completed call-log entry. The displayed 7-day counts therefore describe **previous logged calls from that number**. After the current call finishes, it becomes part of future counts.

## Architecture

```text
MainActivity
├── Default-dialer role request
├── Runtime permission request
├── Simple dial field
└── Recent incoming caller list

CallerLensInCallService
├── Receives calls from Android Telecom
├── Maintains the current Call through CallSession
├── Posts the incoming/ongoing call notification
└── Opens CallActivity for the call UI

CallActivity
├── Caller name + number
├── 7-day CallLog summary
├── Answer / Block / Voicemail / Reject
└── Ongoing mute / speaker / hang up controls

CallLogRepository
├── Queries CallLog.Calls
├── Matches number variants
└── Aggregates missed/rejected/answered/blocked calls

CallSession
├── Wraps android.telecom.Call
├── Exposes current UI state
├── Executes call actions
└── Writes user-selected blocks to BlockedNumberContract
```

## MVP limitations / next work

This is deliberately an MVP. Before a Play Store release, the next priorities should be:

1. Test incoming, outgoing, answered, rejected, missed, blocked, Bluetooth, headset, speaker, and lock-screen behavior on multiple physical devices.
2. Add a real dial pad instead of only a number field.
3. Add contact photos and a contact-detail screen.
4. Add multi-call / call-waiting / hold / swap / conference support.
5. Add DTMF keypad controls during active calls.
6. Add a proper call-history detail screen for each number.
7. Add unblock/manage-blocked-numbers UI.
8. Add full-screen-intent permission diagnostics for Android 14+.
9. Add dual-SIM handling and phone-account selection.
10. Add accessibility labels, larger-text testing, TalkBack testing, and landscape layouts.
11. Add instrumentation tests around Telecom behavior where practical.
12. Review current Google Play call-log/default-handler permission policy before publishing.

## Privacy direction

CallerLens is designed so its core feature can work entirely on-device. The MVP does not include analytics, advertising SDKs, caller databases, or call-history uploads.

## Package

`com.pgdevhouse.callerlens`

## License

No license has been selected yet. Add one before publishing the source publicly if you want other people to have explicit reuse rights.

## Android 36.1 dependency compatibility

This MVP is intentionally compiled with Android API 36.1. The dependency graph is pinned to AndroidX Core 1.18.0 and Lifecycle 2.10.0 because Core 1.19.0 and Lifecycle 2.11.0 require compileSdk 37 or newer. If Android Studio previously cached a failed dependency resolution, use **File > Sync Project with Gradle Files**, then **Build > Clean Project** and rebuild.

## Android SDK required for this build

This project compiles against **Android API 37.1** while keeping `targetSdk = 36`.

If Android Studio says an SDK platform is missing:

1. Open **Tools > SDK Manager**.
2. On **SDK Platforms**, enable **Show Package Details** if needed.
3. Install the **Android API 37 / 37.1 SDK Platform** available in your Android Studio installation.
4. Apply the changes and let the SDK finish installing.
5. Return to the project and choose **File > Sync Project with Gradle Files**.
6. Then use **Build > Clean Project** and **Build > Make Project**.

The API 37 compile SDK is needed because the current AndroidX Core 1.19 and Lifecycle 2.11 artifacts declare API 37 as their minimum compile SDK. `targetSdk` remains 36, so merely compiling against 37.1 does not opt CallerLens into Android 17 runtime behavior.
