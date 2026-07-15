# YggdrasilManager UX and App Hide DBG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an informational Root warning, a device-rich manager home screen, explicit child-screen navigation, and searchable per-App Hide DBG controls backed by a read-only-at-startup configuration snapshot.

**Architecture:** Keep the existing single-activity Compose/MVVM structure. Centralize route chrome, isolate platform reads behind small providers, initialize an app-private Hide DBG configuration store and process-scoped mutation coordinator in `Application.onCreate()`, map installed packages to UID-based state, and route every explicit KPM mutation through that coordinator so route teardown cannot cancel result handling.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation Compose, ViewModel/StateFlow, libsu, Android PackageManager/SharedPreferences, kotlinx-coroutines-test, MockK, JUnit 4, Gradle/AGP, JNI/CMake.

---

**Design reference:** `docs/superpowers/specs/2026-07-14-manager-ux-and-app-hide-dbg-design.md`

**Baseline:** `./gradlew.bat :app:testDebugUnitTest` passed on 2026-07-15. `adb devices` reported no connected device, so all state and presentation decisions below have JVM coverage; final on-device checks remain a separate acceptance step.

## File Map

**Navigation and theme:**

- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/AppDestination.kt`: route metadata and top-level destinations.
- Delete `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/BottomNavItem.kt`: replaced by `AppDestination`.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/MainActivity.kt`: route-aware Top App Bar, back action, and bottom navigation.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/NavGraph.kt`: use centralized routes.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/features/FeaturesScreen.kt`: use destination metadata and remove duplicate page heading.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Color.kt`: approved green/neutral/error palette.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Shape.kt`: radius cap of 8dp.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Theme.kt`: fixed light/dark schemes without dynamic color.

**Home:**

- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeModels.kt`: statuses, device model, and display mappings.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/DeviceInfoProvider.kt`: Android/BuildConfig adapter.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/RootStatusSource.kt`: suspend libsu adapter.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeViewModel.kt`: independent startup checks and one-time warning state.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeScreen.kt`: status dashboard, device rows, and Root dialog.

**Hide DBG data and app startup:**

- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/HideDbgConfig.kt`: persisted state model and store interface.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/SharedPreferencesHideDbgConfigStore.kt`: schema validation, cached snapshot, and serialized commits.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/HideDbgMutationCoordinator.kt`: process-scoped mutation state, serialization, and KPM-to-config sequencing.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/InstalledApp.kt`: app/filter models and pure list derivations.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/InstalledAppsSource.kt`: PackageManager adapter.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt`: construct/read config, then construct the mutation coordinator and app source at process startup.
- Modify `app/src/main/AndroidManifest.xml`: declare all-packages visibility.

**Hide DBG presentation:**

- Replace `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt`: combine shared mutation state with route-local App-list state and delegate UID/global operations.
- Replace `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgScreen.kt`: global control, search, segmented filter, and app list.

**Tests:**

- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/ui/navigation/AppDestinationTest.kt`.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/ui/theme/YggdrasilThemeTest.kt`.
- Replace `app/src/test/kotlin/ng1ok/yggdrasilmanager/HomeViewModelTest.kt`.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HomePresentationTest.kt`.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgConfigStoreTest.kt`.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/InstalledAppsTest.kt`.
- Replace `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt`.

## Global Constraints

- Do not add a Yggdrasil version label; the ABI exposes availability only.
- Do not gate features on Root status.
- Do not call `setHideDbg()` from `App.onCreate()`, a constructor, config loading, or app-list refresh.
- Persist UID state only after a successful KPM command. A failed disk commit keeps the new in-memory state and reports `已套用但未保存`.
- Disabling global Hide DBG clears all saved UID rules. Enabling global Hide DBG preserves them but disables per-App switches.
- Use comments only for hidden invariants or workarounds, and explain why rather than narrating the code.
- Keep the existing JNI and `YggdrasilRepository` API unchanged.

### Task 1: Centralize Destinations and Add Route-Aware App Chrome

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/AppDestination.kt`
- Delete: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/BottomNavItem.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/MainActivity.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/features/FeaturesScreen.kt`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/ui/navigation/AppDestinationTest.kt`

- [ ] **Step 1: Write the failing route metadata tests**

```kotlin
package ng1ok.yggdrasilmanager.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {
    @Test
    fun destinationsMatchApprovedRoutes() {
        assertEquals(
            listOf(
                Triple("home", "主頁", true),
                Triple("features", "功能", true),
                Triple("hwbp", "HWBP Observe", false),
                Triple("hidedbg", "Hide DBG", false),
            ),
            AppDestination.entries.map { Triple(it.route, it.title, it.isTopLevel) },
        )
    }

    @Test
    fun topLevelDestinationsShowOnlyBottomNavigation() {
        assertEquals(
            listOf(AppDestination.Home, AppDestination.Features),
            AppDestination.topLevel,
        )
        AppDestination.topLevel.forEach {
            assertTrue(it.showBottomNavigation)
            assertFalse(it.showBackButton)
        }
    }

    @Test
    fun childDestinationsShowOnlyBackNavigation() {
        listOf(AppDestination.Hwbp, AppDestination.HideDbg).forEach {
            assertTrue(it.showBackButton)
            assertFalse(it.showBottomNavigation)
        }
    }

    @Test
    fun routeLookupFallsBackToHome() {
        assertEquals(AppDestination.Hwbp, AppDestination.fromRoute("hwbp"))
        assertEquals(AppDestination.Home, AppDestination.fromRoute(null))
        assertEquals(AppDestination.Home, AppDestination.fromRoute("unknown"))
    }
}
```

