package com.intentplayer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosisScreen(
    viewModel: MainViewModel,
    onSelectFolderClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit
) {
    val diagnosisResults by viewModel.diagnosisResults.collectAsState()

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.runSelfDiagnosis()
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.runSelfDiagnosis()
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.runSelfDiagnosis()
    }

    LaunchedEffect(Unit) {
        viewModel.runSelfDiagnosis()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自己診断ツール") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(MainViewModel.AppScreen.MAIN) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.runSelfDiagnosis() }) {
                        Text("再診断")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(diagnosisResults) { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.isOk) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (result.isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (result.isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = result.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (result.isOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.isOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
                        )

                        if (!result.isOk && result.actionType != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    when (result.actionType) {
                                        MainViewModel.DiagnosisResult.ActionType.REQUEST_NOTIFICATION -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }
                                        MainViewModel.DiagnosisResult.ActionType.REQUEST_STORAGE -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                storageLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                                            } else {
                                                storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                            }
                                        }
                                        MainViewModel.DiagnosisResult.ActionType.OPEN_BATTERY_SETTINGS -> {
                                            onBatteryOptimizationClick()
                                        }
                                        MainViewModel.DiagnosisResult.ActionType.REQUEST_BLUETOOTH -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                            }
                                        }
                                        MainViewModel.DiagnosisResult.ActionType.SELECT_FOLDER -> {
                                            onSelectFolderClick()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("修復する")
                            }
                        }
                    }
                }
            }
        }
    }
}
