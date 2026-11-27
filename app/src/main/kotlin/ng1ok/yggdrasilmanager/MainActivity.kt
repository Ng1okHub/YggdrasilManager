package ng1ok.yggdrasilmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ng1ok.yggdrasilmanager.ui.navigation.AppDestination
import ng1ok.yggdrasilmanager.ui.navigation.NavGraph
import ng1ok.yggdrasilmanager.ui.theme.YggdrasilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = AppDestination.fromRoute(backStackEntry?.destination?.route)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = destination.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (destination.showBackButton) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    scrolledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            if (destination.showBottomNavigation) {
                NavigationBar {
                    AppDestination.topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
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
                                    imageVector = when (item) {
                                        AppDestination.Home -> if (item == destination) {
                                            Icons.Filled.Home
                                        } else {
                                            Icons.Outlined.Home
                                        }

                                        AppDestination.Features -> if (item == destination) {
                                            Icons.Filled.List
                                        } else {
                                            Icons.AutoMirrored.Outlined.List
                                        }

                                        else -> requireNotNull(item.bottomBarIcon)
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavGraph(navController = navController, modifier = Modifier.padding(innerPadding))
    }
}
