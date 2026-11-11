package local.ringleader.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle as JediStyle
import kotlinx.coroutines.*
import local.ringleader.core.*

private val ansi = intArrayOf(0x20252b,0xd75f5f,0x5faf87,0xd7af5f,0x609fd7,0xaf87d7,0x5fafaf,0xc8c8c8,0x707780,0xff8787,0x87dfaf,0xffd787,0x87bfff,0xd7afff,0x87dfdf,0xf0f0f0)
private fun terminalColor(c: TerminalColor?, default: Color): Color {
    if (c == null) return default
    if (!c.isIndexed) return c.toColor().let { Color(it.red,it.green,it.blue) }
    val i=c.colorIndex
    return if(i<16) Color(0xff000000L or ansi[i].toLong()) else if(i<232) { val n=i-16; fun part(v:Int): Int { return if(v==0) 0 else 55+40*v }; Color(part(n/36),part(n/6%6),part(n%6)) } else { val v=8+10*(i-232); Color(v,v,v) }
}

@Composable fun TerminalView(state: WorkspaceState, workspace: Workspace, target: TerminalTarget, modifier: Modifier = Modifier) {
    var retry by remember(target) { mutableStateOf(0) }
    var session by remember(target,retry,state.connectionEpoch) { mutableStateOf<TerminalSession?>(null) }
    var reason by remember(target,retry,state.connectionEpoch) { mutableStateOf("Connecting") }
    DisposableEffect(target,retry,state.connectionEpoch) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var opened: TerminalSession?=null
        scope.launch { try { opened=workspace.openTerminal(target); session=opened } catch(e: CancellationException) { throw e } catch(e: Exception) { reason=e.message?.takeIf { !it.contains("ticket",true) && !it.contains("wss:") } ?: "The terminal could not connect." } }
        onDispose { scope.cancel(); opened?.close(); VncKeyboard.captured=false }
    }
    LaunchedEffect(state.overview,target,session) {
        if(target.kind!="node" && state.overview?.guests?.any { it.node==target.node && it.id.toString()==target.vmid.toString() && it.status=="running" } != true) { session?.close(); reason=if(target.kind=="lxc") "The container is stopped." else "The VM is stopped." }
    }
    val model=session?.model
    val fallback=remember(target,retry) { TerminalModel() }
    DisposableEffect(fallback) { onDispose { fallback.close() } }
    TerminalPane(model ?: fallback,session,reason, { retry++ },modifier)
}

