package local.ringleader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Read of the signed-in identity's privileges. Direct Proxmox VE only. */
interface PermissionsGateway {
    suspend fun read(connection: Connection): PermissionDump
}

class LivePermissionsGateway(private val client: PveClient = PveClient()) : PermissionsGateway {
    override suspend fun read(connection: Connection): PermissionDump {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.permissions(connection) }
    }
}

/** Read-only node calls: services, system log pages and the cached package update list. Direct Proxmox VE only. */
interface NodeToolsGateway {
    suspend fun services(connection: Connection, node: String): List<NodeService>
    suspend fun syslog(connection: Connection, node: String, start: Int, limit: Int, service: String?): SyslogPage
    suspend fun updates(connection: Connection, node: String): List<PackageUpdate>
}

class LiveNodeToolsGateway(private val client: PveClient = PveClient()) : NodeToolsGateway {
    override suspend fun services(connection: Connection, node: String): List<NodeService> {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.nodeServices(connection, node) }
    }

    override suspend fun syslog(connection: Connection, node: String, start: Int, limit: Int, service: String?): SyslogPage {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.nodeSyslog(connection, node, start, limit, service) }
    }

    override suspend fun updates(connection: Connection, node: String): List<PackageUpdate> {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.nodeUpdates(connection, node) }
    }
}

/** Content list of one storage the user chose. Direct Proxmox VE only. Never the storage index. */
interface StorageContentGateway {
    suspend fun read(connection: Connection, node: String, storage: String, content: String?): List<StorageVolume>
}

class LiveStorageContentGateway(private val client: PveClient = PveClient()) : StorageContentGateway {
    override suspend fun read(connection: Connection, node: String, storage: String, content: String?): List<StorageVolume> {
        requireDirectRequest(connection)
        return runInterruptible(Dispatchers.IO) { client.storageContent(connection, node, storage, content) }
    }
}