- [ ] **Step 2: Run the route test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.ui.navigation.AppDestinationTest"
```

Expected: compilation fails because `AppDestination` does not exist.

- [ ] **Step 3: Add the minimal destination model**

Create `AppDestination.kt`:

```kotlin
package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val title: String,
    val isTopLevel: Boolean,
    val bottomBarIcon: ImageVector? = null,
) {
    Home("home", "主頁", true, Icons.Default.Home),
    Features("features", "功能", true, Icons.Default.List),
    Hwbp("hwbp", "HWBP Observe", false),
    HideDbg("hidedbg", "Hide DBG", false),
    ;

    val showBackButton: Boolean get() = !isTopLevel
    val showBottomNavigation: Boolean get() = isTopLevel

    companion object {
        val topLevel: List<AppDestination> = entries.filter(AppDestination::isTopLevel)

        fun fromRoute(route: String?): AppDestination =
            entries.firstOrNull { it.route == route } ?: Home
    }
}
```

- [ ] **Step 4: Replace scattered route strings and app chrome**

Delete `BottomNavItem.kt`, then replace `MainActivity.kt` with:

```kotlin
package ng1ok.yggdrasilmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ng1ok.yggdrasilmanager.ui.navigation.AppDestination
import ng1ok.yggdrasilmanager.ui.navigation.NavGraph
import ng1ok.yggdrasilmanager.ui.theme.YggdrasilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YggdrasilTheme {
                MainScaffold()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = AppDestination.fromRoute(backStack?.destination?.route)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(destination.title) },
                navigationIcon = {
                    if (destination.showBackButton) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (destination.showBottomNavigation) {
                NavigationBar {
                    AppDestination.topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = requireNotNull(item.bottomBarIcon),
                                    contentDescription = item.title,
                                )
                            },
                            label = { Text(item.title) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavGraph(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

Replace `NavGraph.kt` with:

```kotlin
package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ng1ok.yggdrasilmanager.ui.features.FeaturesScreen
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgScreen
import ng1ok.yggdrasilmanager.ui.home.HomeScreen
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpScreen

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier,
    ) {
        composable(AppDestination.Home.route) { HomeScreen() }
        composable(AppDestination.Features.route) { FeaturesScreen(navController) }
        composable(AppDestination.Hwbp.route) { HwbpScreen() }
        composable(AppDestination.HideDbg.route) { HideDbgScreen() }
    }
}
```

Replace `FeaturesScreen.kt` with:

```kotlin
package ng1ok.yggdrasilmanager.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ng1ok.yggdrasilmanager.ui.navigation.AppDestination

private data class FeatureItem(
    val destination: AppDestination,
    val description: String,
    val icon: ImageVector,
)

private val features = listOf(
    FeatureItem(
        destination = AppDestination.Hwbp,
        description = "通過硬件斷點觀察進程內存訪問，實時查看觸發事件",
        icon = Icons.Default.Visibility,
    ),
    FeatureItem(
        destination = AppDestination.HideDbg,
        description = "隱藏調試器狀態，支持全局模式和按 App 精確控制",
        icon = Icons.Default.BugReport,
    ),
)

@Composable
fun FeaturesScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        features.forEach { feature ->
            FeatureCard(
                feature = feature,
                onClick = { navController.navigate(feature.destination.route) },
            )
        }
    }
}

@Composable
private fun FeatureCard(feature: FeatureItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(feature.destination.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    feature.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 5: Verify route behavior is GREEN and the app compiles**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.ui.navigation.AppDestinationTest"
.\gradlew.bat :app:assembleDebug
```

Expected: both commands exit 0. The child-route metadata test proves the back/bottom-bar contract; the build proves the Compose chrome and `navigateUp()` wiring compile.

- [ ] **Step 6: Commit the navigation slice**

```powershell
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/MainActivity.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/navigation app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/features/FeaturesScreen.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/ui/navigation/AppDestinationTest.kt
git commit -m "feat: add route-aware manager navigation"
```

### Task 2: Define the Manager Material 3 Theme

**Files:**

- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Shape.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme/Theme.kt`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/ui/theme/YggdrasilThemeTest.kt`

- [ ] **Step 1: Write the failing semantic theme test**

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YggdrasilThemeTest {
    @Test
    fun lightThemeUsesApprovedSemanticColors() {
        val scheme = yggdrasilColorScheme(darkTheme = false)
        assertEquals(Color(0xFF006C4C), scheme.primary)
        assertEquals(Color(0xFF53606F), scheme.tertiary)
        assertEquals(Color(0xFFBA1A1A), scheme.error)
        assertNotEquals(scheme.primary, scheme.error)
    }

    @Test
    fun darkThemeAndShapesUseApprovedTokens() {
        val scheme = yggdrasilColorScheme(darkTheme = true)
        assertEquals(Color(0xFF6DDBAD), scheme.primary)
        assertEquals(Color(0xFF101416), scheme.background)
        assertEquals(RoundedCornerShape(8.dp), YggdrasilShapes.medium)
        assertEquals(RoundedCornerShape(8.dp), YggdrasilShapes.large)
    }
}
```

- [ ] **Step 2: Run the theme test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.ui.theme.YggdrasilThemeTest"
```

Expected: compilation fails because `yggdrasilColorScheme` and `YggdrasilShapes` do not exist.

- [ ] **Step 3: Add the approved palette and shapes**

Replace `Color.kt` with:

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.ui.graphics.Color

internal val LightPrimary = Color(0xFF006C4C)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFF8CF8C6)
internal val LightOnPrimaryContainer = Color(0xFF002116)
internal val LightSecondary = Color(0xFF52645C)
internal val LightSecondaryContainer = Color(0xFFD5E8DE)
internal val LightTertiary = Color(0xFF53606F)
internal val LightTertiaryContainer = Color(0xFFD7E4F7)
internal val LightError = Color(0xFFBA1A1A)
internal val LightBackground = Color(0xFFF7F9FA)
internal val LightOnBackground = Color(0xFF191C1D)
internal val LightSurfaceVariant = Color(0xFFE0E3E6)
internal val LightOnSurfaceVariant = Color(0xFF44474A)
internal val LightOutline = Color(0xFF74777A)

internal val DarkPrimary = Color(0xFF6DDBAD)
internal val DarkOnPrimary = Color(0xFF003828)
internal val DarkPrimaryContainer = Color(0xFF00513A)
internal val DarkOnPrimaryContainer = Color(0xFF8CF8C6)
internal val DarkSecondary = Color(0xFFB8CCC1)
internal val DarkSecondaryContainer = Color(0xFF3B4B43)
internal val DarkTertiary = Color(0xFFBBC7DB)
internal val DarkTertiaryContainer = Color(0xFF3B4858)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkBackground = Color(0xFF101416)
internal val DarkOnBackground = Color(0xFFE0E3E4)
internal val DarkSurfaceVariant = Color(0xFF44474A)
internal val DarkOnSurfaceVariant = Color(0xFFC4C7CA)
internal val DarkOutline = Color(0xFF8E9194)
```

Create `Shape.kt`:

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val YggdrasilShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)
```

- [ ] **Step 4: Replace dynamic/template theme selection**

Replace `Theme.kt` with:

```kotlin
package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    tertiary = LightTertiary,
    tertiaryContainer = LightTertiaryContainer,
    error = LightError,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    tertiary = DarkTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    error = DarkError,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkBackground,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
)

internal fun yggdrasilColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkColorScheme else LightColorScheme

@Composable
fun YggdrasilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = yggdrasilColorScheme(darkTheme),
        typography = Typography,
        shapes = YggdrasilShapes,
        content = content,
    )
}
```

- [ ] **Step 5: Verify GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.ui.theme.YggdrasilThemeTest"
.\gradlew.bat :app:assembleDebug
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/theme app/src/test/kotlin/ng1ok/yggdrasilmanager/ui/theme/YggdrasilThemeTest.kt
git commit -m "style: define manager Material 3 theme"
```

Expected: tests and build exit 0.

### Task 3: Model Independent Home Startup Checks

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeModels.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/DeviceInfoProvider.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/RootStatusSource.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeViewModel.kt`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HomeViewModelTest.kt`

- [ ] **Step 1: Replace the Home ViewModel test with the failing behavior contract**

```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.ui.home.DeviceInfo
import ng1ok.yggdrasilmanager.ui.home.DeviceInfoProvider
import ng1ok.yggdrasilmanager.ui.home.HomeViewModel
import ng1ok.yggdrasilmanager.ui.home.RootStatus
import ng1ok.yggdrasilmanager.ui.home.RootStatusSource
import ng1ok.yggdrasilmanager.ui.home.YggdrasilStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: YggdrasilRepository
    private val device = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 8",
        androidVersion = "15",
        apiLevel = 35,
        kernelVersion = "6.1.0-test",
        managerVersion = "1.0",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        repo = mockk()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createVm(rootStatusSource: RootStatusSource): HomeViewModel =
        HomeViewModel(
            repo = repo,
            rootStatusSource = rootStatusSource,
            deviceInfoProvider = DeviceInfoProvider { device },
        )

    @Test
    fun initialStateContainsDeviceInfoAndCheckingStatuses() {
        val state = createVm(RootStatusSource { true }).uiState.value
        assertEquals(device, state.deviceInfo)
        assertEquals(RootStatus.CHECKING, state.rootStatus)
        assertEquals(YggdrasilStatus.CHECKING, state.yggdrasilStatus)
    }

    @Test
    fun moduleCheckCompletesWhileRootCheckIsPending() = runTest {
        val rootResult = CompletableDeferred<Boolean>()
        coEvery { repo.checkAvailable() } returns true
        val vm = createVm(RootStatusSource { rootResult.await() })

        vm.startStartupChecks()
        runCurrent()

        assertEquals(RootStatus.CHECKING, vm.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.AVAILABLE, vm.uiState.value.yggdrasilStatus)

        rootResult.complete(true)
        advanceUntilIdle()
        assertEquals(RootStatus.GRANTED, vm.uiState.value.rootStatus)
    }

    @Test
    fun deniedRootShowsOnePromptAndStillChecksModule() = runTest {
        val root = mockk<RootStatusSource>()
        coEvery { root.isRootGranted() } returns false
        coEvery { repo.checkAvailable() } returns true
        val vm = createVm(root)

        vm.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.DENIED, vm.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.AVAILABLE, vm.uiState.value.yggdrasilStatus)
        assertEquals(true, vm.uiState.value.showRootDeniedDialog)

        vm.dismissRootDeniedDialog()
        vm.startStartupChecks()
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.showRootDeniedDialog)
        assertEquals(true, vm.uiState.value.hasShownRootDeniedPrompt)
        coVerify(exactly = 1) { root.isRootGranted() }
        coVerify(exactly = 1) { repo.checkAvailable() }
    }

    @Test
    fun grantedRootDoesNotShowPrompt() = runTest {
        coEvery { repo.checkAvailable() } returns false
        val vm = createVm(RootStatusSource { true })

        vm.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.GRANTED, vm.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.UNAVAILABLE, vm.uiState.value.yggdrasilStatus)
        assertEquals(false, vm.uiState.value.showRootDeniedDialog)
    }

    @Test
    fun checkFailuresMapToDeniedAndUnavailableWithoutBlocking() = runTest {
        coEvery { repo.checkAvailable() } throws IllegalStateException("native unavailable")
        val vm = createVm(RootStatusSource { error("no su") })

        vm.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.DENIED, vm.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.UNAVAILABLE, vm.uiState.value.yggdrasilStatus)
        assertEquals(true, vm.uiState.value.showRootDeniedDialog)
    }
}
```

- [ ] **Step 2: Run the Home test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HomeViewModelTest"
```

