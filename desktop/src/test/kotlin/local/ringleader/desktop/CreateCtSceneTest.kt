package local.ringleader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.*
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.yield
import local.ringleader.core.CreateContext
import local.ringleader.core.CreateFields
import local.ringleader.core.CreateParam
import local.ringleader.core.CreateRequest
import local.ringleader.core.CreateValue
import local.ringleader.core.CtDraft
import local.ringleader.core.CtMount
import local.ringleader.core.FreezeResult
import local.ringleader.core.IdMap
import local.ringleader.core.IdRange
import local.ringleader.core.Ip6Mode
import local.ringleader.core.IpMode
import local.ringleader.core.MountSource
import local.ringleader.core.NewGuestKind
import local.ringleader.core.ParamLine
import local.ringleader.core.SecretState
import local.ringleader.core.StorageChoice
import local.ringleader.core.demoCreateSession
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.Path
import kotlin.test.Test

/** Synthetic scene coverage for every container step; it performs no workspace or network action. */
class CreateCtSceneTest {
    @Test
    fun everyStepRendersAtTheMinimumWindow() = runSceneTest {
        Appearance.preview(light = false)
        for ((step, _) in CT_SCENE_STEPS) {
            val scene = ctScene(960, 640, step, scroll = 0)
            try { yield(); scene.render() } finally { scene.close() }
        }
    }

    @Test
    fun writeRequiredMatrixWhenRequested() = runSceneTest {
        val output = System.getenv("RINGMASTER_CT_SCENES")?.takeIf(String::isNotBlank)?.let(::Path)
            ?: return@runSceneTest
        Files.createDirectories(output)
        val sizes = listOf(960 to 640, 1600 to 1000)
        for (light in listOf(false, true)) {
            Appearance.preview(light = light)
            for ((width, height) in sizes) for ((step, title) in CT_SCENE_STEPS) for ((position, scroll) in listOf("top" to 0, "bottom" to 1_000_000)) {
                val scene = ctScene(width, height, step, scroll)
                try {
                    repeat(8) { scene.render((it + 1) * 100_000_000L).close(); yield() }
                    for (label in listOf("SIMULATE CREATE","CANCEL","NEXT","BACK")) {
                        val node = assertNotNull(allNodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true })
                        assertTrue(node.boundsInWindow.top >= 0 && node.boundsInWindow.bottom <= height * 1.25f)
                        assertTrue(node.boundsInWindow.right <= width * 1.25f)
                    }
                    val bytes = scene.render(900_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                    val theme = if (light) "light" else "dark"
                    Files.write(output.resolve("ct-$theme-${width}x$height-$title-$position.png"), bytes)
                } finally { scene.close() }
            }
        }
        Appearance.preview(light = false)
    }
}

private val CT_SCENE_STEPS = listOf(
    CreateFields.GENERAL to "general",
    CreateFields.TEMPLATE to "template",
    CreateFields.DISKS to "disks",
    CreateFields.CPU to "cpu",
    CreateFields.MEMORY to "memory",
    CreateFields.NETWORK to "network",
    CreateFields.DNS to "dns",
)

private fun kotlinx.coroutines.CoroutineScope.ctScene(width: Int, height: Int, step: String, scroll: Int) =
    ImageComposeScene((width * 1.25f).toInt(), (height * 1.25f).toInt(), Density(1.25f), coroutineContext = coroutineContext) {
        MaterialTheme(colorScheme = ringColors) {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(Modifier.fillMaxSize().background(Tech.Canvas)) {
                    CreateCtWizard(
                        session = ctSceneSession,
                        context = ctSceneContext,
                        initialDraft = ctSceneDraft,
                        initialStep = step,
                        initialAdvanced = true,
                        previewScroll = scroll,
                        onCreate = { _, secrets -> secrets.close() },
                        onCancel = {},
                        onRead = {},
                        onGetMedia = { _, _, _ -> },
                        validateDraft = { _, _ -> emptyMap() },
                        validateSecrets = { _, _, _ -> emptyMap() },
                        paramsFor = { _, _, state -> ctSceneParams(state) },
                        freezeDraft = { _, _ -> FreezeResult.Ready(ctSceneRequest) },
                        nextMount = { CtMount(key = "mp1", source = MountSource.New("archive", "12"), path = "/srv/archive") },
                    )
                }
            }
        }
    }

