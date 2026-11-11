package local.ringleader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Handwritten fixtures shaped like `GET /access/permissions`. None is a captured server body: they check the parser and the
 * inheritance rules read from pve-access-control (RPCEnvironment::get_effective_permissions, AccessControl::roles).
 */
class PermissionsTest {
    private val targets = PermissionTargets(
        nodes = listOf("pve1", "pve4"),
        guests = listOf(100 to "VM", 101 to "Container", 102 to "VM"),
        storages = listOf("local", "local-lvm"),
    )

    private fun checks(json: String, targets: PermissionTargets = this.targets) = evaluateFeatures(parsePermissions(json), targets).associateBy { it.requirement.id }

    /** PVEAuditor as pve-access-control builds it, set on / with Propagate and listed on the fixed top-level paths. */
    private val auditor = """"Sys.Audit":1,"VM.Audit":1,"VM.GuestAgent.Audit":1,"Datastore.Audit":1,"Pool.Audit":1,"SDN.Audit":1,"Mapping.Audit":1"""
    private val auditorTree = """{"data":{"/":{$auditor},"/access":{$auditor},"/nodes":{$auditor},"/pool":{$auditor},"/sdn":{$auditor},"/storage":{$auditor},"/vms":{$auditor}}}"""

    // ---- parser ----

    @Test fun flagsReadAsNumbersBooleansAndStrings() {
        val dump = parsePermissions("""{"data":{"/vms":{"VM.Audit":1,"VM.Console":0,"VM.PowerMgmt":true,"VM.Backup":false,
            "VM.Snapshot":"1","VM.Clone":"0","Sys.Audit":null,"junk":1,"VM.Weird":"yes","VM.Nested":{"a":1}}}}""")
        val vms = dump.paths.getValue("/vms")
        assertEquals(true, vms["VM.Audit"])
        assertEquals(false, vms["VM.Console"])
        assertEquals(true, vms["VM.PowerMgmt"])
        assertEquals(false, vms["VM.Backup"])
        assertEquals(true, vms["VM.Snapshot"])
        assertEquals(false, vms["VM.Clone"])
        assertEquals(false, vms["VM.Weird"], "An odd flag is granted without Propagate, never inherited")
        assertFalse("Sys.Audit" in vms, "A null flag is not a grant")
        assertFalse("junk" in vms, "A name that is not a privilege is dropped")
        assertFalse("VM.Nested" in vms)
    }

    @Test fun pathsAreNormalizedAndBadOnesAreSkipped() {
        val dump = parsePermissions("""{"data":{"vms//101/":{"VM.Audit":1},"/nodes/pve 5":{"Sys.Audit":1},"/vms/../x":{"VM.Audit":1},
            "/":{"Sys.Audit":1},"/storage":[1,2],"/pool/a/b":{"Pool.Audit":1},"/empty":{}}}""")
        assertEquals(setOf("/vms/101", "/", "/pool/a/b"), dump.paths.keys)
        assertNull(normalizedAclPathOrNull("/nodes/pve%205"))
        assertNull(normalizedAclPathOrNull("/vms/./1"))
        assertNull(normalizedAclPathOrNull(""))
        assertEquals("/vms", normalizedAclPathOrNull("vms/"))
        assertEquals("/", normalizedAclPathOrNull("//"))
    }

    @Test fun emptyAndMalformedBodies() {
        assertTrue(parsePermissions("""{"data":{}}""").paths.isEmpty())
        assertTrue(parsePermissions("""{"data":[]}""").paths.isEmpty())
        assertFailsWith<IllegalStateException> { parsePermissions("""{"data":null}""") }
        assertFailsWith<IllegalStateException> { parsePermissions("""{}""") }
        assertFailsWith<IllegalStateException> { parsePermissions("not json") }
        assertFailsWith<IllegalStateException> { parsePermissions("""{"data":"x"}""") }
        assertFailsWith<IllegalStateException> { parsePermissions("""{"data":[1]}""") }
    }

    @Test fun anOversizedListIsRefusedNotTruncated() {
        val rows = (100..50_200).joinToString(",") { "\"/vms/$it\":{\"VM.Audit\":0}" }
        assertEquals(PERMISSIONS_TOO_LARGE, assertFailsWith<IllegalStateException> { parsePermissions("""{"data":{$rows}}""") }.message)
    }

    // ---- inheritance ----