Expected: compilation fails because the new status/provider APIs do not exist.

- [ ] **Step 3: Add the Home models and platform providers**

Create `HomeModels.kt`:

```kotlin
package ng1ok.yggdrasilmanager.ui.home

enum class RootStatus { CHECKING, GRANTED, DENIED }

enum class YggdrasilStatus { CHECKING, AVAILABLE, UNAVAILABLE }

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val kernelVersion: String,
    val managerVersion: String,
)

data class HomeUiState(
    val deviceInfo: DeviceInfo,
    val rootStatus: RootStatus = RootStatus.CHECKING,
    val yggdrasilStatus: YggdrasilStatus = YggdrasilStatus.CHECKING,
    val showRootDeniedDialog: Boolean = false,
    val hasShownRootDeniedPrompt: Boolean = false,
)
```

Create `DeviceInfoProvider.kt`:

```kotlin
package ng1ok.yggdrasilmanager.ui.home

import android.os.Build
import ng1ok.yggdrasilmanager.BuildConfig

fun interface DeviceInfoProvider {
    fun getDeviceInfo(): DeviceInfo
}

object AndroidDeviceInfoProvider : DeviceInfoProvider {
    override fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
        kernelVersion = System.getProperty("os.version").orEmpty().ifBlank { "未知" },
        managerVersion = BuildConfig.VERSION_NAME,
    )
}
```

Create `RootStatusSource.kt`:

```kotlin
package ng1ok.yggdrasilmanager.ui.home

import com.topjohnwu.superuser.Shell
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface RootStatusSource {
    suspend fun isRootGranted(): Boolean
}

object LibsuRootStatusSource : RootStatusSource {
    override suspend fun isRootGranted(): Boolean = suspendCancellableCoroutine { continuation ->
        Shell.getShell { shell ->
            if (continuation.isActive) {
                continuation.resume(shell.isRoot)
            }
        }
    }
}
```

- [ ] **Step 4: Replace HomeViewModel with independent checks**

```kotlin
package ng1ok.yggdrasilmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository

class HomeViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance,
    private val rootStatusSource: RootStatusSource = LibsuRootStatusSource,
    deviceInfoProvider: DeviceInfoProvider = AndroidDeviceInfoProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HomeUiState(deviceInfo = deviceInfoProvider.getDeviceInfo()),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var startupChecksStarted = false

    fun startStartupChecks() {
        if (startupChecksStarted) return
        startupChecksStarted = true

        viewModelScope.launch {
            launch {
                val granted = runCatching { rootStatusSource.isRootGranted() }
                    .getOrDefault(false)
                _uiState.update { state ->
                    val showPrompt = !granted && !state.hasShownRootDeniedPrompt
                    state.copy(
                        rootStatus = if (granted) RootStatus.GRANTED else RootStatus.DENIED,
                        showRootDeniedDialog = showPrompt,
                        hasShownRootDeniedPrompt = state.hasShownRootDeniedPrompt || showPrompt,
                    )
                }
            }
            launch {
                val available = runCatching { repo.checkAvailable() }
                    .getOrDefault(false)
                _uiState.update {
                    it.copy(
                        yggdrasilStatus = if (available) {
                            YggdrasilStatus.AVAILABLE
                        } else {
                            YggdrasilStatus.UNAVAILABLE
                        },
                    )
                }
            }
        }
    }

    fun dismissRootDeniedDialog() {
        _uiState.update { it.copy(showRootDeniedDialog = false) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel() }
        }
    }
}
```

- [ ] **Step 5: Verify Home logic is GREEN and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HomeViewModelTest"
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeModels.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/DeviceInfoProvider.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/RootStatusSource.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeViewModel.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HomeViewModelTest.kt
git commit -m "feat: model home startup checks and device info"
```

Expected: the focused test exits 0.

### Task 4: Render the Home Status Dashboard and Root Warning

**Files:**

- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeModels.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeScreen.kt`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HomePresentationTest.kt`

- [ ] **Step 1: Write the failing presentation mapping test**

```kotlin
package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.ui.home.DeviceInfo
import ng1ok.yggdrasilmanager.ui.home.RootStatus
import ng1ok.yggdrasilmanager.ui.home.YggdrasilStatus
import ng1ok.yggdrasilmanager.ui.home.androidLabel
import ng1ok.yggdrasilmanager.ui.home.displayName
import ng1ok.yggdrasilmanager.ui.home.label
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePresentationTest {
    @Test
    fun statusesHaveStableVisibleLabels() {
        assertEquals("檢查中", RootStatus.CHECKING.label)
        assertEquals("已授權", RootStatus.GRANTED.label)
        assertEquals("未授權", RootStatus.DENIED.label)
        assertEquals("可用", YggdrasilStatus.AVAILABLE.label)
        assertEquals("不可用", YggdrasilStatus.UNAVAILABLE.label)
    }

    @Test
    fun deviceLabelsAvoidDuplicatingManufacturer() {
        val device = DeviceInfo("Google", "Google Pixel 8", "15", 35, "6.1", "1.0")
        assertEquals("Google Pixel 8", device.displayName)
        assertEquals("Android 15 (API 35)", device.androidLabel)
    }
}
```

- [ ] **Step 2: Run the presentation test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HomePresentationTest"
```

Expected: compilation fails because the display properties do not exist.

- [ ] **Step 3: Add display mappings to HomeModels.kt**

Append:

```kotlin
val RootStatus.label: String
    get() = when (this) {
        RootStatus.CHECKING -> "檢查中"
        RootStatus.GRANTED -> "已授權"
        RootStatus.DENIED -> "未授權"
    }

val YggdrasilStatus.label: String
    get() = when (this) {
        YggdrasilStatus.CHECKING -> "檢查中"
        YggdrasilStatus.AVAILABLE -> "可用"
        YggdrasilStatus.UNAVAILABLE -> "不可用"
    }

val DeviceInfo.displayName: String
    get() {
        val cleanManufacturer = manufacturer.trim()
        val cleanModel = model.trim()
        return when {
            cleanModel.isBlank() -> cleanManufacturer.ifBlank { "未知設備" }
            cleanManufacturer.isBlank() -> cleanModel
            cleanModel.startsWith(cleanManufacturer, ignoreCase = true) -> cleanModel
            else -> "$cleanManufacturer $cleanModel"
        }
    }

val DeviceInfo.androidLabel: String
    get() = "Android ${androidVersion.ifBlank { "未知" }} (API $apiLevel)"
```

- [ ] **Step 4: Replace HomeScreen.kt with the dashboard UI**

