# Jukebox KMP - Architecture & Implementation Summary

## 1. PROJECT STRUCTURE & OVERVIEW

### Project Type
- **Framework**: Kotlin Multiplatform (KMP) with Compose Multiplatform
- **Targets**: Android, Desktop (JVM), and iOS
- **Purpose**: A cross-platform app for browsing a user's Discogs vinyl collection via OAuth

### Directory Structure
```
jukebox_kmp/
├── composeApp/
│   ├── src/
│   │   ├── commonMain/           # Shared code (all platforms)
│   │   ├── commonTest/           # Shared tests
│   │   ├── androidMain/          # Android-specific code
│   │   ├── desktopMain/          # JVM Desktop-specific code
│   │   ├── iosMain/              # iOS-specific code
│   │   └── desktopTest/          # Desktop platform tests
│   └── build.gradle.kts          # Main build configuration
├── build.gradle.kts              # Root build config (plugin management)
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml        # Centralized dependency versions
├── local.properties.template     # Discogs API credentials template
└── README.md
```

### Build System
- **Gradle 8.x** with Kotlin 2.3.20 and KMP plugin
- **JDK 25** (Liberica recommended, specified in `.sdkmanrc`)
- Android SDK 26 (minSdk), targets SDK 36
- Discogs credentials are injected via `local.properties` and never committed to git

---

## 2. KEY DEPENDENCIES & TECH STACK

### Core Libraries
| Purpose | Library | Version |
|---------|---------|---------|
| HTTP Client | Ktor (multiplatform) | 3.1.3 |
| Serialization | kotlinx-serialization-json | 1.8.1 |
| State Management | androidx.lifecycle (ViewModel) | 2.10.0 |
| UI Framework | Compose Multiplatform | 1.10.3 |
| Navigation | androidx.navigation-compose | 2.9.2 |
| Image Loading | Coil 3 | 3.1.0 |
| Coroutines | kotlinx-coroutines | 1.10.2 |
| Secure Storage | DataStore (Desktop/iOS), EncryptedSharedPreferences (Android) | 1.1.7 |

### Platform-Specific HTTP Engines
- **Android**: OkHttp (reliable, widely-tested)
- **Desktop (JVM)**: CIO (Coroutine I/O engine)
- **iOS**: Darwin (native HTTP stack)

### Testing
- Ktor MockEngine for API mocking
- Turbine for StateFlow testing
- KoTest for assertions
- JUnit 4 for JVM tests
- MockK for mocking

---

## 3. DISCOGS API INTEGRATION & AUTHENTICATION

### OAuth 1.0a PLAINTEXT Flow

The `DiscogsService` class implements a complete OAuth 1.0a PLAINTEXT authentication flow:

#### Step 1: Request Token (OAuth Step 1)
```kotlin
suspend fun startOAuthFlow(): String
```
- Makes a GET request to `https://api.discogs.com/oauth/request_token`
- Includes OAuth headers with nonce, timestamp, consumer key/secret
- Signature method: **PLAINTEXT** (signature = `consumerSecret & tokenSecret`)
- Callback: `oob` (out-of-band) — user receives PIN on Discogs website
- Returns: The authorization URL for the user to open in their browser

#### Step 2: Exchange Verifier for Access Token (OAuth Step 2)
```kotlin
suspend fun completeOAuthFlow(verifier: String): OAuthTokens
```
- Uses the PIN code user received from Discogs authorization page
- Makes POST to `https://api.discogs.com/oauth/access_token`
- Includes request token, timestamp, nonce, verifier in OAuth header
- Returns: `OAuthTokens(token, secret)` — persisted to secure storage

#### Step 3: Authenticated API Calls
```kotlin
suspend fun getCollection(
    username: String,
    page: Int = 1,
    perPage: Int = 25
): CollectionResult
```
- Fetches user's collection from `/users/{username}/collection/folders/0/releases`
- Query params: `page`, `per_page` (default 25), `sort=artist`, `sort_order=asc`
- Each request includes OAuth headers with access token & secret
- Returns paginated results with pagination metadata and collection items

#### Credential Management
- `setCredentials(token, secret)` — loads from secure storage after app restart
- `clearCredentials()` — used on disconnect
- `isAuthenticated` computed property checks for non-null tokens

