package com.cmsoft.horizonstream.manual
import androidx.compose.ui.res.stringResource
import com.cmsoft.horizonstream.R

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cmsoft.horizonstream.common.ManualHost
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.common.ext.viewModelFactory
import com.cmsoft.horizonstream.common.getDatabase
import com.cmsoft.horizonstream.lib.RegistInfo
import com.cmsoft.horizonstream.lib.Target
import com.cmsoft.horizonstream.regist.RegistExecuteViewModel
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch

private enum class OnboardingStep { DISCOVER, SIGN_IN, LINK_DEVICE }

/**
 * The normal first-console experience. It deliberately keeps redirect URLs and Account IDs out
 * of the UI: the QR transfer is the supported path, while the old editor remains an advanced
 * recovery route for legacy consoles and unusual network setups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleOnboardingScreen(navController: NavController, discoveredHost: String?) {
    val context = LocalContext.current
    val database = remember { getDatabase(context) }
    val preferences = remember { Preferences(context) }
    val scope = rememberCoroutineScope()
    val registViewModel: RegistExecuteViewModel = viewModel(
        factory = viewModelFactory { RegistExecuteViewModel(database) }
    )
    val registState by registViewModel.state.observeAsState(RegistExecuteViewModel.State.IDLE)

    var accountId by remember { mutableStateOf(preferences.psnAccountId) }
    var step by remember {
        mutableStateOf(
            when {
                discoveredHost.isNullOrBlank() -> OnboardingStep.DISCOVER
                accountId.isNullOrBlank() -> OnboardingStep.SIGN_IN
                else -> OnboardingStep.LINK_DEVICE
            }
        )
    }
    var hostAddress by remember { mutableStateOf(discoveredHost.orEmpty()) }
    var showManualAddress by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var retrievingAccount by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var consoleVersion by remember { mutableStateOf(ConsoleVersion.PS5) }

    val saveFallbackError = stringResource(R.string.console_onboarding_save_error)
    fun retrieveAccountId(redirectUrl: String) {
        scope.launch {
            retrievingAccount = true
            lookupError = null
            try {
                accountId = PsnAccountIdLogin.retrieveAccountId(redirectUrl)
                preferences.psnAccountId = accountId
                step = OnboardingStep.LINK_DEVICE
            } catch (error: PsnLoginException) {
                lookupError = context.getString(error.resId)
            } finally {
                retrievingAccount = false
            }
        }
    }

    LaunchedEffect(registState) {
        if (registState == RegistExecuteViewModel.State.SUCCESSFUL) navController.popBackStack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.console_onboarding_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF020617)))
            ).padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.width(600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Progress(step)
                when (step) {
                    OnboardingStep.DISCOVER -> DiscoveryStep(
                        address = hostAddress,
                        showManualAddress = showManualAddress,
                        onShowManualAddress = { showManualAddress = true },
                        onAddressChange = { hostAddress = it },
                        onContinue = { step = if (accountId.isNullOrBlank()) OnboardingStep.SIGN_IN else OnboardingStep.LINK_DEVICE },
                        onAdvanced = { navController.navigate("edit_manual_console/0") }
                    )
                    OnboardingStep.SIGN_IN -> SignInStep(
                        isRetrieving = retrievingAccount,
                        error = lookupError,
                        onScan = { showScanner = true },
                        onAdvanced = { navController.navigate("edit_manual_console/0") }
                    )
                    OnboardingStep.LINK_DEVICE -> LinkDeviceStep(
                        version = consoleVersion,
                        pin = pin,
                        onVersionChange = { consoleVersion = it },
                        onPinChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                        onPair = {
                            val target = when (consoleVersion) {
                                ConsoleVersion.PS5 -> Target.PS5_1
                                ConsoleVersion.PS4_GE_8 -> Target.PS4_10
                                ConsoleVersion.PS4_GE_7 -> Target.PS4_9
                                ConsoleVersion.PS4_LT_7 -> Target.PS4_8
                            }
                            val id = accountId ?: return@LinkDeviceStep
                            database.manualHostDao().insert(ManualHost(host = hostAddress, registeredHost = null))
                                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                                .subscribe({ manualId ->
                                    registViewModel.start(
                                        RegistInfo(target, hostAddress, false, null, Base64.decode(id, Base64.DEFAULT), pin.toInt()),
                                        manualId
                                    )
                                }, { lookupError = saveFallbackError })
                        },
                        onOlderConsole = { navController.navigate("edit_manual_console/0") }
                    )
                }
            }
        }
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AccountIdQrScanner(
                onTransferScanned = { transfer ->
                    showScanner = false
                    when (transfer) {
                        is PsnQrTransfer.AccountId -> {
                            accountId = transfer.value
                            preferences.psnAccountId = accountId
                            step = OnboardingStep.LINK_DEVICE
                        }
                        is PsnQrTransfer.RedirectUrl -> retrieveAccountId(transfer.value)
                    }
                },
                onDismissRequest = { showScanner = false }
            )
        }
    }

    when (registState) {
        RegistExecuteViewModel.State.RUNNING -> ProgressOverlay(stringResource(R.string.console_onboarding_progress_title), stringResource(R.string.console_onboarding_progress_detail))
        RegistExecuteViewModel.State.FAILED -> FailureOverlay(
            title = stringResource(R.string.console_onboarding_failed_title),
            detail = stringResource(R.string.console_onboarding_failed_detail),
            onRetry = { navController.popBackStack() }, onAdvanced = { navController.navigate("edit_manual_console/0") },
            retryLabel = stringResource(R.string.console_onboarding_failed_retry)
        )
        RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE -> FailureOverlay(
            title = stringResource(R.string.console_onboarding_duplicate_title), detail = stringResource(R.string.console_onboarding_duplicate_detail),
            onRetry = { registViewModel.saveHost() }, onAdvanced = { registViewModel.stop() }, retryLabel = stringResource(R.string.console_onboarding_duplicate_replace), secondaryLabel = stringResource(R.string.console_onboarding_duplicate_keep)
        )
        else -> Unit
    }
}

@Composable private fun Progress(step: OnboardingStep) {
    val labels = listOf(stringResource(R.string.console_onboarding_step_find), stringResource(R.string.console_onboarding_step_signin), stringResource(R.string.console_onboarding_step_link))
    val current = step.ordinal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label -> Text("${if (index <= current) "●" else "○"} $label", color = if (index <= current) MaterialTheme.colorScheme.primary else Color.LightGray, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable private fun DiscoveryStep(address: String, showManualAddress: Boolean, onShowManualAddress: () -> Unit, onAddressChange: (String) -> Unit, onContinue: () -> Unit, onAdvanced: () -> Unit) {
    StepCard(stringResource(R.string.console_onboarding_discovery_title), stringResource(R.string.console_onboarding_discovery_detail)) {
        if (address.isNotBlank()) {
            StatusLine(true, stringResource(R.string.console_onboarding_console_found, address))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.console_onboarding_continue)) }
        } else {
            StatusLine(false, stringResource(R.string.console_onboarding_no_console))
            Text(stringResource(R.string.console_onboarding_wait_hint), style = MaterialTheme.typography.bodyMedium)
            if (showManualAddress) OutlinedTextField(address, onAddressChange, label = { Text(stringResource(R.string.console_onboarding_ip_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (showManualAddress) Button(onClick = onContinue, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.console_onboarding_use_address)) }
            else TextButton(onClick = onShowManualAddress) { Text(stringResource(R.string.console_onboarding_enter_ip)) }
            TextButton(onClick = onAdvanced) { Text(stringResource(R.string.console_onboarding_advanced)) }
        }
    }
}

@Composable private fun SignInStep(isRetrieving: Boolean, error: String?, onScan: () -> Unit, onAdvanced: () -> Unit) {
    StepCard(stringResource(R.string.console_onboarding_signin_title), stringResource(R.string.console_onboarding_signin_detail)) {
        Text(stringResource(R.string.console_onboarding_signin_qr_hint), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onScan, enabled = !isRetrieving, modifier = Modifier.fillMaxWidth()) {
            if (isRetrieving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.console_onboarding_getting_account)) } else Text(stringResource(R.string.console_onboarding_scan_qr))
        }
        error?.let { StatusLine(false, it) }
        TextButton(onClick = onAdvanced) { Text(stringResource(R.string.console_onboarding_qr_not_working)) }
    }
}

@Composable private fun LinkDeviceStep(version: ConsoleVersion, pin: String, onVersionChange: (ConsoleVersion) -> Unit, onPinChange: (String) -> Unit, onPair: () -> Unit, onOlderConsole: () -> Unit) {
    StepCard(stringResource(R.string.console_onboarding_link_title), if (version.isPS5) stringResource(R.string.console_onboarding_link_detail_ps5) else stringResource(R.string.console_onboarding_link_detail_ps4)) {
        Text(stringResource(R.string.console_onboarding_link_code_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(pin, onPinChange, label = { Text(stringResource(R.string.console_onboarding_link_code_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        TextButton(onClick = { onVersionChange(if (version == ConsoleVersion.PS5) ConsoleVersion.PS4_GE_8 else ConsoleVersion.PS5) }) { Text(if (version.isPS5) stringResource(R.string.console_onboarding_using_ps4) else stringResource(R.string.console_onboarding_using_ps5)) }
        Button(onClick = onPair, enabled = pin.length == 8, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.console_onboarding_pair_save)) }
        TextButton(onClick = onOlderConsole) { Text(stringResource(R.string.console_onboarding_older_ps4)) }
    }
}

@Composable private fun StepCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172554))) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(description, style = MaterialTheme.typography.bodyLarge); content() }) }
}

@Composable private fun StatusLine(ok: Boolean, text: String) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = if (ok) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text(text, color = if (ok) Color(0xFFBBF7D0) else Color(0xFFFECACA)) } }

@Composable private fun ProgressOverlay(title: String, detail: String) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .8f)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) { CircularProgressIndicator(); Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text(detail) } } }

@Composable private fun FailureOverlay(title: String, detail: String, onRetry: () -> Unit, onAdvanced: () -> Unit, retryLabel: String = stringResource(R.string.failure_retry_start_over), secondaryLabel: String = stringResource(R.string.failure_advanced_setup)) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .86f)), contentAlignment = Alignment.Center) { Card(Modifier.padding(24.dp)) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(detail); Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(retryLabel) }; TextButton(onClick = onAdvanced, modifier = Modifier.align(Alignment.End)) { Text(secondaryLabel) } } } } }
