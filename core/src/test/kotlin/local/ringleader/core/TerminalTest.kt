package local.ringleader.core

import kotlin.test.*
import org.junit.jupiter.api.Test

class TerminalTest {
    @Test fun framingAndAuth() {
        assertEquals("0:7:hé😀",terminalFrame(data="hé😀"))
        assertEquals("1:120:36:",terminalFrame(cols=120,rows=36))
        assertEquals("2",terminalFrame())
        val proxy=parseTermProxy("""{"data":{"port":5900,"ticket":"one-time","user":"root@pam","upid":"UPID:fixture"}}""")
        assertEquals("root@pam:one-time\n",terminalAuth(proxy))
        assertFails { parseTermProxy("""{"data":{"port":0}}""") }
        assertFails { terminalFrame(cols=0,rows=30) }
    }
    @Test fun routes() {
        assertEquals("/api2/json/nodes/test/lxc/101",TerminalTarget("test","lxc",101).base())
        assertEquals("/api2/json/nodes/test/qemu/102",TerminalTarget("test","qemu",102).base())
        assertEquals("/api2/json/nodes/test",TerminalTarget("test").base())
        for(t in listOf(TerminalTarget("../bad"),TerminalTarget("test","bad",1),TerminalTarget("test","qemu",0),TerminalTarget("test","node",1))) assertFails { t.base() }
    }
    @Test fun keys() {
        val expected=mapOf("Up" to "\u001b[A","Down" to "\u001b[B","Left" to "\u001b[D","Right" to "\u001b[C","Home" to "\u001b[H","End" to "\u001b[F","PageUp" to "\u001b[5~","PageDown" to "\u001b[6~","F1" to "\u001bOP","F12" to "\u001b[24~","Backspace" to "\u007f","Enter" to "\r","Tab" to "\t")
        expected.forEach { (k,v) -> assertEquals(v,terminalKey(k)) }
        assertEquals("\u0003",terminalKey("","c",ctrl=true))
        assertEquals("\u001bx",terminalKey("","x",alt=true))
    }
    @Test fun emulatorRecordedStream() {
        TerminalModel(40,10).use { model ->
            model.feed("root@test:~# \u001b[31mRED\u001b[0m\r\n\u001b[2J\u001b[Hbase\u001b[2;4H\u001b[38;5;82mgreen\u001b[0m\u001b[?1049h\u001b[Halternate\u001b[?1049l\u001b[3;1HDONE".toByteArray())
            val deadline=System.nanoTime()+5_000_000_000
            while(!model.buffer.getScreenLines().contains("DONE") && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(model.buffer.getScreenLines().contains("DONE"),model.buffer.getScreenLines())
            assertEquals("base",model.buffer.getLine(0).text)
            assertEquals("   green",model.buffer.getLine(1).text)
            assertEquals(0x5fff00,model.buffer.getStyleAt(3,1)!!.foreground!!.toColor().rgb and 0xffffff)
            assertFalse(model.buffer.isUsingAlternateBuffer)
            model.feed("\u001b[?2004h".toByteArray())
            while(!model.bracketedPaste && System.nanoTime()<deadline) Thread.sleep(10)
            assertEquals("\u001b[200~hi\u001b[201~",model.paste("hi"))
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline=System.nanoTime()+5_000_000_000
        while(!condition()) { check(System.nanoTime()<deadline) { "timed out waiting for $what" }; Thread.sleep(5) }
    }

    @Test fun textFramesAreUtf8AndSplitSurrogatesAreJoined() {
        val session=TerminalSession(TerminalModel(40,5),null)
        try {
            session.receive("OK".toByteArray())
            assertTrue(session.ready)
            session.receiveText("café \uD83D",false)
            session.receiveText("\uDE00 ok",true)
            waitFor("text") { session.model.buffer.getLine(0).text.contains("ok") }
            val line=session.model.buffer.getLine(0).text
            assertTrue(line.startsWith("café "),line)
            assertFalse('?' in line || '�' in line,line)
        } finally { session.close() }
    }

    @Test fun inputIsQueuedForOneWriterAndNeverBlocksTheCaller() {
        val session=TerminalSession(TerminalModel(),null)
        val gate=java.util.concurrent.Semaphore(0)
        val sent=java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            session.receive("OK".toByteArray())
            session.startWriter { gate.acquire(); sent+=it }
            val started=System.nanoTime()
            session.input("ls"); session.input("\r"); session.resize(100,30)
            assertTrue((System.nanoTime()-started)/1_000_000<1000,"a stalled socket does not block the UI thread")
            assertTrue(sent.isEmpty())
            gate.release(3)
            waitFor("three frames") { sent.size==3 }
            assertEquals(listOf("0:2:ls","0:1:\r","1:100:30:"),sent.toList())
            assertNull(session.reason)
        } finally { gate.release(100); session.close() }
    }

    @Test fun aStalledWriterEndsTheSessionInsteadOfQueueingForever() {
        val session=TerminalSession(TerminalModel(),null)
        try {
            session.receive("OK".toByteArray())
            session.startWriter { Thread.sleep(60_000) }
            repeat(TERMINAL_OUTBOX_LIMIT+5) { session.input("x") }
            assertEquals(TERMINAL_LOST,session.reason)
            assertFalse(session.ready)
        } finally { session.close() }
    }

    @Test fun aWrongGreetingIsRefused() {
        val session=TerminalSession(TerminalModel(),null)
        session.receive("NO".toByteArray())
        assertEquals("The terminal refused authentication.",session.reason)
        assertFalse(session.ready)
    }
}
