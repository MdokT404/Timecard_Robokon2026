package com.example.timecard_robokon2026
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable
fun App(onExportRequested: (String, String) -> Unit) {
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        var showExportDialog by remember { mutableStateOf(false) }
        var selectedFilter by remember { mutableStateOf("全期間") }
        var customDaysInput by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Compose: $greeting")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { showExportDialog = true }) {
                Text("ログを出力する")
            }
            if (showExportDialog) {
                AlertDialog(
                    onDismissRequest = { showExportDialog = false },
                    title = {Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("出力期間の指定")
                            IconButton(onClick = { showExportDialog = false }) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    },
                    text = {
                        Column {
                            val options = listOf("1時間", "3時間", "1日", "1週間", "1ヶ月", "1年", "全期間", "任意入力期間")
                            options.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                    RadioButton(selected = (selectedFilter == option), onClick = { selectedFilter = option })
                                    Text(option, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                            if (selectedFilter == "任意入力期間") {
                                OutlinedTextField(value = customDaysInput, onValueChange = { customDaysInput = it }, label = { Text("何日前までのログを出力するか（日数）") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showExportDialog = false
                            onExportRequested(selectedFilter, customDaysInput) }) {
                            Text("CSV変換")
                        }
                    }
                )
            }
        }
    }
}