```kotlin
package ng1ok.yggdrasilmanager.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ng1ok.yggdrasilmanager.R

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(vm) {
        vm.startStartupChecks()
    }

    if (state.showRootDeniedDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissRootDeniedDialog,
            title = { Text("Root 權限未授權") },
            text = { Text("YggdrasilManager 尚未取得 Root 權限；此提示不會限制任何功能。") },
            confirmButton = {
                TextButton(onClick = vm::dismissRootDeniedDialog) {
                    Text("知道了")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ModuleSummary(state.yggdrasilStatus) }
        item { RootSummary(state.rootStatus) }
        item { DeviceInfoPanel(state.deviceInfo) }
    }
}

@Composable
private fun ModuleSummary(status: YggdrasilStatus) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = when (status) {
            YggdrasilStatus.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
            YggdrasilStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
            YggdrasilStatus.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Yggdrasil", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                StatusLine(status.label, statusIcon(status))
            }
        }
    }
}

@Composable
private fun RootSummary(status: RootStatus) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Root 權限", style = MaterialTheme.typography.titleMedium)
            StatusLine(status.label, statusIcon(status))
        }
    }
}

@Composable
private fun DeviceInfoPanel(device: DeviceInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "設備資訊",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            InfoRow("設備", device.displayName)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            InfoRow("系統", device.androidLabel)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            InfoRow("核心", device.kernelVersion)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            InfoRow("Manager", device.managerVersion)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.32f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.68f))
    }
}

@Composable
private fun StatusLine(label: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun statusIcon(status: YggdrasilStatus): ImageVector = when (status) {
    YggdrasilStatus.CHECKING -> Icons.Default.HourglassTop
    YggdrasilStatus.AVAILABLE -> Icons.Default.CheckCircle
    YggdrasilStatus.UNAVAILABLE -> Icons.Default.Warning
}

private fun statusIcon(status: RootStatus): ImageVector = when (status) {
    RootStatus.CHECKING -> Icons.Default.HourglassTop
    RootStatus.GRANTED -> Icons.Default.CheckCircle
    RootStatus.DENIED -> Icons.Default.Warning
}
```

- [ ] **Step 5: Verify the presentation mapping and Compose build are GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HomePresentationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HomeViewModelTest"
.\gradlew.bat :app:assembleDebug
```

Expected: all commands exit 0; no Yggdrasil version is rendered.

- [ ] **Step 6: Commit the Home UI slice**

```powershell
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeModels.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/home/HomeScreen.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HomePresentationTest.kt
git commit -m "feat: render home status dashboard and root warning"
```

### Task 5: Add the Cached Hide DBG Configuration Store

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/HideDbgConfig.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/SharedPreferencesHideDbgConfigStore.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgConfigStoreTest.kt`

- [ ] **Step 1: Write the failing configuration store tests**

```kotlin
package ng1ok.yggdrasilmanager

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfig
import ng1ok.yggdrasilmanager.data.hidedbg.SharedPreferencesHideDbgConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HideDbgConfigStoreTest {
    @Test
    fun loadAcceptsValidUidsAndDropsMalformedValues() {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getInt(any(), any()) } returns 1
        every { preferences.getBoolean(any(), any()) } returns true
        every { preferences.getStringSet(any(), any()) } returns
            setOf("1000", "-1", "abc", "2147483648", "1000")

        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(),
        )

        assertEquals(HideDbgConfig(true, setOf(1000)), store.current)
        assertEquals(null, store.loadWarning)
    }

    @Test
    fun unsupportedSchemaFallsBackToSafeDefaults() {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getInt(any(), any()) } returns 2

        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(),
        )

        assertEquals(HideDbgConfig(), store.current)
        assertNotNull(store.loadWarning)
    }

    @Test
    fun successfulCommitWritesSchemaAndUidSet() = runTest {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.getInt(any(), any()) } returns 1
        every { preferences.getBoolean(any(), any()) } returns false
        every { preferences.getStringSet(any(), any()) } returns emptySet()
        every { preferences.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns true
        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(),
        )
        val changed = HideDbgConfig(true, setOf(1000, 1001))

        val result = store.save(changed)

        assertTrue(result.isSuccess)
        verify { editor.putInt("schema_version", 1) }
        verify { editor.putBoolean("global_enabled", true) }
        verify { editor.putStringSet("enabled_uids", setOf("1000", "1001")) }
        assertEquals(changed, store.current)
    }

    @Test
    fun failedCommitKeepsNewInMemoryStateAndReportsFailure() = runTest {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.getInt(any(), any()) } returns 1
        every { preferences.getBoolean(any(), any()) } returns false
        every { preferences.getStringSet(any(), any()) } returns emptySet()
        every { preferences.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns false
        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(),
        )
        val changed = HideDbgConfig(globalEnabled = false, enabledUids = setOf(1000))

        val result = store.save(changed)

        assertTrue(result.isFailure)
        assertEquals(changed, store.current)
    }
}
```

- [ ] **Step 2: Run the store test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgConfigStoreTest"
```

Expected: compilation fails because the config/store types do not exist.

- [ ] **Step 3: Add the config model and store contract**

Create `HideDbgConfig.kt`:

```kotlin
package ng1ok.yggdrasilmanager.data.hidedbg

data class HideDbgConfig(
    val globalEnabled: Boolean = false,
    val enabledUids: Set<Int> = emptySet(),
)

interface HideDbgConfigStore {
    val current: HideDbgConfig
    val loadWarning: String?
    suspend fun save(config: HideDbgConfig): Result<Unit>
}
```

- [ ] **Step 4: Implement validated reads and serialized writes**

Create `SharedPreferencesHideDbgConfigStore.kt`:

```kotlin
package ng1ok.yggdrasilmanager.data.hidedbg

import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SharedPreferencesHideDbgConfigStore(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HideDbgConfigStore {
    private val saveMutex = Mutex()

    @Volatile
    private var currentValue: HideDbgConfig

    override val current: HideDbgConfig get() = currentValue
    override val loadWarning: String?

    init {
        val loaded = readInitialConfig()
        currentValue = loaded.first
        loadWarning = loaded.second
    }

    override suspend fun save(config: HideDbgConfig): Result<Unit> =
        withContext(ioDispatcher) {
            saveMutex.withLock {
                val stableConfig = config.copy(enabledUids = config.enabledUids.toSet())

                // A successful KPM command is already live, so memory must not pretend it rolled back.
                currentValue = stableConfig

                runCatching {
                    val committed = preferences.edit()
                        .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                        .putBoolean(KEY_GLOBAL_ENABLED, stableConfig.globalEnabled)
                        .putStringSet(
                            KEY_ENABLED_UIDS,
                            stableConfig.enabledUids.map { it.toString() }.toSet(),
                        )
                        .commit()
                    if (!committed) {
                        throw IOException("Hide DBG configuration commit failed")
                    }
                }
            }
        }

    private fun readInitialConfig(): Pair<HideDbgConfig, String?> = try {
        val schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        if (schemaVersion != SCHEMA_VERSION) {
            HideDbgConfig() to "不支援的 Hide DBG 配置版本：$schemaVersion"
        } else {
            val uids = preferences.getStringSet(KEY_ENABLED_UIDS, emptySet())
                .orEmpty()
                .mapNotNull { raw ->
                    raw.takeIf { it.isNotEmpty() && it.all { char -> char.isDigit() } }
                        ?.toIntOrNull()
                        ?.takeIf { it >= 0 }
                }
                .toSet()
            HideDbgConfig(
                globalEnabled = preferences.getBoolean(KEY_GLOBAL_ENABLED, false),
                enabledUids = uids,
            ) to null
        }
    } catch (_: RuntimeException) {
        HideDbgConfig() to "Hide DBG 配置無法讀取，已使用預設值"
    }

    companion object {
        const val FILE_NAME = "hide_dbg_config"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_ENABLED_UIDS = "enabled_uids"
    }
}
```

- [ ] **Step 5: Initialize the store when the App process starts**

Replace `App.kt` with:

```kotlin
package ng1ok.yggdrasilmanager

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.SharedPreferencesHideDbgConfigStore

class App : Application() {
    lateinit var hideDbgConfigStore: HideDbgConfigStore
        private set

    override fun onCreate() {
        super.onCreate()

        // The KPM cannot report its rules, so startup restores Manager state without replaying it.
        hideDbgConfigStore = SharedPreferencesHideDbgConfigStore(
            getSharedPreferences(
                SharedPreferencesHideDbgConfigStore.FILE_NAME,
                Context.MODE_PRIVATE,
            ),
        )

        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10),
        )
    }
}
```

- [ ] **Step 6: Verify GREEN and commit the startup config slice**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgConfigStoreTest"
.\gradlew.bat :app:assembleDebug
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/HideDbgConfig.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/SharedPreferencesHideDbgConfigStore.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgConfigStoreTest.kt
git commit -m "feat: add cached Hide DBG configuration"
```

