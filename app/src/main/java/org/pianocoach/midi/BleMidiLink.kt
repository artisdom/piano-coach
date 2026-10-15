package org.pianocoach.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * BLE-MIDI to `GZUT-MIDI` over raw GATT. The piano drops any packet whose
 * header byte is not exactly 0x80 and reads everything after it as plain
 * MIDI, so Android's own BLE-MIDI (timestamped) cannot drive it. Every
 * write here is `80 80` + plain MIDI messages, write-without-response,
 * at most min(MTU − 3, 244) bytes. Reconnects automatically.
 *
 * Callers must hold the Bluetooth permissions for the running Android
 * version (see MainActivity).
 */
@SuppressLint("MissingPermission")
class BleMidiLink(
    private val context: Context,
    private val listener: PianoListener,
) : PianoLink("Bluetooth") {
    private val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var payload = 20
    private val writeDone = Semaphore(0)
    private var sender: Thread? = null

    @Volatile
    private var enabled = false

    /** Human-readable state for the settings page. */
    @Volatile
    var status = "off"
        private set

    val available get() = bm?.adapter != null

    fun start() {
        if (enabled) return
        enabled = true
        scan()
    }

    override fun close() {
        enabled = false
        stopScan()
        handler.removeCallbacksAndMessages(null)
        val g = gatt
        gatt = null
        val was = connected
        connected = false
        try {
            g?.disconnect(); g?.close()
        } catch (_: Exception) {
        }
        status = "off"
        if (was) listener.onLinkState(this, false, "closed")
    }

    private fun setStatus(s: String) {
        status = s
        Log.i(TAG, s)
        listener.onLinkState(this, connected, s)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            if (name == DEVICE_NAME || MIDI_SERVICE in uuids) {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("scan failed ($errorCode)")
            retry(5000)
        }
    }

    private fun scan() {
        if (!enabled || scanning || gatt != null) return
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off")
            retry(5000)
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { retry(5000); return }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCb)
        } catch (e: SecurityException) {
            setStatus("no Bluetooth permission")
            return
        }
        scanning = true
        setStatus("searching for $DEVICE_NAME…")
        // Give up after a while and try again (scans are throttled).
        handler.postDelayed({
            if (scanning) {
                stopScan()
                setStatus(
                    "$DEVICE_NAME not found — retrying" +
                        if (Build.VERSION.SDK_INT < 31) " (Location must be on to scan)" else "",
                )
                retry(4000)
            }
        }, 12_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bm?.adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
    }

    private fun retry(ms: Long) {
        if (enabled) handler.postDelayed({ scan() }, ms)
    }

    private fun connect(dev: BluetoothDevice) {
        setStatus("connecting…")
        gatt = dev.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun lost(why: String) {
        val g = gatt
        gatt = null
        chr = null
        val was = connected
        connected = false
        try {
            g?.close()
        } catch (_: Exception) {
        }
        writeDone.release()
        if (was) listener.onLinkState(this, false, why)
        setStatus(why)
        retry(2000)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("connected, setting up…")
                    if (!g.requestMtu(260)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    lost("disconnected")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            handler.post {
                if (g != gatt) return@post
                payload = (if (st == BluetoothGatt.GATT_SUCCESS) mtu - 3 else 20).coerceIn(20, 244)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post {
                if (g != gatt) return@post
                val c = g.getService(MIDI_SERVICE)?.getCharacteristic(MIDI_CHAR)
                if (c == null) {
                    g.disconnect()
                    lost("no MIDI service")
                    return@post
                }
                chr = c
                g.setCharacteristicNotification(c, true)
                val cccd = c.getDescriptor(CCCD)
                if (cccd == null) {
                    ready(g)
                    return@post
                }
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, v)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = v
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            handler.post { if (g == gatt) ready(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            writeDone.release()
        }

        private val parser = MidiStreamParser(listener, ble = true)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            // API 33+.
            if (c.uuid == MIDI_CHAR) parser.feed(value, 0, value.size)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            if (c.uuid == MIDI_CHAR) parser.feed(v, 0, v.size)
        }
    }

    private fun ready(g: BluetoothGatt) {
        if (connected) return
        queue.clear()
        writeDone.drainPermits()
        connected = true
        detail = g.device.name ?: DEVICE_NAME
        setStatus("connected (${payload + 3} MTU)")
        startSender(g)
    }

    private fun startSender(g: BluetoothGatt) {
        val t = Thread({
            while (connected && g == gatt) {
                // Each message is 3 bytes after the 2-byte header.
                val batch = drain((payload - 2) / 3, 200) ?: continue
                val c = chr ?: break
                val pkt = encode(batch, byteArrayOf(0x80.toByte(), 0x80.toByte()))
                val ok = try {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        c.value = pkt
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(c)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "write failed", e)
                    false
                }
                if (ok) {
                    // One GATT operation at a time.
                    writeDone.tryAcquire(300, TimeUnit.MILLISECONDS)
                } else {
                    // Busy: put it back and try again shortly.
                    Thread.sleep(10)
                    val rest = ArrayList<Int>()
                    queue.drainTo(rest)
                    queue.addAll(batch.toList())
                    queue.addAll(rest)
                }
            }
        }, "ble-midi-out")
        t.isDaemon = true
        t.start()
        sender = t
    }

    companion object {
        private const val TAG = "BleMidiLink"
        const val DEVICE_NAME = "GZUT-MIDI"
        val MIDI_SERVICE: UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
        val MIDI_CHAR: UUID = UUID.fromString("7772e5db-3868-4112-a1a9-f2669d106bf3")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