    private val tree = parsePermissions("""{"data":{
        "/":{"Sys.Audit":1,"VM.Audit":1,"Datastore.Audit":1},
        "/vms":{"VM.Audit":1,"VM.PowerMgmt":1,"VM.Snapshot":0},
        "/vms/100":{"VM.Audit":0},
        "/nodes":{"Sys.Audit":1,"Sys.Console":0}
    }}""")

    @Test fun aListedPathIsAnsweredByItself() {
        val own = effectivePrivileges(tree, "/vms/100")
        assertEquals(setOf("VM.Audit"), own.privileges)
        assertTrue(own.exact)
        assertEquals("/vms/100", own.source)
        assertEquals(setOf("Sys.Audit", "VM.Audit", "Datastore.Audit"), effectivePrivileges(tree, "/").privileges)
    }

    @Test fun anUnlistedPathInheritsOnlyWhatPropagates() {
        val child = effectivePrivileges(tree, "/vms/101")
        assertEquals("/vms", child.source)
        assertFalse(child.exact)
        assertEquals(setOf("VM.Audit", "VM.PowerMgmt"), child.privileges)
        assertEquals(setOf("VM.Snapshot"), child.notInherited)
        val node = effectivePrivileges(tree, "/nodes/pve9")
        assertEquals(setOf("Sys.Audit"), node.privileges)
        assertEquals(setOf("Sys.Console"), node.notInherited)
    }

    @Test fun theNearestListedParentWinsOverAFartherOne() {
        // /vms/100 lists fewer privileges than /vms: a role set on the child replaces what it would inherit.
        assertEquals(setOf("VM.Audit"), effectivePrivileges(tree, "/vms/100").privileges)
        assertFalse("VM.PowerMgmt" in effectivePrivileges(tree, "/vms/100").privileges)
    }

    @Test fun pathsWithoutAListedParentFallBackToRootOrNothing() {
        assertEquals("/", effectivePrivileges(tree, "/storage/local").source)
        assertEquals(setOf("Sys.Audit", "VM.Audit", "Datastore.Audit"), effectivePrivileges(tree, "/mapping/notifications").privileges)
        val none = effectivePrivileges(parsePermissions("""{"data":{"/vms/100":{"VM.Audit":0}}}"""), "/nodes/pve1")
        assertNull(none.source)
        assertTrue(none.privileges.isEmpty())
    }

    @Test fun anInvalidPathGrantsNothingAndTrailingSlashesAreNormalized() {
        assertTrue(effectivePrivileges(tree, "/vms/../nodes").privileges.isEmpty())
        assertNull(effectivePrivileges(tree, "/vms/../nodes").source)
        assertEquals("/vms", effectivePrivileges(tree, "vms//101/").source)
    }

    @Test fun anEmptyPathTheServerOmitsIsAnsweredFromItsParent_knownLimit() {
        // A NoAccess role on /vms/100 leaves the identity with nothing, and the server drops paths with no privileges from the
        // dump. The resolver cannot see it and answers from /vms. GET /access/permissions?path=/vms/100 would answer exactly.
        val omitted = parsePermissions("""{"data":{"/vms":{"VM.PowerMgmt":1}}}""")
        assertEquals(setOf("VM.PowerMgmt"), effectivePrivileges(omitted, "/vms/100").privileges)
    }

    // ---- the table of requirements ----

    @Test fun everyRequirementUsesARealPrivilegeAndAValidAclPath() {
        val all = FEATURE_REQUIREMENTS.flatMap { it.needs + it.alsoNeeds.flatten() }
        assertTrue(all.isNotEmpty())
        for (need in all) {
            assertTrue(need.privileges.isNotEmpty())
            for (name in need.privileges) assertTrue(name in PVE_PRIVILEGES, "$name is not a Proxmox VE privilege")
            for (name in need.legacy) assertTrue(name in LEGACY_PRIVILEGES && name !in PVE_PRIVILEGES, "$name is not a legacy privilege")
            assertTrue(aclPathPattern.matches(concrete(need.scope.template)), need.scope.template)
            assertTrue(aclPathPattern.matches(concrete(need.scope.collection)), need.scope.collection)
        }
    }