Expected: tests/build exit 0. At this intermediate step, `App` only loads the cached snapshot and performs no KPM call. The final process-scoped coordinator wiring is added in Task 7; its construction also performs no KPM call.

### Task 6: Enumerate, Sort, Search, and Group Installed Apps

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/InstalledApp.kt`
- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/InstalledAppsSource.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/InstalledAppsTest.kt`

- [ ] **Step 1: Write failing installed-app and filter tests**

```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.PackageRecord
import ng1ok.yggdrasilmanager.data.hidedbg.PackageManagerInstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.filterInstalledApps
import ng1ok.yggdrasilmanager.data.hidedbg.findSharedUids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAppsTest {
    private val apps = listOf(
        InstalledApp("com.beta", "Beta", 1001, false),
        InstalledApp("com.alpha.system", "Alpha System", 1000, true),
        InstalledApp("com.alpha.shared", "Alpha Shared", 1000, false),
    )

    @Test
    fun filtersMatchLabelPackageAndAppType() {
        assertEquals(2, filterInstalledApps(apps, "alpha", AppFilter.ALL).size)
        assertEquals(
            listOf("com.alpha.shared"),
            filterInstalledApps(apps, "", AppFilter.USER).map { it.packageName }
                .filter { it.startsWith("com.alpha") },
        )
        assertEquals(
            listOf("com.alpha.system"),
            filterInstalledApps(apps, "system", AppFilter.SYSTEM).map { it.packageName },
        )
    }

    @Test
    fun sharedUidDetectionReturnsOnlyDuplicatedUids() {
        assertEquals(setOf(1000), findSharedUids(apps))
    }

    @Test
    fun packageManagerSourceMapsFlagsAndSortsStably() = runTest {
        val source = PackageManagerInstalledAppsSource(
            packageManager = mockk(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
            queryRecords = {
                listOf(
                    PackageRecord(
                        packageName = "com.zeta",
                        label = "Zeta",
                        uid = 1000,
                        flags = android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                    ),
                    PackageRecord(
                        packageName = "com.alpha",
                        label = "Alpha",
                        uid = 1001,
                        flags = 0,
                    ),
                )
            },
        )

        val loaded = source.load().getOrThrow()

        assertEquals(listOf("com.alpha", "com.zeta"), loaded.map { it.packageName })
        assertFalse(loaded.first().isSystem)
        assertTrue(loaded.last().isSystem)
    }
}
```

- [ ] **Step 2: Run the installed-app test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.InstalledAppsTest"
```

Expected: compilation fails because installed-app types and functions do not exist.

- [ ] **Step 3: Add the pure app/filter model**

Create `InstalledApp.kt`:

```kotlin
package ng1ok.yggdrasilmanager.data.hidedbg

import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
)

enum class AppFilter { ALL, USER, SYSTEM }

fun filterInstalledApps(
    apps: List<InstalledApp>,
    query: String,
    filter: AppFilter,
): List<InstalledApp> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return apps.filter { app ->
        val matchesType = when (filter) {
            AppFilter.ALL -> true
            AppFilter.USER -> !app.isSystem
            AppFilter.SYSTEM -> app.isSystem
        }
        val matchesQuery = normalizedQuery.isEmpty() ||
            app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        matchesType && matchesQuery
    }
}

fun findSharedUids(apps: List<InstalledApp>): Set<Int> = apps
    .groupingBy(InstalledApp::uid)
    .eachCount()
    .filterValues { it > 1 }
    .keys
```

- [ ] **Step 4: Add the PackageManager source**

Create `InstalledAppsSource.kt`:

```kotlin
package ng1ok.yggdrasilmanager.data.hidedbg

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface InstalledAppsSource {
    suspend fun load(): Result<List<InstalledApp>>
}

class PackageManagerInstalledAppsSource(
    private val packageManager: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val queryRecords: () -> List<PackageRecord> = {
        packageManager.installedApplicationsCompat().map { info ->
            PackageRecord(
                packageName = info.packageName,
                label = runCatching {
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(info.packageName),
                uid = info.uid,
                flags = info.flags,
            )
        }
    },
) : InstalledAppsSource {
    override suspend fun load(): Result<List<InstalledApp>> = withContext(ioDispatcher) {
        runCatching {
            queryRecords()
                .map { record ->
                    val systemMask = ApplicationInfo.FLAG_SYSTEM or
                        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    InstalledApp(
                        packageName = record.packageName,
                        label = record.label,
                        uid = record.uid,
                        isSystem = record.flags and systemMask != 0,
                    )
                }
                .sortedWith(
                    compareBy<InstalledApp> {
                        it.label.lowercase(Locale.getDefault())
                    }.thenBy { it.packageName },
                )
        }
    }
}

internal data class PackageRecord(
    val packageName: String,
    val label: String,
    val uid: Int,
    val flags: Int,
)

@Suppress("DEPRECATION")
private fun PackageManager.installedApplicationsCompat(): List<ApplicationInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        getInstalledApplications(0)
    }
```

- [ ] **Step 5: Wire process dependencies and package visibility**

Replace `App.kt` with:

```kotlin
package ng1ok.yggdrasilmanager

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.PackageManagerInstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.SharedPreferencesHideDbgConfigStore

class App : Application() {
    lateinit var hideDbgConfigStore: HideDbgConfigStore
        private set
    lateinit var installedAppsSource: InstalledAppsSource
        private set

    override fun onCreate() {
        super.onCreate()

        // The KPM cannot report its rules, so startup restores Manager state without replaying it.
        hideDbgConfigStore = SharedPreferencesHideDbgConfigStore(
            getSharedPreferences(
                SharedPreferencesHideDbgConfigStore.FILE_NAME,
                Context.MODE_PRIVATE,
            ),
        )
        installedAppsSource = PackageManagerInstalledAppsSource(packageManager)

        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10),
        )
    }
}
```

Add this immediately inside `<manifest>` and before `<application>`:

```xml
<uses-permission
    android:name="android.permission.QUERY_ALL_PACKAGES"
    tools:ignore="QueryAllPackagesPermission" />
```

- [ ] **Step 6: Verify GREEN and commit installed-app discovery**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.InstalledAppsTest"
.\gradlew.bat :app:assembleDebug
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/InstalledApp.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/InstalledAppsSource.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/InstalledAppsTest.kt
git commit -m "feat: enumerate apps for Hide DBG"
```

Expected: tests/build exit 0. No Root or KPM API is used for enumeration.

### Task 7: Move Hide DBG Mutations to Process Scope (Normative Final)

