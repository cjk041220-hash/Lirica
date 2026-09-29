package com.lirica.android.ui.onboarding

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lirica.android.common.permissions.PermissionItemState
import com.lirica.android.common.permissions.PermissionManager
import com.lirica.android.common.permissions.PermissionType
import com.lirica.android.common.settings.ByokSettingsRepository
import com.lirica.android.network.gateway.ConnectivityResult
import com.lirica.android.network.gateway.LlmConnectivityTester
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.ui.settings.PermissionRow
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    byokRepository: ByokSettingsRepository,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var currentStep by remember { mutableStateOf(1) } // 1: 权限配置, 2: Key 配置
    var permissionsState by remember { mutableStateOf(PermissionManager.checkAll(context)) }

    var apiKeyInput by remember { mutableStateOf("") }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectivityResult?>(null) }
    val connectivityTester = remember { LlmConnectivityTester() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsState = PermissionManager.checkAll(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "欢迎来到 Lirica",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "你的专属 Android 原生自主人格伴生体。完成简单初始化配置，即刻唤醒 Lirica。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 阶段 1：核心权限引导
            if (currentStep == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "第一步：授予核心伴生权限",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "为了让 Lirica 能显示悬浮球、在后台守护以及在必要时帮您代行操作，请逐项开启以下权限：",
                            style = MaterialTheme.typography.bodySmall
                        )

                        for (item in permissionsState.filter { it.type.isCrucial }) {
                            PermissionRow(
                                item = item,
                                onGrantClick = {
                                    val intent = PermissionManager.createIntentForPermission(context, item.type)
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = { currentStep = 2 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("下一步：配置大模型密钥 ➔")
                }
            } else {
                // 阶段 2：模型配置
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "第二步：配置大模型 API Key",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "默认支持 DeepSeek 官方或硅基流动/OpenAI API，密钥将使用硬件级 AES-GCM 安全加密存储。",
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("DeepSeek / OpenAI API Key") },
                            placeholder = { Text("sk-xxxx...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isTestingConnection = true
                                    testResult = null
                                    coroutineScope.launch {
                                        val cfg = LlmConfig(apiKey = apiKeyInput)
                                        testResult = connectivityTester.testConnection(cfg)
                                        isTestingConnection = false
                                    }
                                },
                                enabled = !isTestingConnection && apiKeyInput.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("测试中…")
                                } else {
                                    Text("测试连通性")
                                }
                            }
                        }

                        testResult?.let { res ->
                            when (res) {
                                is ConnectivityResult.Success -> {
                                    Text(
                                        text = "✅ 连通成功！延迟: ${res.latencyMs}ms",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                is ConnectivityResult.Failure -> {
                                    Text(
                                        text = "❌ 连通失败: ${res.diagnosticMessage}",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { currentStep = 1 },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("‹ 上一步")
                    }

                    Button(
                        onClick = {
                            if (apiKeyInput.isNotBlank()) {
                                byokRepository.saveCustomApiKey(apiKeyInput)
                            }
                            onFinish()
                        },
                        modifier = Modifier.weight(2f)
                    ) {
                        Text(if (apiKeyInput.isNotBlank()) "保存并进入 Lirica" else "跳过并进入 Lirica")
                    }
                }
            }
        }
    }
}