### API Response Models
All responses are deserialized using `kotlinx-serialization`:

```kotlin
// Final model presented to UI
data class CollectionItem(
    val instanceId: Int,
    val id: Int,
    val title: String,
    val artists: List<String>,
    val formats: List<String>,
    val thumb: String?,        // Album artwork thumbnail URL
    val year: Int?,
    val label: String?,
)

// Discogs API response wrapper
data class DiscogsCollectionResponse(
    val pagination: DiscogsPaginationJson,
    val releases: List<DiscogsReleaseJson>,
)
```

---

## 4. DATA PERSISTENCE & STORAGE SOLUTION

### Architecture: Expect/Actual Pattern
The storage layer uses Kotlin's **expect/actual mechanism** for cross-platform support:

```kotlin
// commonMain/storage/SecureStorage.kt
expect class SecureStorage {
    suspend fun write(key: String, value: String)
    suspend fun read(key: String): String?
    suspend fun delete(key: String)
}

object StorageKeys {
    const val ACCESS_TOKEN = "discogs_access_token"
    const val ACCESS_TOKEN_SECRET = "discogs_access_token_secret"
    const val USERNAME = "discogs_username"
    fun collectionCache(username: String) = "collection_cache_$username"
}
```

### Platform Implementations

#### Android: EncryptedSharedPreferences
- **Location**: `androidMain/storage/SecureStorage.kt`
- **Security**: AES256-GCM encryption (Jetpack Security)
- **MasterKey**: AES256_GCM scheme, managed by Android Keystore
- **File**: Encrypted prefs file on device storage
- **Benefit**: Hardware-backed security on supported devices

```kotlin
actual class SecureStorage(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jukebox_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        // ...
    )
}
```

#### Desktop (JVM): DataStore Preferences
- **Location**: `desktopMain/storage/SecureStorage.kt`
- **Storage**: Plain file (`~/.jukebox/jukebox_prefs.preferences_pb`)
- **Format**: Protocol Buffers (binary, but not encrypted)
- **Note**: For production, replace with OS keyring (libsecret on Linux, Keychain on macOS, Credential Manager on Windows)

```kotlin
actual class SecureStorage(private val dataStore: DataStore<Preferences>) {
    actual suspend fun write(key: String, value: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = value }
    }
}
```

#### iOS: DataStore Preferences (Simplified)
- **Location**: `iosMain/storage/SecureStorage.kt`
- **Storage**: Similar to Desktop (not encrypted by default)
- **Production Note**: Should use Keychain via Security framework or KeychainSwift package

### Data Persistence Strategy

#### OAuth Tokens
- **Keys**: `discogs_access_token`, `discogs_access_token_secret`, `discogs_username`
- **Flow**: Stored after successful OAuth completion, restored on app launch
- **Lifecycle**: Cleared on logout or manual disconnect

#### Collection Cache
- **Key**: `collection_cache_{username}` (per-user cache)
- **Content**: Full JSON serialization of `List<CollectionItem>`
- **Format**: kotlinx-serialization JSON
- **Strategy**:
  1. On app launch, restore cache to show collection immediately (instant UI response)
  2. Simultaneously, fetch full collection from Discogs API in background
  3. Update UI with fresh data as pages are fetched
  4. Persist complete collection when all pages loaded
  5. On refresh, repeat steps 2-4

---

## 5. COLLECTION DATA LOADING & HANDLING

### CollectionViewModel Architecture

**Purpose**: Manages paginated loading, caching, and filtering of user's Discogs collection.

```kotlin
class CollectionViewModel(
    private val service: DiscogsService,
    private val username: String,
    private val storage: SecureStorage,
    private val perPage: Int = 50,
) : ViewModel()
```

**State Management** via `StateFlow`:
```kotlin
data class CollectionUiState(
    val items: List<CollectionItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    val selectedFilter: Any? = null,              // Letter A-Z, NUMBERS, SPECIAL, or null
    val randomItem: CollectionItem? = null,       // For random-pick overlay
    val syncProgress: Float? = null,              // 0f-1f, null when done
)
```

### Data Flow

