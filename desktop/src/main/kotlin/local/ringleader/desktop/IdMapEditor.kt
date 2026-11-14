package local.ringleader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ringleader.core.CtMount
import local.ringleader.core.IdMap
import local.ringleader.core.IdRange

/** Full Proxmox 9 container-mount ID mapping editor: default, passthrough, or explicit user/group ranges. */
@Composable
fun IdMapEditor(
    mount: CtMount,
    onMount: (CtMount) -> Unit,
    enabled: Boolean = true,
    error: String? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("ID mapping")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IdMapMode("Default", mount.idMap is IdMap.None, enabled) { onMount(mount.copy(idMap = IdMap.None)) }
            IdMapMode("Passthrough", mount.idMap is IdMap.Passthrough, enabled) { onMount(mount.copy(idMap = IdMap.Passthrough)) }
            IdMapMode("Ranges", mount.idMap is IdMap.Ranges, enabled) {
                onMount(mount.copy(idMap = IdMap.Ranges(listOf(IdRange('u', "0", "100000", "65536")))))
            }
        }
        when (val map = mount.idMap) {
            IdMap.None -> Text("Use the server's default unprivileged mapping.", color = Tech.Muted, fontSize = 11.sp)
            IdMap.Passthrough -> Text("Pass container IDs through unchanged. The server checks this privileged mapping.", color = Tech.Amber, fontSize = 11.sp)
            is IdMap.Ranges -> {
                map.rows.forEachIndexed { index, row ->
                    TechPanel(rail = if (row.type == 'u') Tech.Amber else Tech.Green, padding = 9.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.width(72.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (row.type == 'u') "USER" else "GROUP", color = Tech.Ink, fontFamily = Tech.Mono, fontSize = 11.sp)
                                MiniAction("Remove", enabled = enabled) {
                                    onMount(mount.copy(idMap = IdMap.Ranges(map.rows.filterIndexed { at, _ -> at != index })))
                                }
                            }
                            TechField(row.containerId, { value -> updateRange(mount, map, index, row.copy(containerId = value), onMount) }, "Container ID", Modifier.weight(1f).identity(), enabled = enabled)
                            TechField(row.hostId, { value -> updateRange(mount, map, index, row.copy(hostId = value), onMount) }, "Host ID", Modifier.weight(1f).identity(), enabled = enabled)
                            TechField(row.count, { value -> updateRange(mount, map, index, row.copy(count = value), onMount) }, "Count", Modifier.weight(1f).identity(), enabled = enabled)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniAction("Add user range", enabled = enabled) {
                        onMount(mount.copy(idMap = IdMap.Ranges(map.rows + IdRange('u', "", "", ""))))
                    }
                    MiniAction("Add group range", enabled = enabled) {
                        onMount(mount.copy(idMap = IdMap.Ranges(map.rows + IdRange('g', "", "", ""))))
                    }
                }
            }
        }
        error?.let { Text(it, color = Tech.Red, fontSize = 11.sp) }
    }
}

@Composable
private fun IdMapMode(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TechAction(label, active = active, enabled = enabled, onClick = onClick)
}

private fun updateRange(mount: CtMount, map: IdMap.Ranges, index: Int, row: IdRange, onMount: (CtMount) -> Unit) {
    onMount(mount.copy(idMap = IdMap.Ranges(map.rows.mapIndexed { at, old -> if (at == index) row else old })))
}