@Composable internal fun TerminalPane(model: TerminalModel, session: TerminalSession?, initialStatus: String, reconnect: () -> Unit, modifier: Modifier=Modifier) {
    var font by remember { mutableStateOf(13) }
    var tick by remember { mutableStateOf(0) }
    var offset by remember { mutableStateOf(0) }
    var cols by remember { mutableStateOf(80) }; var rows by remember { mutableStateOf(24) }
    var focused by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf<Pair<Int,Int>?>(null) }; var end by remember { mutableStateOf<Pair<Int,Int>?>(null) }
    val clipboard=LocalClipboardManager.current
    val focus=remember { FocusRequester() }
    val measure=rememberTextMeasurer()
    val style=TextStyle(fontFamily=Tech.Mono,fontSize=font.sp)
    val metrics=measure.measure("M",style)
    val cw=metrics.size.width.toFloat().coerceAtLeast(1f); val ch=metrics.size.height.toFloat().coerceAtLeast(1f)
    val reduce=LocalReduceMotion.current
    var blink by remember { mutableStateOf(true) }
    // Output redraws on the next display frame, at most once per frame: smooth at 240 Hz, nothing drawn while idle.
    // (A fixed 50 ms poll capped terminal output at 20 fps whatever the display could do.)
    LaunchedEffect(model) {
        val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        model.onUpdate = { changed.trySend(Unit) }
        try { for (signal in changed) { tick++; androidx.compose.runtime.withFrameNanos { } } }
        finally { model.onUpdate = null }
    }
    // The cursor blinks (530 ms, the system caret period) only while this terminal has focus in an active window; otherwise it stays solid and nothing wakes up.
    val windowActive = LocalWindowActive.current
    LaunchedEffect(reduce, focused, windowActive) { blink = true; while(!reduce && focused && windowActive) { delay(530); blink=!blink } }
    LaunchedEffect(cols,rows,session?.ready) { delay(150); if(session!=null) session.resize(cols,rows) else model.resize(cols,rows) }
    DisposableEffect(Unit) { onDispose { VncKeyboard.captured=false } }
    fun selected(): String {
        val a=start ?: return ""; val b=end ?: return ""
        val (lo,hi)=if(a.second*10000+a.first<=b.second*10000+b.first) a to b else b to a
        model.buffer.lock(); try { return (lo.second..hi.second).joinToString("\n") { y -> val line=model.buffer.getLine(y).text.padEnd(cols); line.substring(if(y==lo.second) lo.first.coerceIn(0,line.length) else 0,if(y==hi.second) (hi.first+1).coerceIn(0,line.length) else line.length).trimEnd() } } finally { model.buffer.unlock() }
    }
    fun copy() { clipboard.setText(AnnotatedString(selected())) }
    fun paste() { clipboard.getText()?.text?.let { session?.input(model.paste(it)) } }
    Column(modifier.fillMaxSize().background(Tech.Black)) {
        Row(Modifier.fillMaxWidth().height(32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(if (tick < 0) "" else session?.reason ?: if(session?.ready==true) "Connected $cols×$rows" else initialStatus,Modifier.weight(1f),color=Tech.Muted,fontFamily=Tech.Mono,fontSize=11.sp,maxLines=1)
            MiniAction("Copy") { copy() }; MiniAction("Paste",enabled=session?.ready==true) { paste() }
            MiniAction("Reconnect",onClick=reconnect)
            MiniAction("−",enabled=font>9) { font-- }; Text("$font",color=Tech.Muted,fontSize=11.sp); MiniAction("+",enabled=font<28) { font++ }
        }
        // Glyph layouts are cached per character and style: laying out every cell on every frame was thousands of
        // text layouts per frame at full-window sizes.
        val glyphs = remember(font, style) { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }
        // A container console only redraws its login prompt after a key, so an empty screen gets a quiet hint.
        val blank = remember(tick, session?.ready) {
            session?.ready == true && run { model.buffer.lock(); try { (0 until model.buffer.height).all { model.buffer.getLine(it).text.isBlank() } } finally { model.buffer.unlock() } }
        }
        ContextMenuArea(items={ listOf(ContextMenuItem("Copy") { copy() }) }) { Box(Modifier.fillMaxSize().crtPowerOn(if(session?.ready==true) session else null)) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { cols=(it.width/cw).toInt().coerceIn(2,1000); rows=(it.height/ch).toInt().coerceIn(2,500) }
                .focusRequester(focus).onFocusChanged { focused=it.isFocused; VncKeyboard.captured=it.isFocused }.focusable()
                .onPreviewKeyEvent { e ->
                    if(!focused) false else if(e.type!=KeyEventType.KeyDown) true else {
                        if(e.isCtrlPressed && e.isShiftPressed && e.key==Key.C) copy()
                        else if(e.isCtrlPressed && e.isShiftPressed && e.key==Key.V) paste()
                        else {
                            val symbol=vncKeysym(e)
                            val named=mapOf(0xff0d to "Enter",0xff08 to "Backspace",0xff09 to "Tab",0xff1b to "Escape",0xff51 to "Left",0xff52 to "Up",0xff53 to "Right",0xff54 to "Down",0xff50 to "Home",0xff57 to "End",0xff55 to "PageUp",0xff56 to "PageDown",0xffff to "Delete",0xff63 to "Insert")
                            val name=named[symbol] ?: if(symbol!=null && symbol in 0xffbe..0xffc9) "F${symbol-0xffbe+1}" else ""
                            val text=if(e.utf16CodePoint>0) String(Character.toChars(e.utf16CodePoint)) else if(e.isCtrlPressed && e.key.keyCode in Key.A.keyCode..Key.Z.keyCode) ('a'.code+(e.key.keyCode-Key.A.keyCode).toInt()).toChar().toString() else ""
                            (if (name in listOf("Up","Down","Left","Right","Home","End","PageUp","PageDown","Insert","Delete") && !e.isAltPressed && !e.isCtrlPressed) {
                                val code=mapOf("Up" to 38,"Down" to 40,"Left" to 37,"Right" to 39,"Home" to 36,"End" to 35,"PageUp" to 33,"PageDown" to 34,"Insert" to 155,"Delete" to 127)[name]!!
                                model.terminal.getCodeForKey(code,0)?.toString(Charsets.UTF_8) ?: terminalKey(name,text)
                            } else terminalKey(name,text,e.isCtrlPressed,e.isAltPressed))?.let { session?.input(it); offset=0 }
                        }; true
                    }
                }.pointerInput(model,cw,ch,cols,rows) { awaitPointerEventScope { while(true) {
                    val e=awaitPointerEvent(); val c=e.changes.firstOrNull() ?: continue
                    if(e.type==PointerEventType.Scroll && !model.buffer.isUsingAlternateBuffer) { offset=(offset+c.scrollDelta.y.toInt()*3).coerceIn(0,model.buffer.historyLinesCount); c.consume() }
                    val point=(c.position.x/cw).toInt().coerceIn(0,cols-1) to ((c.position.y/ch).toInt().coerceIn(0,rows-1)-offset)
                    if(e.type==PointerEventType.Press && e.buttons.isPrimaryPressed) { focus.requestFocus(); start=point; end=point }
                    if(e.type==PointerEventType.Move && e.buttons.isPrimaryPressed) { end=point; c.consume() }
                } } }) {
                if(tick<0) return@Canvas
                model.buffer.lock()
                try {
                    val scroll=if(model.buffer.isUsingAlternateBuffer) 0 else offset.coerceAtMost(model.buffer.historyLinesCount)
                    for(y in 0 until minOf(rows,model.buffer.height)) for(x in 0 until minOf(cols,model.buffer.width)) {
                        val line=model.buffer.getLine(y-scroll); val s=line.getStyleAt(x) ?: JediStyle.EMPTY
                        var fg=terminalColor(s.foreground,Tech.Ink); var bg=terminalColor(s.background,Tech.Black)
                        if(s.hasOption(JediStyle.Option.INVERSE)) { val tmp=fg; fg=bg; bg=tmp }
                        val a=start; val b=end; val n=(y-scroll)*10000+x
                        val selected=a!=null && b!=null && n in minOf(a.second*10000+a.first,b.second*10000+b.first)..maxOf(a.second*10000+a.first,b.second*10000+b.first)
                        if(selected) bg=Tech.AmberWash
                        if(bg!=Tech.Black) drawRect(bg,Offset(x*cw,y*ch),Size(cw,ch))
                        val char=line.charAt(x)
                        if(char!='\u0000' && char!=' ') {
                            val bold=s.hasOption(JediStyle.Option.BOLD); val under=s.hasOption(JediStyle.Option.UNDERLINED)
                            val glyph=glyphs.getOrPut("$char|${fg.value}|$bold|$under") {
                                if(glyphs.size>4096) glyphs.clear()
                                measure.measure(char.toString(),style.copy(color=fg,fontWeight=if(bold) FontWeight.Bold else FontWeight.Normal,textDecoration=if(under) TextDecoration.Underline else null))
                            }
                            drawText(glyph,topLeft=Offset(x*cw,y*ch))
                        }
                    }
                    if(scroll==0 && model.cursorVisible && (reduce || blink)) drawRect(Tech.Ink.copy(alpha=.5f),Offset((model.terminal.cursorX-1)*cw,(model.terminal.cursorY-1)*ch),Size(cw,ch))
                } finally { model.buffer.unlock() }
            }
            if (blank) Text("Press Enter to show the console's prompt", Modifier.align(Alignment.Center), color = Tech.Muted, fontFamily = Tech.Mono, fontSize = 11.sp)
        } }
    }
}