#### On ViewModel Initialization
```kotlin
init {
    viewModelScope.launch {
        restoreFromCache()      // Show cached data instantly
        syncAllPages()          // Fetch fresh data in background
    }
}
```

#### 1. Restore From Cache
```kotlin
private suspend fun restoreFromCache() {
    val json = readCache() ?: return
    val cached: List<CollectionItem> = Json.decodeFromString(json)
    if (cached.isNotEmpty()) {
        _uiState.update { state ->
            state.copy(
                items = cached,
                totalItems = cached.size,
                currentPage = 1,
                totalPages = 1,  // Mark as "loaded"
            )
        }
    }
}
```

#### 2. Full Sync (Background Fetch)
```kotlin
private suspend fun syncAllPages() {
    var page = 1
    var totalPages = 1
    val allItems = mutableListOf<CollectionItem>()
    
    _uiState.update { it.copy(syncProgress = 0f) }
    
    while (page <= totalPages) {
        val result = service.getCollection(username, page, perPage=50)
        totalPages = result.totalPages
        allItems += result.items
        
        // Update UI with progress
        val progress = page.toFloat() / totalPages
        _uiState.update {
            it.copy(
                items = allItems.toList(),
                currentPage = page,
                totalPages = totalPages,
                syncProgress = if (page < totalPages) progress else null,
            )
        }
        page++
    }
    
    // Persist complete collection
    persistToCache(allItems)
}
```

#### 3. Persist to Cache
```kotlin
private suspend fun persistToCache(items: List<CollectionItem>) {
    val json = Json.encodeToString(items)
    storage.write(StorageKeys.collectionCache(username), json)
}
```

### Collection Features

#### Filtering by Letter
```kotlin
fun filterLetter(filter: Any?) {
    // filter can be:
    // - null (show all)
    // - Char 'A'-'Z' (filter by first letter of artist)
    // - LetterFilter.NUMBERS (artists starting with digit)
    // - LetterFilter.SPECIAL (other symbols/punctuation)
    _uiState.update { it.copy(selectedFilter = filter) }
}

val filteredItems: List<CollectionItem>
    get() {
        if (selectedFilter == null) return items
        return items.filter { item ->
            val firstChar = item.artists.firstOrNull()
                ?.trimStart()?.firstOrNull()
                ?: item.title.firstOrNull()
            when (selectedFilter) {
                is Char -> firstChar.uppercaseChar() == selectedFilter
                LetterFilter.NUMBERS -> firstChar.isDigit()
                LetterFilter.SPECIAL -> !firstChar.isLetter() && !firstChar.isDigit()
            }
        }
    }
```

#### Random Pick Feature
```kotlin
fun pickRandom() {
    val items = _uiState.value.items
    if (items.isEmpty()) return
    _uiState.update { it.copy(randomItem = items.random()) }
}

fun dismissRandom() {
    _uiState.update { it.copy(randomItem = null) }
}
```

#### Refresh
```kotlin
fun refresh() {
    _uiState.update { state ->
        state.copy(
            items = state.items,  // Keep cached items visible
            totalItems = state.totalItems,
        )
    }
    viewModelScope.launch { syncAllPages() }
}
```

---

## 6. MAIN SCREENS & UI COMPONENTS

### App Navigation Architecture
```kotlin
@Serializable data object HomeRoute
@Serializable data class CollectionRoute(val username: String)

// NavHost routes between screens
NavHost(navController, startDestination = HomeRoute) {
    composable<HomeRoute> { HomeScreen(...) }
    composable<CollectionRoute> { CollectionScreen(...) }
}
```

### HomeScreen
**File**: `commonMain/ui/screen/HomeScreen.kt`

**States & UI**:
1. **Unauthenticated**
   - Album icon, welcome message
   - "Connect to Discogs" button
   - Opens OAuth flow

2. **Authenticating**
   - Loading spinner
   - PIN entry dialog (AlertDialog)
   - User pastes verifier from Discogs authorization page

3. **Authenticated**
   - Green checkmark icon
   - "Connected as {username}" message
   - "View My Collection" button (navigates to CollectionScreen)
   - "Disconnect" button (clears tokens & caches)

4. **Error**
   - Error icon
   - Error message display
   - "Try Again" button