    @Test fun requirementIdsAreUniqueAndEveryRowSaysWhichCallItChecks() {
        val ids = FEATURE_REQUIREMENTS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(FEATURE_REQUIREMENTS.all { it.api.isNotBlank() && it.title.isNotBlank() && it.needs.isNotEmpty() })
        assertTrue(FEATURE_REQUIREMENTS.all { it.group == GROUP_TODAY || it.group == GROUP_PLANNED })
        assertTrue(FEATURE_REQUIREMENTS.filter { it.planned }.all { it.group == GROUP_PLANNED })
        assertTrue(FEATURE_REQUIREMENTS.all { it.alsoNeeds.isEmpty() || !it.alsoNote.isNullOrBlank() })
    }

    @Test fun requirementsMatchTheApiViewerFindings() {
        fun need(id: String) = FEATURE_REQUIREMENTS.single { it.id == id }.needs
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Node, listOf("Sys.Syslog"))), need("syslog"))
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Node, listOf("Sys.Audit"))), need("services"))
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Node, listOf("Sys.Modify"))), need("updates"))
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Root, listOf("Sys.Modify"))), need("usb"), "USB is checked on /, not on the node")
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Node, listOf("Sys.Console"))), need("node-shell"))
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Guest, listOf("VM.Snapshot", "VM.Snapshot.Rollback"), any = true)), need("rollback"))
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Notifications, listOf("Mapping.Audit"))), need("notifications"))
        assertEquals(listOf(PrivilegeNeed(PermissionScope.Guest, listOf("VM.GuestAgent.Unrestricted"), legacy = listOf("VM.Monitor"), guestKind = "VM")), need("bluetooth"))
        assertEquals(setOf("VM.Backup", "Datastore.AllocateSpace"), need("backup-now").flatMap { it.privileges }.toSet())
    }

    // ---- evaluation ----

    @Test fun anAdministratorIsAllowedEverywhere() {
        val everything = PVE_PRIVILEGES.joinToString(",") { "\"$it\":1" }
        val result = evaluateFeatures(parsePermissions("""{"data":{"/":{$everything}}}"""), targets)
        assertEquals(FEATURE_REQUIREMENTS.size, result.size)
        assertTrue(result.all { it.status == FeatureStatus.Allowed }, result.filter { it.status != FeatureStatus.Allowed }.joinToString { it.requirement.id })
    }

    @Test fun anAuditorSeesTheReadFeaturesAndMissesTheRest() {
        val r = checks(auditorTree)
        for (id in listOf("fleet-guests", "fleet-nodes", "activity", "agent-addresses", "services", "pci", "notifications")) {
            assertEquals(FeatureStatus.Allowed, r.getValue(id).status, id)
        }
        for (id in listOf("power", "snapshots", "rollback", "console", "node-shell", "bluetooth", "syslog", "updates", "backup-now", "usb")) {
            assertEquals(FeatureStatus.Missing, r.getValue(id).status, id)
        }
        // The storage call works with Datastore.Audit, but archives and snippets are filtered out by the server.
        assertEquals(FeatureStatus.Partial, r.getValue("storage").status)
        assertTrue(r.getValue("storage").summary.contains("Datastore.AllocateSpace"))
        assertTrue(r.getValue("storage").summary.contains("omits the rest"))
    }

    @Test fun missingNamesThePrivilegeThePathAndWhyOnOneLine() {
        val r = checks(auditorTree)
        val power = r.getValue("power")
        assertEquals(listOf("VM.PowerMgmt @ /vms/{vmid}"), power.needs)
        assertEquals("Missing on all 3 guests. /vms/100 inherits from /vms, whose roles do not include VM.PowerMgmt.", power.summary)
        assertFalse(power.summary.contains('\n'))
        assertEquals("Missing on all 2 nodes. /nodes/pve1 inherits from /nodes, whose roles do not include Sys.Syslog.", r.getValue("syslog").summary)
        assertTrue(r.getValue("rollback").summary.contains("VM.Snapshot or VM.Snapshot.Rollback"))
        assertEquals("Allowed on all 2 nodes.", r.getValue("services").summary)
        assertEquals("Allowed on /.", r.getValue("activity").summary)
    }

    @Test fun oneGuestOverrideMakesAFeaturePartial() {
        val r = checks("""{"data":{"/vms":{"VM.Audit":1,"VM.PowerMgmt":1},"/vms/101":{"VM.Audit":0}}}""")
        val power = r.getValue("power")
        assertEquals(FeatureStatus.Partial, power.status)
        assertEquals("Missing on 1 of 3 guests. The roles on /vms/101 do not include VM.PowerMgmt.", power.summary)
        assertEquals(FeatureStatus.Allowed, r.getValue("fleet-guests").status)
    }

    @Test fun aPrivilegeSetWithoutPropagateIsNotInheritedAndTheReasonSaysSo() {
        val r = checks("""{"data":{"/vms":{"VM.Audit":1,"VM.PowerMgmt":0}}}""")
        val power = r.getValue("power")
        assertEquals(FeatureStatus.Missing, power.status)
        assertEquals("Missing on all 3 guests. VM.PowerMgmt is set on /vms without Propagate, so /vms/100 does not inherit it.", power.summary)
    }

    @Test fun aRoleOnTheExactPathCountsWhateverItsFlag() {
        val r = checks("""{"data":{"/nodes/pve1":{"Sys.Audit":0,"Sys.Console":0},"/nodes/pve4":{"Sys.Audit":0,"Sys.Console":0}}}""")
        assertEquals(FeatureStatus.Allowed, r.getValue("node-shell").status)
        assertEquals(FeatureStatus.Allowed, r.getValue("fleet-nodes").status)
    }

    @Test fun anyOfAcceptsEitherPrivilege() {
        for (name in listOf("VM.Snapshot", "VM.Snapshot.Rollback")) {
            assertEquals(FeatureStatus.Allowed, checks("""{"data":{"/vms":{"$name":1}}}""").getValue("rollback").status, name)
        }
        assertEquals(FeatureStatus.Missing, checks("""{"data":{"/vms":{"VM.Audit":1}}}""").getValue("rollback").status)
    }

    @Test fun allOfNeedsEveryPrivilegeAndEveryCheck() {
        val onlyGuest = checks("""{"data":{"/vms":{"VM.Backup":1}}}""").getValue("backup-now")
        assertEquals(FeatureStatus.Missing, onlyGuest.status)
        assertTrue(onlyGuest.summary.contains("Datastore.AllocateSpace"), onlyGuest.summary)
        assertEquals(FeatureStatus.Allowed, checks("""{"data":{"/vms":{"VM.Backup":1},"/storage":{"Datastore.AllocateSpace":1}}}""").getValue("backup-now").status)
        assertEquals(listOf("VM.Backup @ /vms/{vmid}", "Datastore.AllocateSpace @ /storage/{storage}"), onlyGuest.needs)
    }

    @Test fun theOlderGuestAgentPrivilegeIsUnknownBecauseTheServerVersionDecides() {
        val r = checks("""{"data":{"/vms":{"VM.Audit":1,"VM.Monitor":1}}}""")
        val bluetooth = r.getValue("bluetooth")
        assertEquals(FeatureStatus.Unknown, bluetooth.status)
        assertTrue(bluetooth.summary.contains("VM.Monitor") && bluetooth.summary.contains("Proxmox VE 8") && bluetooth.summary.contains("VM.GuestAgent.Unrestricted"), bluetooth.summary)
        assertEquals(FeatureStatus.Unknown, r.getValue("agent-addresses").status)
        // The modern privilege wins outright.
        assertEquals(FeatureStatus.Allowed, checks("""{"data":{"/vms":{"VM.GuestAgent.Unrestricted":1}}}""").getValue("bluetooth").status)
        assertEquals(FeatureStatus.Allowed, checks("""{"data":{"/vms":{"VM.GuestAgent.Audit":1}}}""").getValue("agent-addresses").status)
        assertEquals(FeatureStatus.Missing, checks("""{"data":{"/vms":{"VM.GuestAgent.Audit":1}}}""").getValue("bluetooth").status)
    }

    @Test fun guestAgentChecksSkipContainers() {
        val agent = FEATURE_REQUIREMENTS.single { it.id == "agent-addresses" }.needs.single()
        assertEquals(listOf("/vms/100", "/vms/102"), needPaths(agent, targets))
        // Only the container lacks the privilege, and it is not checked.
        val r = checks("""{"data":{"/vms":{"VM.GuestAgent.Audit":1},"/vms/101":{"VM.Audit":0}}}""")
        assertEquals(FeatureStatus.Allowed, r.getValue("agent-addresses").status)
        assertEquals("Allowed on all 2 guests.", r.getValue("agent-addresses").summary)
    }

    @Test fun withoutAnInventoryTheCollectionPathIsChecked() {
        val r = checks("""{"data":{"/vms":{"VM.PowerMgmt":1,"VM.Audit":1},"/nodes":{"Sys.Audit":1}}}""", PermissionTargets())
        assertEquals("Allowed on /vms.", r.getValue("power").summary)
        assertEquals("Allowed on /nodes.", r.getValue("services").summary)
        assertEquals(FeatureStatus.Missing, r.getValue("syslog").status)
        assertEquals("The roles on /nodes do not include Sys.Syslog.", r.getValue("syslog").summary)
    }

    @Test fun anEmptyDumpIsMissingEverywhereAndSaysNoRoleApplies() {
        val r = checks("""{"data":{}}""")
        assertTrue(r.values.all { it.status == FeatureStatus.Missing })
        assertEquals("Missing on all 3 guests. No role applies on /vms/100 or its parents, so VM.PowerMgmt is not granted.", r.getValue("power").summary)
    }

    @Test fun anUnreadDumpOrANonDirectConnectionIsUnknown() {
        val unread = evaluateFeatures(null, targets)
        assertTrue(unread.all { it.status == FeatureStatus.Unknown && it.summary == PERMISSIONS_UNREAD })
        val pdm = evaluateFeatures(parsePermissions(auditorTree), targets, direct = false)
        assertTrue(pdm.all { it.status == FeatureStatus.Unknown && it.summary == PERMISSIONS_DIRECT_ONLY })
        assertEquals(FEATURE_REQUIREMENTS.size, unread.size)
    }

    @Test fun statusCountsAddUp() {
        val result = evaluateFeatures(parsePermissions(auditorTree), targets)
        val counts = featureStatusCounts(result)
        assertEquals(result.size, counts.values.sum())
        assertEquals(7, counts.getValue(FeatureStatus.Allowed))
        assertEquals(1, counts.getValue(FeatureStatus.Partial))
        assertEquals(10, counts.getValue(FeatureStatus.Missing))
    }

    // ---- targets and demo ----

    @Test fun targetsComeFromTheRecordedInventoryAndSkipPdmGuests() {
        val demo = permissionTargets(demoOverview)
        assertEquals(listOf("demo-node-a", "demo-node-b"), demo.nodes)
        assertEquals(listOf(120 to "VM", 121 to "Container", 130 to "VM"), demo.guests)
        assertEquals(listOf("local-lvm", "shared-data"), demo.storages)
        assertEquals(PermissionTargets(), permissionTargets(null))
        val pdm = Overview(0, listOf(Guest("https://pdm.example.test:8443", 100, "g", "n", "VM", "running", scope = "remote-a")))
        assertTrue(permissionTargets(pdm).guests.isEmpty())
    }

    @Test fun theDemoSampleShowsEveryKindOfOutcome() {
        val r = evaluateFeatures(demoPermissions, permissionTargets(demoOverview)).associateBy { it.requirement.id }
        assertEquals(setOf("fleet-guests", "fleet-nodes", "activity", "agent-addresses", "services", "pci", "notifications"),
            r.filterValues { it.status == FeatureStatus.Allowed }.keys)
        assertEquals(setOf("power", "console", "node-shell", "storage", "syslog", "backup-now"), r.filterValues { it.status == FeatureStatus.Partial }.keys)
        assertEquals(setOf("snapshots", "rollback", "bluetooth", "updates", "usb"), r.filterValues { it.status == FeatureStatus.Missing }.keys)
        assertEquals("Missing on 1 of 3 guests. The roles on /vms/130 do not include VM.PowerMgmt.", r.getValue("power").summary)
        assertEquals("Missing on 1 of 2 nodes. /nodes/demo-node-b inherits from /nodes, whose roles do not include Sys.Console.", r.getValue("node-shell").summary)
    }

    private val aclPathPattern = Regex(
        "^(/|/access|/access/groups|/access/groups/[A-Za-z0-9._\\-]+|/access/realm|/access/realm/[A-Za-z0-9._\\-]+|/nodes|/nodes/[A-Za-z0-9._\\-]+" +
            "|/pool|/pool/[A-Za-z0-9._\\-]+|/sdn|/storage|/storage/[A-Za-z0-9._\\-]+|/vms|/vms/[1-9][0-9]{2,}|/mapping|/mapping/[A-Za-z0-9._\\-]+|/mapping/[A-Za-z0-9._\\-]+/[A-Za-z0-9._\\-]+)$",
    )

    private fun concrete(template: String) = template.replace("{node}", "pve1").replace("{vmid}", "100").replace("{storage}", "local")
}
