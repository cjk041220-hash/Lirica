package com.xiaoxi.android.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xiaoxi.android.common.logging.DiagnosticExporter
import com.xiaoxi.android.common.permissions.PermissionItemState
import com.xiaoxi.android.common.permissions.PermissionManager
import com.xiaoxi.android.common.settings.ByokSettingsRepository
import com.xiaoxi.android.network.gateway.ConnectivityResult
import com.xiaoxi.android.network.gateway.LlmConnectivityTester
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    byokRepository: ByokSettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val settingsState by byokRepository.settings.collectAsState()

    var apiKeyInput by remember(settingsState.customApiKey) {
        mutableStateOf(settingsState.customApiKey ?: "")
    }
    var baseUrlInput by remember(settingsState.customBaseUrl) {
        mutableStateOf(settingsState.customBaseUrl ?: ByokSettingsRepository.DEFAULT_BASE_URL)
    }
    var modelInput by remember(settingsState.customModel) {
        mutableStateOf(settingsState.customModel ?: ByokSettingsRepository.DEFAULT_MODEL)
    }
    var selectedProvider by remember(settingsState.customProvider) {
        mutableStateOf(settingsState.customProvider ?: LlmProvider.DEEPSEEK)
    }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    // 连通性测试状态
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectivityResult?>(null) }
    val connectivityTester = remember { LlmConnectivityTester() }

    // 权限体检列表状态（在 ON_RESUME 时自动刷新）
    var permissionsState by remember {
        mutableStateOf(PermissionManager.checkAll(context))
    }

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
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("设置与系统体检", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Text(
                        text = "‹ 返回",
                        modifier = Modifier
                            .clickable { onBack() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 模块 1：大模型 BYOK 网关配置
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🤖 大模型网关配置 (BYOK)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("支持 OpenAI 兼容格式 (DeepSeek, Qwen, Moonshot, SiliconFlow) 及 Claude 原生协议。", style = MaterialTheme.typography.bodySmall)

                    // 供应商选择
                    ExposedDropdownMenuBox(
                        expanded = providerDropdownExpanded,
                        onExpandedChange = { providerDropdownExpanded = !providerDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedProvider.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("协议供应商") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = providerDropdownExpanded,
                            onDismissRequest = { providerDropdownExpanded = false }
                        ) {
                            for (provider in LlmProvider.values()) {
                                DropdownMenuItem(
                                    text = { Text(provider.name) },
                                    onClick = {
                                        selectedProvider = provider
                                        providerDropdownExpanded = false
                                        when (provider) {
                                            LlmProvider.DEEPSEEK -> {
                                                baseUrlInput = "https://api.deepseek.com/v1"
                                                modelInput = "deepseek-chat"
                                            }
                                            LlmProvider.OPENAI -> {
                                                baseUrlInput = "https://api.openai.com/v1"
                                                modelInput = "gpt-4o"
                                            }
                                            LlmProvider.ANTHROPIC -> {
                                                baseUrlInput = "https://api.anthropic.com"
                                                modelInput = "claude-3-5-sonnet-20241022"
                                            }
                                            LlmProvider.CUSTOM -> {}
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Base URL
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("API Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Model Name
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("模型名称 (Model)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // API Key
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key (密文存储)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Text(
                                text = if (isPasswordVisible) "隐藏" else "显示",
                                modifier = Modifier
                                    .clickable { isPasswordVisible = !isPasswordVisible }
                                    .padding(8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )

                    // 保存与连通性测试按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                byokRepository.saveCustomApiKey(apiKeyInput)
                                byokRepository.saveCustomBaseUrl(baseUrlInput)
                                byokRepository.saveCustomModel(modelInput)
                                byokRepository.saveCustomProvider(selectedProvider)
                                Toast.makeText(context, "配置已安全加密保存", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存配置")
                        }

                        OutlinedButton(
                            onClick = {
                                isTestingConnection = true
                                testResult = null
                                coroutineScope.launch {
                                    val testConfig = LlmConfig(
                                        apiKey = apiKeyInput,
                                        provider = selectedProvider,
                                        baseUrl = baseUrlInput,
                                        model = modelInput
                                    )
                                    testResult = connectivityTester.testConnection(testConfig)
                                    isTestingConnection = false
                                }
                            },
                            enabled = !isTestingConnection && apiKeyInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("探测中…")
                            } else {
                                Text("测试连通性")
                            }
                        }
                    }

                    // 连通性测试结果面板
                    testResult?.let { res ->
                        when (res) {
                            is ConnectivityResult.Success -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("✅ 握手成功 | 延迟: ${res.latencyMs}ms", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("模型响应摘要: \"${res.responseSnippet}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                            is ConnectivityResult.Failure -> {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        val codeText = res.statusCode?.let { " [HTTP $it]" } ?: ""
                                        Text("❌ 握手失败$codeText", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Text(res.diagnosticMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 模块 2：权限体检面板 (T1.9 常驻入口)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🛡️ 核心权限体检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "刷新",
                            modifier = Modifier
                                .clickable { permissionsState = PermissionManager.checkAll(context) }
                                .padding(4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    for (item in permissionsState) {
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

            // 模块 3：系统诊断包导出 (T1.10)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📦 诊断包一键导出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("聚合设备环境、权限体检状态与近 100 条环形日志（API Key 自动脱敏），便于排查问题。", style = MaterialTheme.typography.bodySmall)

                    Button(
                        onClick = {
                            val file = DiagnosticExporter.exportDiagnosticFile(context, byokRepository)
                            Toast.makeText(context, "诊断报告已保存: ${file.name}", Toast.LENGTH_LONG).show()

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Lirica 运行诊断报告")
                                putExtra(Intent.EXTRA_TEXT, file.readText())
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享诊断报告"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出并分享诊断报告")
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    item: PermissionItemState,
    onGrantClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.isGranted) "✅" else if (item.type.isCrucial) "❌" else "⚠️",
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(item.type.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text(item.type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.details?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = if (item.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }

            if (!item.isGranted) {
                OutlinedButton(
                    onClick = onGrantClick,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("去授权", fontSize = 12.sp)
                }
            }
        }
    }
}