### CollectionScreen
**File**: `commonMain/ui/screen/CollectionScreen.kt` (636 lines)

**Key Components**:

#### Top App Bar
- Back button (navigates back to HomeScreen)
- Title: collection stats (e.g., "200 Items")

#### Pull-to-Refresh
```kotlin
PullToRefreshBox(
    isRefreshing = uiState.syncProgress != null,
    onRefresh = { viewModel.refresh() },
) { ... }
```

#### Main List
```kotlin
LazyColumn {
    itemsIndexed(filteredItems) { index, item ->
        CollectionItemRow(item)
    }
}
```

Each item displays:
- Album thumbnail (Coil image loading)
- Title
- Artist(s)
- Year
- Format (Vinyl, CD, etc.)
- Label

#### Alphabet Filter Bar (Bottom)
```kotlin
FlowRow {
    // Letters A-Z
    ('A'..'Z').forEach { letter ->
        FilterChip(
            selected = selectedFilter == letter,
            onClick = { viewModel.filterLetter(letter) }
        )
    }
    
    // Numbers
    FilterChip(
        selected = selectedFilter == LetterFilter.NUMBERS,
        onClick = { viewModel.filterLetter(LetterFilter.NUMBERS) }
    )
    
    // Special characters
    FilterChip(
        selected = selectedFilter == LetterFilter.SPECIAL,
        onClick = { viewModel.filterLetter(LetterFilter.SPECIAL) }
    )
}
```

#### Random Pick Button
```kotlin
FloatingActionButton(
    onClick = { viewModel.pickRandom() }
) {
    Icon(Icons.Default.Casino, contentDescription = null)
}
```

#### Random Pick Overlay
When `randomItem` is set, displays full-screen modal with:
- Large album artwork
- Title
- Artist(s)
- Year, format, label
- "Close" button to dismiss

#### Sync Progress Indicator
```kotlin
uiState.syncProgress?.let { progress ->
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth()
    )
}
```

#### States & Feedback
- Loading spinner when `isLoading`
- Error message if fetch fails
- Empty state when no items match filter
- Infinite scroll trigger (legacy, now handled by full background sync)

---

## 7. VIEWMODEL ARCHITECTURE

### DiscogsAuthViewModel
**File**: `commonMain/viewmodel/DiscogsAuthViewModel.kt`

**Responsibilities**:
- OAuth lifecycle management
- Token persistence (read/write from SecureStorage)
- Session restoration on app launch
- State transitions via `StateFlow<AuthState>`

**State Machine**:
```kotlin
sealed interface AuthState {
    object Unauthenticated : AuthState
    object Authenticating : AuthState
    data class Authenticated(val username: String) : AuthState
    data class Error(val message: String) : AuthState
}
```

**Key Methods**:
```kotlin
fun restoreSession()            // Called once on app startup
fun connectToDiscogs()          // Step 1: start OAuth flow
fun submitVerifier(pin: String) // Step 2: exchange verifier for tokens
fun disconnect()                // Clear tokens & return to Unauthenticated
```

### CollectionViewModel
**File**: `commonMain/viewmodel/CollectionViewModel.kt`

**Responsibilities**:
- Paginated collection fetching & caching
- Letter-based filtering
- Random item selection
- Sync progress tracking

**Exposed State**:
```kotlin
val uiState: StateFlow<CollectionUiState>
```

**Key Methods**:
```kotlin
fun refresh()                   // Manual full refresh
fun filterLetter(filter: Any?)  // Update letter filter
fun pickRandom()                // Select random item
fun dismissRandom()             // Close random overlay
fun loadNextPage()              // Legacy (no-op, replaced by syncAllPages)
```

---

## 8. PLATFORM-SPECIFIC ENTRY POINTS

