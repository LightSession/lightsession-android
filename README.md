# LightSession Android

Session replay, a map of the screens an app actually has, and what its network did — from an SDK
you add in one call.

| Package | Maven Central | Minimum Android API |
| --- | --- | --- |
| `lightsession-android` | [![Maven Central](https://img.shields.io/maven-central/v/io.lightsession/lightsession-android?style=for-the-badge&color=green)](https://central.sonatype.com/artifact/io.lightsession/lightsession-android) | 26 |

## Installing

```kotlin
dependencies {
    implementation("io.lightsession:lightsession-android:0.29.0")
}
```

API 26 is a floor rather than a preference: the SDK reads window types that older releases do not
expose, and a library's `minSdk` is a ceiling on everyone who consumes it, so it is kept as low as
the code allows.

## Starting it

One call, in `Application.onCreate`, and no screen in the app has to know the SDK exists —
`Activity` lifecycle is hooked once and every screen the app already has is mapped without a line
of code in it.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LightSession.getInstance().init(
            this,
            LightSessionConfig(
                apiKey = BuildConfig.LIGHTSESSION_KEY,
                ingestUrl = "https://ingest.example.com",
                apiUrl = "https://api.example.com",
            ),
        )
    }
}
```

The key belongs outside version control — read it from `BuildConfig` or a properties file that is
not committed. It is a write-only credential and it ships inside the APK either way, but a key in a
public repository is a key anyone can point at your project.

A Compose app that navigates with `NavHost` needs nothing extra; destinations are named from the
route. An app that names its own screens can say so:

```kotlin
LightSession.getInstance().setScreen("checkout")
```

## Recording the network

Off by default, and the only setting that is. Everything else the SDK does, it does to itself;
this one sits in the path of the app's own requests, so nobody gets it without asking twice — once
for the flag, once for the interceptor.

```kotlin
LightSession.getInstance().init(
    this,
    LightSessionConfig(apiKey = …, ingestUrl = …, apiUrl = …, captureNetwork = true),
)

val client = OkHttpClient.Builder()
    .addInterceptor(LightSessionInterceptor())
    .build()
```

Bodies, headers and query strings are never captured, on any setting — there is no field for them.
Paths arrive with their dynamic segments already collapsed on the device: `/v1/orders/{id}`, never
a real id.

For a client that is not OkHttp, report a request directly:

```kotlin
LightSession.recordRequest(
    method = "POST",
    url = "https://api.example.com/v1/orders/84321/items",
    statusCode = 201,
    durationMs = 118,
)
```

## Sampling

`networkSampleRate` defaults to `1.0` — everything. The unit is the **session**, not the request:
a coin per request would turn a screen that fires six calls at once into one recorded call, and a
reader would conclude the screen makes one request. Failures are recorded regardless, marked as
standing for no traffic, so a rare one can still be opened and watched without moving any rate or
percentile.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
