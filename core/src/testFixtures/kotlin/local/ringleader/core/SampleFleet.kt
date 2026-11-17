package local.ringleader.core

import java.time.Instant

/*
 * The invented fleet the tests and the README pictures show: two nodes, three guests, four storages and three tasks.
 * Handwritten in the shapes the server sends. Nothing here was read from a server.
 */

const val SAMPLE_ADDRESS = "https://sample.example.test:8006"

private const val GIB = 1024L * 1024 * 1024

/** The sample fleet as the server at [remote] would report it: every record carries that connection's key. */
fun sampleOverview(remote: String = SAMPLE_ADDRESS): Overview = Overview(
    2,
    listOf(
        Guest(remote, 120, "debian-workbench", "demo-node-a", "VM", "running"),
        Guest(remote, 121, "web-services", "demo-node-a", "Container", "running", haState = "started"),
        Guest(remote, 130, "backup-lab", "demo-node-b", "VM", "stopped", lock = "backup"),
    ),
    members = listOf(
        RecordedMember(remote, "demo-node-a", "online", null, 0.25, 4.0, 2 * GIB, 8 * GIB, 5 * GIB, 32 * GIB, 3600),
        RecordedMember(remote, "demo-node-b", "online", "maintenance", 0.0, 4.0, null, 8 * GIB, 4 * GIB, 32 * GIB, 7200),
    ),
    storage = listOf(
        RecordedStorage(remote, "demo-node-a", "local", false, 2 * GIB, 40 * GIB, "available", "dir", "iso,vztmpl,import"),
        RecordedStorage(remote, "demo-node-a", "local-lvm", false, 10 * GIB, 40 * GIB, "available", "lvmthin", "images,rootdir"),
        RecordedStorage(remote, "demo-node-a", "shared-data", true, 20 * GIB, 100 * GIB, "available", "dir", "images,backup"),
        RecordedStorage(remote, "demo-node-b", "shared-data", true, 20 * GIB, 100 * GIB, "available", "dir", "images,backup"),
    ),
)

fun sampleDetails(guest: Guest): GuestDetails {
    val running = guest.status == "running"
    return GuestDetails(
        guest, guest.status, 4, if (running) 0.12 else 0.0,
        if (running) 2 * GIB else 0, 8 * GIB,
        if (guest.kind == "Container") 3 * GIB else null, 64 * GIB,
        if (running) 86400 else 0, 10L * 1024 * 1024, 4L * 1024 * 1024,
    )
}

/**
 * A task id a server could have sent for a task of [type] about [subject] on [node]. [serial] tells two tasks of one kind
 * about one subject apart.
 */
fun sampleUpid(type: String, subject: String, node: String = "demo-node-a", serial: Int = 0): String =
    "UPID:%s:%08X:%08X:%08X:%s:%s:tester@pve:".format(node, 4096 + serial, 1024 + serial, 0x67000000 + serial, type, subject)

private fun sampleTask(
    remote: String, serial: Int, node: String, type: String, subject: String, started: String, ended: String?, status: String?,
): ClusterTask {
    val endedAt = ended?.let(Instant::parse)
    return ClusterTask(remote, sampleUpid(type, subject, node, serial), node, type, subject, "tester@pve", Instant.parse(started), endedAt, status,
        classifyTask(status, endedAt))
}

/** A backup that is running, a start that ended well, and a task of a kind the app does not know that reports nothing. */
fun sampleTasks(remote: String = SAMPLE_ADDRESS): List<ClusterTask> = listOf(
    sampleTask(remote, 0, "demo-node-a", "vzdump", "121", "2026-11-07T15:04:00Z", null, "running"),
    sampleTask(remote, 1, "demo-node-a", "qmstart", "120", "2026-11-07T15:00:00Z", "2026-11-07T15:00:12Z", "OK"),
    sampleTask(remote, 2, "demo-node-b", "lab-maintenance", "130", "2026-11-07T14:00:00Z", null, null),
)