### Android: MainActivity
**File**: `androidMain/kotlin/com/joergi/jukebox/MainActivity.kt`

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val httpClient = HttpClient(OkHttp) { /* config */ }
        val service = DiscogsService(
            BuildConfig.DISCOGS_CONSUMER_KEY,
            BuildConfig.DISCOGS_CONSUMER_SECRET,
            httpClient,
        )
        val storage = SecureStorage(applicationContext)
        
        val openUrl: suspend (String) -> Unit = { url ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        
        setContent {
            App(service, storage, openUrl)
        }
    }
}
```

### Desktop: Main.kt
**File**: `desktopMain/kotlin/com/joergi/jukebox/Main.kt`

```kotlin
fun main() = application {
    // Read local.properties for credentials
    val localProps = Properties()
    val consumerKey = localProps.getProperty("discogs.consumerKey", "")
    val consumerSecret = localProps.getProperty("discogs.consumerSecret", "")
    
    // Initialize DataStore for token persistence
    val dataStoreDir = File(System.getProperty("user.home"), ".jukebox")
    val dataStore = PreferenceDataStoreFactory.createWithPath(
        produceFile = { File(dataStoreDir, "jukebox_prefs.preferences_pb").absolutePath.toPath() }
    )
    
    val httpClient = HttpClient(CIO) { /* config */ }
    val service = DiscogsService(consumerKey, consumerSecret, httpClient)
    val storage = SecureStorage(dataStore)
    
    val openUrl: suspend (String) -> Unit = { url ->
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            // Fallback: xdg-open, open, or cmd depending on OS
        }
    }
    
    Window(...) {
        App(service, storage, openUrl)
    }
}
```

---

## 9. KEY ARCHITECTURAL PATTERNS

### Dependency Injection
All dependencies are injected at platform entry points:
- `DiscogsService` (with HTTP client)
- `SecureStorage` (with platform-specific backend)
- `openUrl` lambda (browser launcher)

### Expect/Actual for Cross-Platform Code
```kotlin
// commonMain
expect fun currentTimeMillis(): Long

// androidMain, desktopMain, iosMain
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
```

### MVVM with StateFlow
- ViewModels expose immutable `StateFlow<UiState>`
- UI collects state with `collectAsStateWithLifecycle()`
- All state mutations go through `_state.update { ... }`

### Coroutine-Based Async
- All network calls are suspending functions
- Collections scoped to `viewModelScope`
- Platform-specific dispatchers (Dispatchers.Main, Dispatchers.IO)

### OAuth 1.0a PLAINTEXT
- No signature signing library needed (PLAINTEXT = concat of secrets)
- Out-of-band (PIN) flow for CLI/desktop/mobile browser limitations
- Tokens stored securely and restored on app restart

---

## 10. TESTING STRATEGY

### Unit Tests
- **DiscogsService tests**: Mock HTTP engine, verify OAuth flow
- **CollectionViewModel tests**: In-memory cache, mock service
- **Auth ViewModel tests**: State transitions, credential flow

### Mock Engine Usage
```kotlin
val mockEngine = MockEngine { request ->
    respond(
        content = jsonResponse,
        status = HttpStatusCode.OK,
        headers = jsonHeaders(),
    )
}
val httpClient = HttpClient(mockEngine)
val service = DiscogsService("key", "secret", httpClient)
```

### StateFlow Testing with Turbine
```kotlin
viewModel.uiState.test {
    awaitItem() // Initial state
    awaitItem() // After cache load
    awaitItem() // After first page fetch
    // ...
}
```

---

## 11. CURRENT IMPLEMENTATION SUMMARY

| Aspect | Solution | Notes |
|--------|----------|-------|
| **OAuth** | Discogs OAuth 1.0a PLAINTEXT | PIN-based flow, tokens stored securely |
| **HTTP** | Ktor with platform engines | OkHttp (Android), CIO (Desktop), Darwin (iOS) |
| **Serialization** | kotlinx-serialization | Type-safe, compile-time checks |
| **Storage** | Encrypted SharedPrefs (Android), DataStore (Desktop), Keychain (iOS) | Secure token storage |
| **Cache** | JSON file via DataStore/Storage | Per-user collection cache |
| **UI Framework** | Compose Multiplatform | Single codebase, reactive state |
| **State Management** | ViewModel + StateFlow | Lifecycle-aware, coroutine-scoped |
| **Image Loading** | Coil 3 with Ktor network | Cross-platform, async image display |
| **Navigation** | Jetpack Navigation Compose | Type-safe route serialization |
| **Testing** | MockEngine + Turbine | Network tests without actual API calls |