private val ctSceneSession = demoCreateSession(NewGuestKind.Container, "docs-node", 7L, Instant.parse("2026-11-12T12:00:00Z"))

internal val ctSceneContext = CreateContext(
    release = 9,
    hostArch = "x86_64",
    cgroupMode = 2,
    storages = listOf(
        StorageChoice("docs-node", "local", "dir", setOf("vztmpl", "rootdir"), false, 28L shl 30, 96L shl 30, "available"),
        StorageChoice("docs-node", "archive", "nfs", setOf("rootdir"), true, 120L shl 30, 512L shl 30, "available"),
    ),
    usedIds = setOf(101, 108),
    nodeCpus = 16,
    nodeMemoryBytes = 64L shl 30,
    nodeMemoryUsedBytes = 21L shl 30,
    choiceNode = "docs-node",
    volumes = local.ringleader.core.demoCreateVolumes,
    bridges = local.ringleader.core.demoCreateBridges,
    cpuModels = local.ringleader.core.demoCreateCpuModels,
    cpuFlags = local.ringleader.core.demoCreateCpuFlags,
)

internal val ctSceneDraft = CtDraft(
    node = "docs-node",
    vmid = "140",
    hostname = "archive.example.test",
    pool = "lab",
    tags = listOf("docs", "sample"),
    templateStorage = "local",
    template = "local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst",
    mounts = listOf(
        CtMount(source = MountSource.New("local", "8"), quota = false, mountOptions = setOf("noatime")),
        CtMount(
            key = "mp0",
            source = MountSource.New("archive", "24"),
            path = "/srv/archive",
            backup = true,
            readOnly = false,
            mountOptions = setOf("noatime", "nodev"),
            acl = true,
            idMap = IdMap.Ranges(listOf(IdRange('u', "0", "100000", "65536"), IdRange('g', "0", "100000", "65536"))),
        ),
    ),
    cores = "4",
    cpuLimit = "3.5",
    cpuUnits = "1024",
    memoryMib = "4096",
    swapMib = "1024",
    nic = local.ringleader.core.CtNic(
        name = "eth0",
        mac = "02:00:00:00:01:40",
        bridge = "vmbr0",
        vlan = "40",
        firewall = true,
        ipv4 = IpMode.Static("192.0.2.140/24", "192.0.2.1"),
        ipv6 = Ip6Mode.Static("2001:db8::140/64", "2001:db8::1"),
        mtu = "1",
        rate = "50",
        hostManaged = true,
    ),
    searchDomain = "example.test",
    nameservers = "192.0.2.53 2001:db8::53",
)

internal val ctSceneRequest = CreateRequest(
    NewGuestKind.Container,
    "docs-node",
    140,
    "archive.example.test",
    listOf(CreateParam("vmid", CreateValue.Int(140))),
)

internal fun ctSceneParams(state: SecretState): List<ParamLine> = listOf(
    ParamLine("vmid", "140"),
    ParamLine("hostname", "archive.example.test"),
    ParamLine("ostemplate", "local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst"),
    ParamLine("unprivileged", "1"),
    ParamLine("features", "nesting=1"),
    ParamLine("rootfs", "local:8,mountoptions=noatime"),
    ParamLine("mp0", "archive:24,mp=/srv/archive,acl=1"),
    ParamLine("cores", "4"),
    ParamLine("memory", "4096"),
    ParamLine("swap", "1024"),
    ParamLine("net0", "name=eth0,bridge=vmbr0,ip=192.0.2.140/24"),
    ParamLine("password", if (state.password) "set" else "not set", secret = true),
    ParamLine("ssh-public-keys", if (state.keyCount == 0) "not set" else "${state.keyCount} keys", secret = true),
)

