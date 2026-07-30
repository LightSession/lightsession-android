package com.sample.lightsession

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightsession.LightSession

/**
 * Parts of one screen that are screens in their own right: tabs, a dialog, and a declared one.
 *
 * A tab and a modal are not navigations — the destination never changes — but to a person they are
 * places, and a map that cannot tell them apart from the screen underneath cannot answer where people
 * spend their time. The SDK reports them as `TabsAndModalActivity › <part>`.
 *
 * Three mechanisms, and they fail differently, so each is here on purpose:
 *
 *  * **Tabs**, read from Compose semantics. Material3's `Tab` reports `Role.Tab`, and the SDK
 *    identifies the chosen one by diffing against whatever was already selected on arrival — the
 *    screen can hold more than one thing claiming to be a tab, and semantics cannot rank them.
 *  * **The dialog**, seen as a new window rather than through semantics, since a dialog is not part
 *    of the Activity's view tree. Dismissing it returns to the part that was showing underneath,
 *    which is not necessarily the default tab.
 *  * **A declared sub-screen**, `setSubScreen`/`clearSubScreen`, for a part that neither mechanism
 *    can see — a bottom sheet drawn inside the layout, a wizard step, an expanded panel.
 *
 * Both automatic ones are switchable in config (`trackTabs`, `trackModals`), so this screen is also
 * where to check what turning them off actually costs.
 */
class TabsAndModalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var tab by remember { mutableIntStateOf(0) }
            var dialogOpen by remember { mutableStateOf(false) }
            var panelDeclared by remember { mutableStateOf(false) }
            val titles = listOf("Overview", "History", "Settings")

            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        titles.forEachIndexed { index, title ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(title) },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(titles[tab], fontSize = 26.sp)
                        Text(
                            "Expected as TabsAndModalActivity › ${titles[tab].lowercase()}.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )

                        Button(
                            onClick = { dialogOpen = true },
                            modifier = Modifier.padding(top = 32.dp),
                        ) { Text("Open a dialog") }

                        // Raised from whichever tab is showing, on purpose: closing it should return
                        // to that tab rather than to the screen's default.
                        Button(
                            onClick = {
                                if (panelDeclared) {
                                    LightSession.getInstance().clearSubScreen("filters")
                                } else {
                                    LightSession.getInstance().setSubScreen("filters")
                                }
                                panelDeclared = !panelDeclared
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(if (panelDeclared) "Clear declared sub-screen" else "Declare a sub-screen")
                        }
                    }
                }

                if (dialogOpen) {
                    AlertDialog(
                        onDismissRequest = { dialogOpen = false },
                        title = { Text("A modal") },
                        text = {
                            Text(
                                "Its own window, so the SDK sees it as one rather than reading it " +
                                    "from the view tree. Dismissing should return to " +
                                    "› ${titles[tab].lowercase()}, not to the first tab.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { dialogOpen = false }) { Text("Close") }
                        },
                    )
                }
            }
        }
    }
}
