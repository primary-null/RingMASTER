package local.ringleader.desktop

/**
 * Work counters for the Bluetooth scan view, read only by its tests and the opt-in benchmark (BluetoothBenchTest).
 * Plain increments on the UI thread; the app never reads them.
 */
internal object BtScanStats {
    /** Times a table row ran its composable body. */
    @JvmField var rowCompositions = 0
    /** Text layouts made for the field and the timeline (each [androidx.compose.ui.text.TextMeasurer.measure] call). */
    @JvmField var textMeasures = 0
    /** Device positions computed for the field's hit test. */
    @JvmField var hitPositions = 0

    @JvmField var staticFieldCaches = 0
    @JvmField var movingFieldDraws = 0

    fun reset() { rowCompositions = 0; textMeasures = 0; hitPositions = 0; staticFieldCaches = 0; movingFieldDraws = 0 }
}
