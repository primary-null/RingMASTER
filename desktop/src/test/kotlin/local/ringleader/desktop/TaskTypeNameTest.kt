package local.ringleader.desktop
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
class TaskTypeNameTest {
 @Test fun knownAndUnknownNames() {
  assertEquals("Start VM", taskTypeName("qmstart"))
  assertEquals("Backup", taskTypeName("vzdump"))
  assertEquals("custom-task", taskTypeName("custom-task"))
  assertEquals("Console", taskTypeName("vncproxy"))
  assertEquals("Destroy container", taskTypeName("vzdestroy"))
  assertEquals("Delete volume", taskTypeName("imgdel"))
 }
}
