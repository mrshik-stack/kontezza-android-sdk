# Kontezza Android SDK

Contextual advertising SDK for Android applications with AI chat experiences.

## Requirements

- Android 6.0+ (API 23)
- Kotlin 1.9+
- Kotlin Coroutines

## Installation

### Gradle (JitPack)

Add JitPack repository to your root `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your module `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.mrshik-stack:kontezza-android-sdk:1.1.1")
}
```

### Manual (.aar)

Copy the `.aar` file to your `libs/` directory and add:
```kotlin
dependencies {
    implementation(files("libs/kontezza-sdk-1.1.1.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

## Quick Start

```kotlin
import com.kontezza.sdk.KontezzaSDK
import com.kontezza.sdk.KontezzaConfig

// 1. Initialize (once, e.g. in Application.onCreate)
//    Pass applicationContext so the SDK can persist its session ID
//    across app launches (required for frequency capping to work across restarts).
val kontezza = KontezzaSDK(KontezzaConfig(
    apiKey = "ak_YOUR_API_KEY",
    context = applicationContext,
))

// 2. Get an ad based on conversation context (from a coroutine)
val ad = kontezza.getVectorAd(
    userMessage = "Хочу заказать доставку в Москву",
    aiResponse = "Могу помочь с выбором службы доставки..."
)

// 3. Display the ad
ad?.let {
    titleView.text = it.title
    descriptionView.text = it.text
    // ... render in your UI

    // 4. Track click when user taps
    adView.setOnClickListener {
        kontezza.trackClick(ad)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.url)))
    }
}
```

## API Reference

### Initialization

```kotlin
// Full configuration
val kontezza = KontezzaSDK(KontezzaConfig(
    apiKey = "ak_YOUR_API_KEY",
    baseUrl = "https://api.kontezza.com",  // optional, this is the default
    debug = true,                           // optional, enables Logcat logging
    context = applicationContext,           // optional but recommended:
                                            //  persists session ID across launches
))

// Or just the API key (session ID will be regenerated each app start)
val kontezza = KontezzaSDK(apiKey = "ak_YOUR_API_KEY")
```

### Getting Ads

All methods are `suspend` functions — call them from a coroutine scope.
They return `KontezzaAd?` (null = no ad matched).

#### Vector Ad (fast contextual match)
```kotlin
val ad = kontezza.getVectorAd(
    userMessage = userMessage,
    aiResponse = aiResponse
)
```

#### Relevant Ad (deep contextual match, higher quality)
```kotlin
val ad = kontezza.getRelevantAd(
    userMessage = userMessage,
    aiResponse = aiResponse
)
```

#### Banner Ad
```kotlin
val ad = kontezza.getBannerAd()
```

### Tracking

Impressions are tracked automatically when you call `get*Ad()`.

```kotlin
// Track click — call when user taps the ad
kontezza.trackClick(ad)
```

### Campaign Status

```kotlin
// Check if campaigns are active before fetching
if (kontezza.hasActiveCampaign(KontezzaAd.AdType.VECTOR)) {
    val ad = kontezza.getVectorAd(...)
}

// Force refresh campaign cache
kontezza.refreshCampaigns()

// Get publisher design colors
val colors = kontezza.design  // .bgColor, .borderColor
```

### KontezzaAd Properties

| Property | Type | Description |
|---|---|---|
| `id` | `String` | Unique ad identifier |
| `campaignId` | `String` | Campaign the ad belongs to |
| `trackingToken` | `String?` | HMAC token for tracking |
| `title` | `String` | Ad headline |
| `text` | `String` | Ad description |
| `url` | `String` | Click-through URL |
| `imageUrl` | `String` | Ad image URL |
| `category` | `String` | Ad category |
| `adType` | `RELEVANT / VECTOR / BANNER` | How the ad was matched |
| `design` | `AdDesign` | Publisher's color scheme |

## What the SDK Handles Automatically

You only need to provide the conversation text and render the returned ad —
everything else is managed inside the SDK:

- **Session management** — a stable per-install session ID is generated on
  first use. When `context` is passed to `KontezzaConfig`, it persists across
  app launches via `SharedPreferences`.
- **Geo-targeting** — campaign geo filters are resolved server-side from the
  user's IP. No device permissions or setup required.
- **Frequency capping & deduplication** — enforced on the server based on the
  session ID + IP.
- **Impression tracking** — sent automatically on every successful
  `get*Ad()` call. Call `trackClick(ad)` when the user taps the ad.
- **Campaign state cache** — active campaigns are cached for 30s so `get*Ad`
  returns instantly when nothing is running.
- **Network retries** — automatic retry with exponential backoff on transient
  failures and 429 rate limits.

## Integration Example: Jetpack Compose

```kotlin
import androidx.compose.runtime.*
import com.kontezza.sdk.KontezzaSDK

@Composable
fun ChatScreen() {
    val kontezza = remember { KontezzaSDK(apiKey = "ak_YOUR_KEY") }
    var currentAd by remember { mutableStateOf<KontezzaAd?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column {
        // ... your chat UI ...

        currentAd?.let { ad ->
            AdBanner(
                ad = ad,
                onTap = {
                    kontezza.trackClick(ad)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(ad.url))
                    )
                }
            )
        }
    }

    // Call this after each AI response
    fun onAIResponse(userMessage: String, aiResponse: String) {
        scope.launch {
            currentAd = kontezza.getVectorAd(userMessage, aiResponse)
        }
    }
}

@Composable
fun AdBanner(ad: KontezzaAd, onTap: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = Color(android.graphics.Color.parseColor(ad.design.bgColor))
        ),
        border = BorderStroke(2.dp, Color(android.graphics.Color.parseColor(ad.design.borderColor)))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Реклама", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(ad.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(ad.text, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Подробнее →", color = MaterialTheme.colorScheme.primary)
        }
    }
}
```

## Integration Example: XML Views

```kotlin
import com.kontezza.sdk.KontezzaSDK
import androidx.lifecycle.lifecycleScope

class ChatActivity : AppCompatActivity() {
    private val kontezza = KontezzaSDK(apiKey = "ak_YOUR_KEY")

    fun onAIResponse(userMessage: String, aiResponse: String) {
        lifecycleScope.launch {
            val ad = kontezza.getVectorAd(userMessage, aiResponse) ?: return@launch

            findViewById<TextView>(R.id.adTitle).text = ad.title
            findViewById<TextView>(R.id.adText).text = ad.text
            findViewById<View>(R.id.adContainer).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    kontezza.trackClick(ad)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.url)))
                }
            }
        }
    }
}
```

## ProGuard / R8

If you use code shrinking, add:
```proguard
-keep class com.kontezza.sdk.** { *; }
-keepclassmembers class com.kontezza.sdk.** { *; }
```

## Thread Safety

`KontezzaSDK` is safe to call from any coroutine dispatcher. Network calls use OkHttp's async dispatch internally.

## License

MIT
