package local.ringleader.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Maintainer decision D3: `PowerAction.Create` labels a guest creation and must never be offered, sent or parsed as a
 * power action on an existing guest.
 */
class PowerCreateGuardTest {
    private val guests = listOf("running", "stopped", "paused", "suspended").flatMap { status ->
        listOf("VM", "Container").map { kind -> Guest(DEMO_REMOTE_KEY, 120, "guest", "node-a", kind, status) }
    }

    @Test fun createNeverMatchesAGuest() {
        guests.forEach { assertFalse(actionMatches(it, PowerAction.Create), "${it.kind} ${it.status}") }
        assertFalse(actionMatches(guests.first().copy(template = true), PowerAction.Create))
    }

    @Test fun createCannotBeConfirmedAsAPowerAction() {
        val error = assertFailsWith<IllegalArgumentException> {
            PowerConfirmation(1, DEMO_REMOTE_KEY, "Demonstration", "Demo", ResourceId(DEMO_REMOTE_KEY, "", "VM", 120), "node-a", "guest", "VM", PowerAction.Create, "stopped", false)
        }
        assertEquals(CREATE_NOT_POWER_ACTION, error.message)
    }

    @Test fun createHasNoPowerRouteOrBody() {
        assertFailsWith<IllegalArgumentException> { powerActionPath("node-a", "VM", 120, PowerAction.Create) }
        assertFailsWith<IllegalArgumentException> { powerActionPath("node-a", "Container", 120, PowerAction.Create) }
        assertFailsWith<IllegalArgumentException> { powerRequestBody(PowerAction.Create) }
    }

    @Test fun createIsNeitherDestructiveNorHaRouted() {
        assertEquals("Create", powerActionTitle(PowerAction.Create))
        assertEquals("Create", powerCommitLabel(PowerAction.Create))
        assertFalse(powerActionDestructive(PowerAction.Create))
        assertFalse(powerActionUsesHa(guests.first().copy(haState = "started"), PowerAction.Create))
        assertNull(guestLockRefusal(guests.first(), PowerAction.Create))
    }

    @Test fun aCreateSubmissionIsRecordedAndBlocksUntilResolvedByCheck() {
        val unknown = PowerSubmission("local-1", DEMO_REMOTE_KEY, "node-a", 105, "VM", "web-1", PowerAction.Create, PowerPhase.Unknown, "Unknown.", false, false)
        assertTrue(isCreateSubmission(unknown))
        assertEquals(unknown, unresolvedSubmission(WorkspaceState(submissions = listOf(unknown))))
        assertNull(unresolvedSubmission(WorkspaceState(submissions = listOf(unknown.copy(resolvedByCheck = true)))))
        assertTrue(unresolvedActionText(unknown).startsWith("The result of Create on web-1 (105) is unknown."))
        assertFalse(isCreateSubmission(unknown.copy(action = PowerAction.Start)))
    }

    /**
     * Screens list power actions explicitly. This fails when main code starts enumerating every power action (which would
     * offer Create) or names Create outside the core files that own guest creation.
     */
    @Test fun noScreenEnumeratesOrNamesCreate() {
        val roots = listOf(File("src/main/kotlin"), File("../desktop/src/main/kotlin"))
        roots.forEach { assertTrue(it.isDirectory, "Missing source folder ${it.path}") }
        val enumerations = listOf("PowerAction.entries", "PowerAction.values()", "enumValues<PowerAction>", "enumEntries<PowerAction>")
        val createOwners = setOf("PowerActions.kt", "GuestCreate.kt", "GuestCreateFlow.kt", "Workspace.kt")
        val offenders = mutableListOf<String>()
        roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.forEach { file ->
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                if (enumerations.any { it in line }) offenders += "${file.name}:${index + 1} enumerates PowerAction"
                if ("PowerAction.Create" in line && file.name !in createOwners) offenders += "${file.name}:${index + 1} names PowerAction.Create"
            }
        }
        assertEquals(emptyList(), offenders)
    }
}