> **Normative lifecycle amendment:** This is the sole current implementation and review contract for Hide DBG mutation ownership. It supersedes every instruction and snippet in the historical Task 7H below.

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/data/hidedbg/HideDbgMutationCoordinator.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/App.kt`
- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt`
- Modify: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt`

**Required ownership and ordering:**

- `App` synchronously constructs `SharedPreferencesHideDbgConfigStore`, then constructs exactly one `HideDbgMutationCoordinator` for the process.
- The coordinator initializes shared immutable state only from `configStore.current` and `configStore.loadWarning`. Construction and startup must make zero KPM calls.
- The shared state contains `globalEnabled`, `enabledUids`, `globalPending`, `pendingUids`, and `message`.
- The coordinator owns one process-wide coroutine `Mutex` and an injected Application-lifetime `CoroutineScope`.
- Request reservation is atomic so calls from different ViewModel instances cannot pass stale pending checks. The KPM -> shared state -> config sequence runs in the Application scope under the single Mutex.
- A KPM failure leaves enabled state unchanged and skips config save. A save failure retains applied in-memory state and reports exactly `已套用但未保存`. Cancellation and fatal failures propagate after pending cleanup.
- Global enable preserves saved UIDs. Successful global disable clears and saves all UIDs without replaying them.
- `HideDbgViewModel` owns only route-local App-list, query, filter, loading, and load-error state. It observes the coordinator state and delegates every UID/global toggle and message dismissal.
- App-list initialization and refresh remain in `viewModelScope` and perform zero KPM calls and zero config writes.

- [x] **Step 1: Add the failing route-teardown and cross-instance contracts**

The lifecycle regression uses real `ViewModelStoreOwner`, `ViewModelProvider`, and `ViewModelStore.clear()` calls. With initial config `global=true` and UIDs `{1000, 1001}`, the repository marks the native side effect complete and then waits before returning:

```kotlin
val nativeApplied = CompletableDeferred<Unit>()
val allowReturn = CompletableDeferred<Unit>()
coEvery { repo.setHideDbg(false, null) } coAnswers {
    nativeApplied.complete(Unit)
    allowReturn.await()
    Result.success(Unit)
}

val coordinator = HideDbgMutationCoordinator(repo, configStore, this)
val factory = viewModelFactory {
    initializer { HideDbgViewModel(coordinator, installedAppsSource) }
}
val firstOwner = TestViewModelStoreOwner()
val firstVm = ViewModelProvider(firstOwner, factory)[HideDbgViewModel::class.java]

firstVm.toggleGlobal()
runCurrent()
nativeApplied.await()
firstOwner.viewModelStore.clear()

val secondOwner = TestViewModelStoreOwner()
val secondVm = ViewModelProvider(secondOwner, factory)[HideDbgViewModel::class.java]
assertTrue(secondVm.uiState.value.globalPending)

allowReturn.complete(Unit)
advanceUntilIdle()
assertFalse(secondVm.uiState.value.globalEnabled)
assertEquals(emptySet<Int>(), secondVm.uiState.value.enabledUids)
coVerify(exactly = 1) {
    configStore.save(HideDbgConfig(false, emptySet()))
}
```

Add a second test with two ViewModel owners sharing the same coordinator. Block UID 1000, request UID 1001 from the other ViewModel, and verify UID 1001 does not reach KPM until UID 1000 completes. The saves must then occur in cumulative order: `{1000}`, followed by `{1000, 1001}`.

Expected RED against route-owned mutation state: the re-entered ViewModel reports `globalPending=false`, and the second ViewModel can call KPM while the first instance's Mutex is occupied.

- [x] **Step 2: Add the process-scoped mutation coordinator**

The coordinator has this ownership shape; mutation methods atomically reserve pending state before launching the serialized operation:

```kotlin
data class HideDbgMutationState(
    val globalEnabled: Boolean = false,
    val enabledUids: Set<Int> = emptySet(),
    val globalPending: Boolean = false,
    val pendingUids: Set<Int> = emptySet(),
    val message: String? = null,
)

class HideDbgMutationCoordinator(
    private val repo: YggdrasilRepository,
    private val configStore: HideDbgConfigStore,
    private val applicationScope: CoroutineScope,
) {
    private val mutationMutex = Mutex()
    private val _state = MutableStateFlow(
        configStore.current.let { config ->
            HideDbgMutationState(
                globalEnabled = config.globalEnabled,
                enabledUids = config.enabledUids.toSet(),
                message = configStore.loadWarning,
            )
        },
    )
    val state: StateFlow<HideDbgMutationState> = _state.asStateFlow()
}
```

`toggleUid()` and `toggleGlobal()` reserve pending state with `MutableStateFlow.compareAndSet`. Their Application-scope jobs use `mutationMutex.withLock` around the complete KPM -> shared state -> config transaction and clear pending state in `finally`.

- [x] **Step 3: Construct one coordinator after synchronous config loading**

The relevant final `App` dependency wiring is:

```kotlin
class App : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var hideDbgConfigStore: HideDbgConfigStore
        private set
    lateinit var hideDbgMutationCoordinator: HideDbgMutationCoordinator
        private set
    lateinit var installedAppsSource: InstalledAppsSource
        private set

    override fun onCreate() {
        super.onCreate()
        hideDbgConfigStore = SharedPreferencesHideDbgConfigStore(
            getSharedPreferences(
                SharedPreferencesHideDbgConfigStore.FILE_NAME,
                Context.MODE_PRIVATE,
            ),
        )
        hideDbgMutationCoordinator = HideDbgMutationCoordinator(
            repo = YggdrasilRepository.instance,
            configStore = hideDbgConfigStore,
            applicationScope = applicationScope,
        )
        installedAppsSource = PackageManagerInstalledAppsSource(packageManager)
    }
}
```

Creating the repository and coordinator does not call `setHideDbg()`. Only an explicit user toggle reaches the coordinator mutation methods.

- [x] **Step 4: Combine shared mutation state with route-local presentation state**

The final ViewModel constructor and factory do not receive the repository or config store:

```kotlin
class HideDbgViewModel(
    private val mutationCoordinator: HideDbgMutationCoordinator,
    private val installedAppsSource: InstalledAppsSource,
) : ViewModel() {
    private val localState = MutableStateFlow(HideDbgLocalState())
    val uiState: StateFlow<HideDbgUiState> = combine(
        mutationCoordinator.state,
        localState,
    ) { mutationState, routeState ->
        routeState.toUiState(mutationState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = localState.value.toUiState(mutationCoordinator.state.value),
    )

    fun toggleUid(uid: Int) = mutationCoordinator.toggleUid(uid)
    fun toggleGlobal() = mutationCoordinator.toggleGlobal()
    fun dismissMessage() = mutationCoordinator.dismissMessage()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                HideDbgViewModel(
                    mutationCoordinator = app.hideDbgMutationCoordinator,
                    installedAppsSource = app.installedAppsSource,
                )
            }
        }
    }
}
```

- [x] **Step 5: Verify lifecycle GREEN and the complete JVM suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest.toggleGlobal_routeTeardownAfterNativeApplyFinishesThroughSharedState"
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest.toggleUid_distinctRequestsAcrossViewModelsSerializeAndSaveCumulativeSnapshots"
.\gradlew.bat :app:testDebugUnitTest
```

Expected: both lifecycle regressions and the complete suite exit 0. A re-entered ViewModel observes the existing pending/result state; different ViewModel instances serialize through the same Mutex; startup and App-list refresh still issue zero Hide DBG KPM writes.

### Task 7H (Superseded, Non-Normative): Initial Route-Scoped State Machine

> **Do not implement or review this section as current architecture.** It is retained only as the historical first TDD iteration. Every file list, checkbox, constructor, wiring snippet, command, and Expected paragraph from here through the explicit end marker immediately before Task 8 is non-normative. In particular, `HideDbgViewModel(repo, store, source)`, direct repository/config-store ownership, `operationMutex`, and mutation launches in `viewModelScope` are obsolete; Task 7 immediately above is the final contract.

**Files:**

- Replace: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt`
- Replace: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt`

- [ ] **Step 1: Replace the ViewModel test with the failing state-machine contract**

