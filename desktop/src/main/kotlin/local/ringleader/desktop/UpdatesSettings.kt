package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import local.ringleader.core.*

internal object DesktopUpdates {
    val checker by lazy { UpdateChecker(AppVersion.value, UpdatePreferences(AppData.configDir.resolve("updates.properties"), SaveStatus::notice)) }
}
/** Same claim as the README: two HTTPS requests, this User-Agent, nothing about you or your servers. */
internal const val UPDATE_CONSENT = "A check makes two HTTPS requests with User-Agent RingMASTER/<version> and nothing about you or your servers. GitHub sees normal web request details, including your IP address."
private val updateWhen = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(java.time.ZoneId.systemDefault())
private val updateDay = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").withZone(java.time.ZoneId.systemDefault())
internal fun updateStateWords(state: UpdateState, consent: String? = null): String = when(state) {
    UpdateState.Disabled -> "Update checks are disabled by your packager."
    UpdateState.NotConfigured -> "Update checks are not set up in this build."
    UpdateState.NeedsConsent -> "Choose whether RingMASTER may check for updates."
    is UpdateState.Idle -> if (consent == "no") "Update checks are off." else "Ready to check for updates."
    UpdateState.Checking -> "Checking the signed update feed..."
    is UpdateState.UpToDate -> state.message
    is UpdateState.Available -> "RingMASTER ${state.update.manifest.version} is available."
    is UpdateState.Skipped -> "Version ${state.version} is skipped."
    is UpdateState.Failed -> state.reason
}
/** File name, size and SHA-256 of the offered package. Shown as text; the app does not download it. */
internal fun offeredAssetLines(asset: UpdateAsset): List<String> {
    val name = runCatching {
        java.net.URI(asset.url).path.orEmpty().substringAfterLast('/').ifBlank { asset.url }
    }.getOrDefault(asset.url)
    return listOf(name, "${asset.size} bytes", asset.sha256)
}
@Composable internal fun UpdatesSettings(checker: UpdateChecker = DesktopUpdates.checker, preview: UpdateState? = null) {
    val actual by checker.state.collectAsState()
    val state = preview ?: actual
    val web = LocalAppHost.current.web
    val scope = rememberCoroutineScope()
    var automatic by remember { mutableStateOf(checker.preferences.automatic) }
    var browserNote by remember { mutableStateOf<String?>(null) }
    var consent by remember(preview) { mutableStateOf(if (preview == UpdateState.NeedsConsent) null else if (preview != null) "yes" else checker.preferences.consent) }
    val enabled = !web && state !in listOf(UpdateState.Disabled, UpdateState.NotConfigured)
    Text("Running ${AppVersion.value}",fontSize=18.sp,color=Tech.Ink)
    Text(if(web) "Update checks are available in the desktop app." else updateStateWords(state, consent),fontSize=13.sp,color=if(state is UpdateState.Available) Tech.Amber else Tech.Muted)
    Text("Last checked: ${checker.preferences.lastChecked?.let(updateWhen::format) ?: "Never"}",fontSize=11.sp,fontFamily=Tech.Mono,color=Tech.Muted)
    if (enabled && consent != "yes") {
        Text(UPDATE_CONSENT,fontSize=12.sp,color=Tech.Muted)
        MiniAction("Enable update checks") { checker.consent(true); consent="yes" }
    }
    MiniAction("Check now",enabled=enabled && consent == "yes" && state != UpdateState.Checking) { scope.launch { checker.check() } }
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        TechSwitch(automatic && consent == "yes", { automatic=it; checker.automatic(it) }, enabled=enabled && consent == "yes")
        Text("Check automatically",fontSize=13.sp,color=Tech.Ink)
    }
    fun open(url: String) {
        scope.launch {
            when (withContext(Dispatchers.IO) { openInBrowser(url) }) {
                BrowserLaunch.Failed -> { browserNote = null; SaveStatus.notice("The release page could not be opened: $url") }
                BrowserLaunch.Pending -> browserNote = "Browser launch still running."
                BrowserLaunch.Accepted -> browserNote = null
            }
        }
    }
    if (state is UpdateState.Available) {
        val m = state.update.manifest
        Column(Modifier.fillMaxWidth().border(1.dp,Tech.Amber,Tech.Control).background(Tech.Panel).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("RingMASTER ${m.version}",fontSize=18.sp,color=Tech.Amber)
            Text("Published ${updateDay.format(m.published)}",fontFamily=Tech.Mono,fontSize=11.sp,color=Tech.Muted)
            SelectionContainer { Text(m.notes,Modifier.fillMaxWidth().heightIn(max=170.dp).verticalScroll(rememberScrollState()),fontSize=12.sp,color=Tech.Ink) }
            SelectionContainer {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    offeredAssetLines(state.update.asset).forEach { line -> Text(line, Modifier.fillMaxWidth(), fontFamily = Tech.Mono, fontSize = 11.sp, color = Tech.Muted) }
                }
            }
            if(state.update.asset.platform != "windows-x64-msi") Text("The package installs with your system's package manager.",fontSize=12.sp,color=Tech.Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MiniAction("Open download page",primary=true,enabled=!web) { open(RELEASE_PAGE) }
                MiniAction("Skip this version",enabled=!web) { checker.skip(m.version) }
            }
        }
    }
    if (state is UpdateState.Skipped) MiniAction("Stop skipping ${state.version}",enabled=!web) { checker.unskip(); scope.launch { checker.check() } }
    if(state is UpdateState.Failed && state.releasePage != null) MiniAction("Open release page",enabled=!web) { state.releasePage?.let(::open) }
    browserNote?.let { Text(it, fontSize=12.sp, color=Tech.Muted) }
}
@Composable internal fun UpdateLifecycle() {
    if(LocalAppHost.current.web) return
    val checker = remember { DesktopUpdates.checker }
    val state by checker.state.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(checker) { val job=checker.schedule(scope); onDispose { job.cancel() } }
    var prompt by remember { mutableStateOf(false) }
    LaunchedEffect(state == UpdateState.NeedsConsent) { if(state == UpdateState.NeedsConsent) { delay(5000); prompt=true } }
    if(prompt && state == UpdateState.NeedsConsent) UpdateConsentDialog({ yes ->
        checker.consent(yes); prompt=false
        if (yes) scope.launch { checker.check() }
    }) { prompt=false }
}

/**
 * The first-run question. Only the two buttons call [onAnswer]. Escape calls [onClose], which puts the question away
 * with no answer saved, so it is asked again on a later start; a click outside does nothing (a confirmation).
 */
@Composable internal fun UpdateConsentDialog(onAnswer: (Boolean) -> Unit, onClose: () -> Unit) {
    TechDialog("Check for updates?", onClose, subtitle="Your choice can be changed in Settings.",actions={
        TechAction("Check automatically",active=true) { onAnswer(true) }
        TechAction("Not now") { onAnswer(false) }
    }) {
        Text(UPDATE_CONSENT,fontSize=13.sp,color=Tech.Ink)
    }
}
