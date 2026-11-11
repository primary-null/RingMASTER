package local.ringleader.desktop
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
class DensityHelpersTest {
 @Test fun tagsKeepShortNamesAndBoundLongNames() {
  assertEquals("production", compactTag("production"))
  assertEquals("abcdefghijklmn", compactTag("abcdefghijklmn"))
  assertEquals("abcdefghijklm…", compactTag("abcdefghijklmnop"))
 }
}