```kotlin
package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfig
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HideDbgViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: YggdrasilRepository
    private lateinit var store: HideDbgConfigStore
    private lateinit var source: InstalledAppsSource
    private val apps = listOf(
        InstalledApp("com.one", "One", 1000, false),
        InstalledApp("com.shared", "Shared", 1000, false),
        InstalledApp("com.system", "System", 1001, true),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        repo = mockk()
        store = mockk()
        source = mockk()
        coEvery { source.load() } returns Result.success(apps)
        coEvery { store.save(any()) } returns Result.success(Unit)
        coEvery { repo.setHideDbg(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createVm(
        config: HideDbgConfig = HideDbgConfig(),
        warning: String? = null,
    ): HideDbgViewModel {
        every { store.current } returns config
        every { store.loadWarning } returns warning
        return HideDbgViewModel(repo, store, source)
    }

    @Test
    fun initializationRestoresConfigAndNeverReplaysKpm() = runTest {
        val vm = createVm(HideDbgConfig(true, setOf(1000)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.globalEnabled)
        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
        assertEquals(apps, vm.uiState.value.apps)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun uidToggleUpdatesEverySharedUidRowAndPersistsAfterKpmSuccess() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.toggleUid(1000)
        advanceUntilIdle()

        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
        assertEquals(setOf(1000), vm.uiState.value.sharedUids)
        coVerify { repo.setHideDbg(true, 1000) }
        coVerify { store.save(HideDbgConfig(false, setOf(1000))) }
    }

    @Test
    fun kpmFailureKeepsOriginalUidState() = runTest {
        coEvery { repo.setHideDbg(true, 1000) } returns
            Result.failure(IllegalStateException("denied"))
        val vm = createVm()
        advanceUntilIdle()

        vm.toggleUid(1000)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.enabledUids.isEmpty())
        assertTrue(vm.uiState.value.message?.contains("denied") == true)
        coVerify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun configFailureKeepsAppliedStateAndShowsExplicitWarning() = runTest {
        coEvery { store.save(any()) } returns Result.failure(IllegalStateException("disk"))
        val vm = createVm()
        advanceUntilIdle()

        vm.toggleUid(1000)
        advanceUntilIdle()

        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
        assertEquals("已套用但未保存", vm.uiState.value.message)
    }

    @Test
    fun appLoadFailureExposesRetryStateWithoutChangingConfig() = runTest {
        coEvery { source.load() } returns Result.failure(IllegalStateException("packages"))
        val vm = createVm(HideDbgConfig(false, setOf(1000)))

        advanceUntilIdle()

        assertEquals("packages", vm.uiState.value.appLoadError)
        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun disablingGlobalClearsEveryUidAndPersistsEmptyConfig() = runTest {
        val vm = createVm(HideDbgConfig(true, setOf(1000, 1001)))
        advanceUntilIdle()

        vm.toggleGlobal()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.globalEnabled)
        assertTrue(vm.uiState.value.enabledUids.isEmpty())
        coVerify { repo.setHideDbg(false, null) }
        coVerify { store.save(HideDbgConfig(false, emptySet())) }
    }

    @Test
    fun globalKpmFailureKeepsGlobalAndUidState() = runTest {
        coEvery { repo.setHideDbg(false, null) } returns
            Result.failure(IllegalStateException("global denied"))
        val vm = createVm(HideDbgConfig(true, setOf(1000)))
        advanceUntilIdle()

        vm.toggleGlobal()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.globalEnabled)
        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
        coVerify(exactly = 0) { store.save(any()) }
    }

    @Test
    fun enablingGlobalPreservesUidConfigAndBlocksUidWrites() = runTest {
        val vm = createVm(HideDbgConfig(false, setOf(1000)))
        advanceUntilIdle()

        vm.toggleGlobal()
        advanceUntilIdle()
        vm.toggleUid(1001)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.globalEnabled)
        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
        coVerify { store.save(HideDbgConfig(true, setOf(1000))) }
        coVerify(exactly = 0) { repo.setHideDbg(any(), 1001) }
    }

    @Test
    fun repeatedUidTapWhilePendingProducesOneKpmWrite() = runTest {
        val result = CompletableDeferred<Result<Unit>>()
        coEvery { repo.setHideDbg(true, 1000) } coAnswers { result.await() }
        val vm = createVm()
        advanceUntilIdle()

        vm.toggleUid(1000)
        vm.toggleUid(1000)
        runCurrent()

        coVerify(exactly = 1) { repo.setHideDbg(true, 1000) }
        result.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(setOf(1000), vm.uiState.value.enabledUids)
    }

    @Test
    fun distinctUidWritesPersistCumulativeSnapshotsInOrder() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.toggleUid(1000)
        vm.toggleUid(1001)
        advanceUntilIdle()

        assertEquals(setOf(1000, 1001), vm.uiState.value.enabledUids)
        coVerifyOrder {
            store.save(HideDbgConfig(false, setOf(1000)))
            store.save(HideDbgConfig(false, setOf(1000, 1001)))
        }
    }
}
```

- [ ] **Step 2: Run the ViewModel test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest"
```

Expected: compilation fails because the unified state and constructor do not exist.

- [ ] **Step 3: Replace HideDbgViewModel.kt with the tested state machine**

```kotlin
package ng1ok.yggdrasilmanager.ui.hidedbg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfig
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.filterInstalledApps
import ng1ok.yggdrasilmanager.data.hidedbg.findSharedUids

data class HideDbgUiState(
    val globalEnabled: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val enabledUids: Set<Int> = emptySet(),
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val loadingApps: Boolean = false,
    val appLoadError: String? = null,
    val globalPending: Boolean = false,
    val pendingUids: Set<Int> = emptySet(),
    val message: String? = null,
) {
    val visibleApps: List<InstalledApp>
        get() = filterInstalledApps(apps, query, filter)
    val sharedUids: Set<Int>
        get() = findSharedUids(apps)
}

