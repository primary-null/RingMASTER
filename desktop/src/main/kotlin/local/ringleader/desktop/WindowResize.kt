package local.ringleader.desktop
import java.awt.*
import java.awt.event.*
import androidx.compose.runtime.*

@Composable fun InstallEdgeResize(window: Window, maximized: Boolean) {
    DisposableEffect(window,maximized) {
        var edges=0; var origin: Point?=null; var bounds=Rectangle()
        fun edge(p: Point): Int = (if(p.x < 7) 1 else if(p.x >= window.width-7) 2 else 0) or
            (if(p.y < 7) 4 else if(p.y >= window.height-7) 8 else 0)
        val listener=AWTEventListener { event ->
            val e=event as? MouseEvent
            if(e != null && !maximized && (e.component == window || javax.swing.SwingUtilities.getWindowAncestor(e.component) == window)) {
                val p=javax.swing.SwingUtilities.convertPoint(e.component,e.point,window)
                when(e.id) {
                    MouseEvent.MOUSE_MOVED -> { val n=edge(p); window.cursor=Cursor.getPredefinedCursor(when(n) { 1,2 -> Cursor.E_RESIZE_CURSOR; 4,8 -> Cursor.N_RESIZE_CURSOR; 5,10 -> Cursor.NW_RESIZE_CURSOR; 6,9 -> Cursor.NE_RESIZE_CURSOR; else -> Cursor.DEFAULT_CURSOR }) }
                    MouseEvent.MOUSE_PRESSED -> if(e.button == MouseEvent.BUTTON1) { edges=edge(p); if(edges != 0) { origin=e.locationOnScreen; bounds=window.bounds; e.consume() } }
                    MouseEvent.MOUSE_RELEASED -> { edges=0; origin=null }
                    MouseEvent.MOUSE_DRAGGED -> origin?.let { start ->
                        val dx=e.xOnScreen-start.x; val dy=e.yOnScreen-start.y
                        val left=edges and 1 != 0; val right=edges and 2 != 0; val top=edges and 4 != 0; val bottom=edges and 8 != 0
                        val w=(bounds.width+if(left) -dx else if(right) dx else 0).coerceAtLeast(window.minimumSize.width)
                        val h=(bounds.height+if(top) -dy else if(bottom) dy else 0).coerceAtLeast(window.minimumSize.height)
                        window.setBounds(if(left) bounds.x+bounds.width-w else bounds.x,if(top) bounds.y+bounds.height-h else bounds.y,w,h); e.consume()
                    }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener); window.cursor=Cursor.getDefaultCursor() }
    }
}
