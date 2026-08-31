package com.mosstts.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mosstts.app.ui.screens.HistoryScreen
import com.mosstts.app.ui.screens.HomeScreen
import com.mosstts.app.ui.screens.ModelsScreen
import com.mosstts.app.ui.screens.SettingsScreen
import com.mosstts.app.ui.screens.VoiceCloneScreen
import com.mosstts.app.ui.theme.MossTTSTheme
import com.mosstts.app.viewmodel.ModelViewModel
import com.mosstts.app.viewmodel.TTSViewModel

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    data object Home : Screen("home", R.string.nav_home, Icons.Default.Headphones)
    data object Voice : Screen("voice", R.string.nav_voice, Icons.Default.Mic)
    data object History : Screen("history", R.string.nav_history, Icons.Default.Menu)
    data object Models : Screen("models", R.string.nav_models, Icons.Default.Storage)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

val screens = listOf(Screen.Home, Screen.Voice, Screen.History, Screen.Models, Screen.Settings)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限结果处理 */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(MossTTSApp.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用 edge-to-edge 显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 状态栏和导航栏完全透明，让内容延伸到摄像头区域
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        requestNeededPermissions()

        // 读取偏好设置
        val prefs = getSharedPreferences("mosstts_settings", MODE_PRIVATE)
        val darkMode = prefs.getString("dark_mode_string", "system") ?: "system"

        setContent {
            var currentDarkMode by remember { mutableStateOf(darkMode) }
            var hideNav by remember { mutableStateOf(false) }
            // 监听设置变化
            LaunchedEffect(Unit) {
                (application as MossTTSApp).preferences.darkMode.collect {
                    currentDarkMode = it
                }
            }
            // 监听隐藏导航条设置变化
            LaunchedEffect(Unit) {
                (application as MossTTSApp).preferences.hideNavigationBar.collect { hide ->
                    hideNav = hide
                    window.decorView.post {
                        hideNavigationBar(hide)
                    }
                }
            }
            MossTTSTheme(darkMode = currentDarkMode) {
                // 根据主题设置状态栏图标颜色
                val isDark = when (currentDarkMode) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                }
                LaunchedEffect(isDark) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                }
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(onHideNavigationBarChanged = { hide ->
                        hideNavigationBar(hide)
                    })
                }
            }
        }

    }

    /**
     * 隐藏或显示系统导航条（小横条）。
     */
    private fun hideNavigationBar(hide: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            // 隐藏导航条，滑动屏幕边缘可临时显示
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // 恢复默认
            controller.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            // Android 13+ 需要通知权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onHideNavigationBarChanged: ((Boolean) -> Unit)? = null) {
    val navController = rememberNavController()
    val ttsViewModel: TTSViewModel = viewModel()
    val modelViewModel: ModelViewModel = viewModel()
    var pendingText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MOSS TTS Nano", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
        bottomBar = {
            val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            // 毛玻璃效果的底部导航栏
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            ),
                            startY = 0f,
                            endY = 100f,
                        )
                    )
                    .drawWithContent {
                        drawContent()
                        // 顶部边框线（颜色在外部获取）
                        drawLine(
                            color = borderColor,
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                            strokeWidth = 1f,
                        )
                    }
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(top = 1.dp),
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                            label = { Text(stringResource(screen.titleRes), style = MaterialTheme.typography.labelSmall) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    ttsViewModel = ttsViewModel,
                    modelViewModel = modelViewModel,
                    pendingText = pendingText,
                    onPendingTextConsumed = { pendingText = null },
                )
            }
            composable(Screen.Voice.route) {
                VoiceCloneScreen(ttsViewModel = ttsViewModel)
            }
            composable(Screen.History.route) {
                HistoryScreen(
                    ttsViewModel = ttsViewModel,
                    onNavigateToHome = { text ->
                        pendingText = text
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Screen.Models.route) {
                ModelsScreen(modelViewModel = modelViewModel, ttsViewModel = ttsViewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    ttsViewModel = ttsViewModel,
                    onHideNavigationBarChanged = onHideNavigationBarChanged,
                )
            }
        }
    }
}
