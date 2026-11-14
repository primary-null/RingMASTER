package local.ringleader.core
import kotlin.test.*
class StoragePermissionAlternativesTest {
    @Test fun alternativeNetworkPrivilegesPreserveRequiredStoragePrivileges() {
        val row=FEATURE_REQUIREMENTS.single { it.id == "storage-url" }
        val node=PermissionDump(mapOf("/" to mapOf("Datastore.AllocateTemplate" to true,"Sys.AccessNetwork" to true)))
        val root=PermissionDump(mapOf("/" to mapOf("Datastore.AllocateTemplate" to true,"Sys.Audit" to true,"Sys.Modify" to true)))
        for(dump in listOf(node,root)) assertEquals(FeatureStatus.Allowed,evaluateFeatures(dump,PermissionTargets(),requirements=listOf(row)).single().status)
        val missing=PermissionDump(mapOf("/" to mapOf("Sys.AccessNetwork" to true)))
        assertNotEquals(FeatureStatus.Allowed,evaluateFeatures(missing,PermissionTargets(),requirements=listOf(row)).single().status)
    }
}