class HideDbgViewModel(
    private val repo: YggdrasilRepository,
    private val configStore: HideDbgConfigStore,
    private val installedAppsSource: InstalledAppsSource,
) : ViewModel() {
    private val initialConfig = configStore.current
    private val _uiState = MutableStateFlow(
        HideDbgUiState(
            globalEnabled = initialConfig.globalEnabled,
            enabledUids = initialConfig.enabledUids,
            message = configStore.loadWarning,
        ),
    )
    val uiState: StateFlow<HideDbgUiState> = _uiState.asStateFlow()
    private val operationMutex = Mutex()

    init {
        refreshApps()
    }

    fun refreshApps() {
        if (_uiState.value.loadingApps) return
        _uiState.update { it.copy(loadingApps = true, appLoadError = null) }
        viewModelScope.launch {
            installedAppsSource.load().fold(
                onSuccess = { apps ->
                    _uiState.update {
                        it.copy(apps = apps, loadingApps = false, appLoadError = null)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            loadingApps = false,
                            appLoadError = error.message ?: "無法讀取已安裝 App",
                        )
                    }
                },
            )
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun setFilter(filter: AppFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun toggleUid(uid: Int) {
        val state = _uiState.value
        if (state.globalEnabled || state.globalPending || uid in state.pendingUids) return
        val enable = uid !in state.enabledUids
        _uiState.update { it.copy(pendingUids = it.pendingUids + uid) }

        viewModelScope.launch {
            operationMutex.withLock {
                applyUidToggle(uid, enable)
            }
        }
    }

    private suspend fun applyUidToggle(uid: Int, enable: Boolean) {
        val result = try {
            repo.setHideDbg(enable, uid)
        } catch (error: RuntimeException) {
            Result.failure(error)
        }
        if (result.isFailure) {
            _uiState.update {
                it.copy(
                    pendingUids = it.pendingUids - uid,
                    message = result.exceptionOrNull()?.message ?: "Hide DBG 操作失敗",
                )
            }
            return
        }

        val changedUids = _uiState.value.enabledUids.let { current ->
            if (enable) current + uid else current - uid
        }
        _uiState.update { it.copy(enabledUids = changedUids) }
        val saveResult = configStore.save(
            HideDbgConfig(
                globalEnabled = _uiState.value.globalEnabled,
                enabledUids = changedUids,
            ),
        )
        _uiState.update {
            it.copy(
                pendingUids = it.pendingUids - uid,
                message = if (saveResult.isFailure) "已套用但未保存" else it.message,
            )
        }
    }

    fun toggleGlobal() {
        val state = _uiState.value
        if (state.globalPending || state.pendingUids.isNotEmpty()) return
        val enable = !state.globalEnabled
        _uiState.update { it.copy(globalPending = true) }

        viewModelScope.launch {
            operationMutex.withLock {
                applyGlobalToggle(enable)
            }
        }
    }

    private suspend fun applyGlobalToggle(enable: Boolean) {
        val result = try {
            repo.setHideDbg(enable, null)
        } catch (error: RuntimeException) {
            Result.failure(error)
        }
        if (result.isFailure) {
            _uiState.update {
                it.copy(
                    globalPending = false,
                    message = result.exceptionOrNull()?.message ?: "Hide DBG 操作失敗",
                )
            }
            return
        }

        val changedUids = if (enable) _uiState.value.enabledUids else emptySet()
        _uiState.update {
            it.copy(globalEnabled = enable, enabledUids = changedUids)
        }
        val saveResult = configStore.save(HideDbgConfig(enable, changedUids))
        _uiState.update {
            it.copy(
                globalPending = false,
                message = if (saveResult.isFailure) "已套用但未保存" else it.message,
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                HideDbgViewModel(
                    repo = YggdrasilRepository.instance,
                    configStore = app.hideDbgConfigStore,
                    installedAppsSource = app.installedAppsSource,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Verify GREEN and commit the state machine**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest"
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgViewModelTest.kt
git commit -m "feat: manage Hide DBG app state by UID"
```

Expected: focused test exits 0, including the assertion that initialization sends zero KPM writes.

> **End of superseded Task 7H.** Normative implementation instructions resume with Task 8.

### Task 8: Render the Searchable Hide DBG App List

**Files:**

- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt`
- Replace: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgScreen.kt`
- Test: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgPresentationTest.kt`

- [ ] **Step 1: Write the failing switch-availability presentation test**

```kotlin
package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgUiState
import ng1ok.yggdrasilmanager.ui.hidedbg.canToggleUid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideDbgPresentationTest {
    @Test
    fun perAppSwitchesRespectGlobalAndPendingState() {
        assertTrue(HideDbgUiState().canToggleUid(1000))
        assertFalse(HideDbgUiState(globalEnabled = true).canToggleUid(1000))
        assertFalse(HideDbgUiState(globalPending = true).canToggleUid(1000))
        assertFalse(HideDbgUiState(pendingUids = setOf(1000)).canToggleUid(1000))
        assertTrue(HideDbgUiState(pendingUids = setOf(1001)).canToggleUid(1000))
    }
}
```

- [ ] **Step 2: Run the presentation test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgPresentationTest"
```

Expected: compilation fails because `canToggleUid` does not exist.

- [ ] **Step 3: Add the tested UI decision to HideDbgViewModel.kt**

Add directly after `HideDbgUiState`:

```kotlin
fun HideDbgUiState.canToggleUid(uid: Int): Boolean =
    !globalEnabled && !globalPending && uid !in pendingUids
```

- [ ] **Step 4: Replace HideDbgScreen.kt with the complete app-list UI**

```kotlin
package ng1ok.yggdrasilmanager.ui.hidedbg

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp

@Composable
fun HideDbgScreen(vm: HideDbgViewModel = viewModel(factory = HideDbgViewModel.Factory)) {
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            vm.dismissMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        HideDbgContent(
            state = state,
            onToggleGlobal = vm::toggleGlobal,
            onQueryChange = vm::setQuery,
            onFilterChange = vm::setFilter,
            onRefresh = vm::refreshApps,
            onToggleUid = vm::toggleUid,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideDbgContent(
    state: HideDbgUiState,
    onToggleGlobal: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (AppFilter) -> Unit,
    onRefresh: () -> Unit,
    onToggleUid: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlobalSetting(
            checked = state.globalEnabled,
            pending = state.globalPending,
            enabled = state.pendingUids.isEmpty(),
            onToggle = onToggleGlobal,
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜尋 App") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onRefresh, enabled = !state.loadingApps) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新 App 列表")
                }
            },
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AppFilter.entries.forEachIndexed { index, filter ->
                SegmentedButton(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    shape = SegmentedButtonDefaults.itemShape(index, AppFilter.entries.size),
                    label = {
                        Text(
                            when (filter) {
                                AppFilter.ALL -> "全部"
                                AppFilter.USER -> "使用者"
                                AppFilter.SYSTEM -> "系統"
                            },
                        )
                    },
                )
            }
        }

        when {
            state.loadingApps && state.apps.isEmpty() -> {
                LoadingApps(Modifier.weight(1f))
            }
            state.appLoadError != null && state.apps.isEmpty() -> {
                AppLoadError(state.appLoadError, onRefresh, Modifier.weight(1f))
            }
            state.visibleApps.isEmpty() -> EmptyApps(Modifier.weight(1f))
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(state.visibleApps, key = InstalledApp::packageName) { app ->
                        AppRow(
                            app = app,
                            sharedUid = app.uid in state.sharedUids,
                            checked = app.uid in state.enabledUids,
                            pending = app.uid in state.pendingUids,
                            enabled = state.canToggleUid(app.uid),
                            onToggle = { onToggleUid(app.uid) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSetting(
    checked: Boolean,
    pending: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("全局 Hide DBG", style = MaterialTheme.typography.titleMedium)
                Text(
                    "對所有進程隱藏調試器狀態",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.width(52.dp).height(48.dp), contentAlignment = Alignment.Center) {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = checked,
                        onCheckedChange = { onToggle() },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    sharedUid: Boolean,
    checked: Boolean,
    pending: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PackageAppIcon(app.packageName)
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                app.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append("UID ")
                    append(app.uid)
                    if (sharedUid) append(" · 共享 UID")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.width(52.dp).height(48.dp), contentAlignment = Alignment.Center) {
            if (pending) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun PackageAppIcon(packageName: String) {
    val packageManager = LocalContext.current.packageManager
    val drawable by produceState<Drawable?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
    }
    val iconDrawable = drawable
    if (iconDrawable == null) {
        Icon(
            Icons.Default.Apps,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        AndroidView(
            factory = { context ->
                ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            },
            update = { it.setImageDrawable(iconDrawable) },
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun LoadingApps(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AppLoadError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
            Text("重試")
        }
    }
}

@Composable
private fun EmptyApps(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text("沒有符合條件的 App", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **Step 5: Verify presentation logic, all Hide DBG tests, build, and lint**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgPresentationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HideDbgViewModelTest"
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.InstalledAppsTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Expected: all commands exit 0. App rows keep fixed icon/switch dimensions, long labels ellipsize, and global mode leaves per-App rules visible but disabled.

- [ ] **Step 6: Commit the Hide DBG UI**

```powershell
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgViewModel.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hidedbg/HideDbgScreen.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HideDbgPresentationTest.kt
git commit -m "feat: add searchable Hide DBG app controls"
```

### Task 9: Run Full Verification and Device Acceptance

**Files:**

- Verify only; modify production or test files only if a verification failure reveals a defect, and use a new failing regression test before each correction.

- [ ] **Step 1: Run every JVM test from a fresh invocation**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, zero failed tests.

- [ ] **Step 2: Build the complete Debug APK including JNI/CMake**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Run Android lint and repository whitespace checks**

```powershell
.\gradlew.bat :app:lintDebug
git diff --check
git status --short
```

Expected: lint/build exit 0, `git diff --check` prints nothing, and status contains only intentional task changes or is clean after commits.

- [ ] **Step 4: Inspect the final requirement diff**

```powershell
git diff HEAD~8..HEAD -- app/src/main docs/superpowers/specs/2026-07-14-manager-ux-and-app-hide-dbg-design.md
```

Confirm all eight acceptance criteria in the design spec have a corresponding implementation/test and that no Yggdrasil version query or startup KPM replay was introduced.

- [ ] **Step 5: Run device checks when a device becomes available**

```powershell
adb devices
.\gradlew.bat :app:connectedDebugAndroidTest
```

On a Root-capable device with Yggdrasil loaded, manually verify: denied Root shows one dismissible warning without gating; Home has no overlap in light/dark and large font; both child screens show a back arrow and no bottom bar; current-user system/user apps appear and filter correctly; shared UID switches move together; restarting the Manager restores switches without issuing Hide DBG writes; disabling global clears every App switch.

Expected today: `adb devices` may list no target. Record device verification as unavailable rather than claiming it passed.
