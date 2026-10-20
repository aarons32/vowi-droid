package com.vowi.vowidroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.CcidDescriptor
import com.vowi.vowidroid.core.sim.CcidReader
import com.vowi.vowidroid.core.sim.CcidTransport
import com.vowi.vowidroid.core.sim.FoundCard
import com.vowi.vowidroid.core.sim.SimCardException
import com.vowi.vowidroid.core.sim.SimCards
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sim.SimLocation
import com.vowi.vowidroid.core.sim.SimPinRequiredException
import com.vowi.vowidroid.core.sim.Usim
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * USB SIM readers (USB CCID smart-card readers) through Android's USB host
 * API, so a SIM in a reader works like one in the phone: VowiDroid reads its
 * identity and runs AKA on it over APDUs. Readers are watched for being
 * plugged in or out and for cards going in or out; [onChange] then asks the
 * core to look again. Methods block; call them off the main thread.
 */
class UsbSimReaders(context: Context, private val onChange: () -> Unit) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val handles = ConcurrentHashMap<String, Handle>()
    private var monitor: ScheduledExecutorService? = null
    private var lastSnapshot: Map<String, Boolean?> = emptyMap()

    /** A plugged-in reader: whether VowiDroid may use it, the card in it, and why it cannot be used, if so. */
    data class Reader(val device: String, val name: String, val permitted: Boolean, val card: FoundCard?, val issue: String?)

    private class Handle(val device: UsbDevice, val connection: UsbDeviceConnection, val face: UsbInterface, val reader: CcidReader, val name: String) {
        val lock = Any()

        /** The card in the reader, while VowiDroid knows it is the same card. */
        @Volatile
        var card: FoundCard? = null

        /** VowiDroid powered the card down itself (Eject), so an idle slot still holds the same card. */
        @Volatile
        var parked = false

        @Volatile
        var closed = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { close(it.deviceName) }
            }
            onChange()
        }
    }

    fun start() {
        if (usb == null || monitor != null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Plugging a reader in reaches only activities, and a card going in or out reaches nobody, so the readers are polled.
        monitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "vowi-usb-readers").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS) }
    }

    fun close() {
        monitor?.shutdownNow()
        monitor = null
        runCatching { app.unregisterReceiver(receiver) }
        handles.keys.toList().forEach(::close)
    }

    /** Every reader plugged in, with the card in it when VowiDroid may read it. */
    fun readers(): List<Reader> {
        val devices = ccidDevices()
        handles.keys.filter { name -> devices.none { it.deviceName == name } }.forEach(::close)
        return devices.map { device ->
            val name = label(device)
            if (usb?.hasPermission(device) != true) return@map Reader(device.deviceName, name, false, null, ISSUE_PERMISSION)
            val handle = open(device) ?: return@map Reader(device.deviceName, name, true, null, ISSUE_UNUSABLE)
            try {
                val card = synchronized(handle.lock) { readCard(handle) }
                Reader(device.deviceName, name, true, card, if (card == null) ISSUE_NO_CARD else card.issue.ifEmpty { null })
            } catch (error: Exception) {
                Log.w(TAG, "reader $name: ${error.message}")
                Reader(device.deviceName, name, true, null, error.message ?: ISSUE_UNUSABLE)
            }
        }
    }

    /**
     * Cards in the readers, a PIN-locked one included: its line still follows
     * it there, and then shows why it cannot register.
     */
    fun cards(): List<FoundCard> = readers().mapNotNull { it.card }

    /** Shows Android's "Allow VowiDroid to access …?" dialog for a reader. */
    fun requestPermission(device: String) {
        val manager = usb ?: return
        val target = manager.deviceList[device] ?: return
        val intent = Intent(ACTION_PERMISSION).setPackage(app.packageName)
        manager.requestPermission(target, PendingIntent.getBroadcast(app, device.hashCode(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    /** The identity of the card in [device], read fresh. */
    fun identity(device: String): SimIdentity {
        val handle = handle(device)
        synchronized(handle.lock) {
            handle.parked = false
            handle.reader.powerOn()
            return Usim.readIdentity(handle.reader)
        }
    }

    /** AKA on the card in [device]; [expectedIccid] makes sure it is still the line's card. */
    fun authenticate(device: String, expectedIccid: String, challenge: AkaChallenge): AkaResult {
        val handle = handle(device)
        synchronized(handle.lock) {
            handle.parked = false
            handle.reader.powerOn()
            return Usim.authenticate(handle.reader, challenge, expectedIccid)
        }
    }

    /** Powers the card in [device] down, so it can be taken out (Eject). */
    fun powerOff(device: String) {
        val handle = handles[device] ?: return
        synchronized(handle.lock) {
            runCatching { handle.reader.powerOff() }.onFailure { Log.w(TAG, "power off failed: ${it.message}") }
            handle.parked = true
        }
    }

    private fun readCard(handle: Handle): FoundCard? {
        val slot = handle.reader.slotStatus()
        if (slot == CcidReader.Slot.ABSENT) {
            handle.card = null
            handle.parked = false
            return null
        }
        // An idle card VowiDroid did not power down may have been swapped in the meantime.
        val known = handle.card
        if (known != null && (slot == CcidReader.Slot.ACTIVE || handle.parked)) return known
        handle.parked = false
        handle.reader.powerOn()
        val iccid = Usim.readIccid(handle.reader)
        val location = SimLocation.UsbReader(handle.device.deviceName, handle.name)
        // The carrier's name labels the card; a PIN-locked card gives no IMSI but is still told apart by its ICCID.
        val identity = try {
            Usim.readIdentity(handle.reader)
        } catch (error: SimPinRequiredException) {
            null
        }
        val label = identity?.spn?.ifEmpty { null } ?: handle.name
        return FoundCard(location, identity?.imsi, iccid, label, issue = if (identity == null) SimCards.ISSUE_PIN else "").also { handle.card = it }
    }

    private fun poll() {
        val manager = usb ?: return
        val snapshot = ccidDevices().associate { device ->
            val handle = if (manager.hasPermission(device)) open(device) else null
            val inserted = handle?.let { current ->
                runCatching { synchronized(current.lock) { current.reader.slotStatus() } }.getOrNull()?.let { it != CcidReader.Slot.ABSENT }
            }
            device.deviceName to inserted
        }
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            onChange()
        }
    }

    private fun handle(device: String): Handle {
        val known = handles[device]
        if (known != null && !known.closed) return known
        val target = usb?.deviceList?.get(device) ?: throw SimCardException("the USB SIM reader is not plugged in")
        return open(target) ?: throw SimCardException("VowiDroid may not use this USB SIM reader")
    }

    @Synchronized
    private fun open(device: UsbDevice): Handle? {
        handles[device.deviceName]?.takeIf { !it.closed }?.let { return it }
        val manager = usb ?: return null
        if (!manager.hasPermission(device)) return null
        val face = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CSCID } ?: return null
        val endpoints = (0 until face.endpointCount).map(face::getEndpoint)
        val bulkOut = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        val bulkIn = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val connection = manager.openDevice(device) ?: return null
        if (!connection.claimInterface(face, true)) {
            connection.close()
            return null
        }
        val descriptor = CcidDescriptor.parse(connection.rawDescriptors ?: ByteArray(0))
        if (descriptor == null || !descriptor.supported) {
            Log.w(TAG, "${label(device)} is not a reader VowiDroid can drive (features ${descriptor?.features?.let { "%08x".format(it) } ?: "unknown"})")
            connection.releaseInterface(face)
            connection.close()
            return null
        }
        lateinit var handle: Handle
        val transport = BulkTransport(connection, bulkOut, bulkIn) { handle.closed }
        handle = Handle(device, connection, face, CcidReader(transport, descriptor), label(device))
        handles[device.deviceName] = handle
        Log.i(TAG, "opened ${handle.name}")
        return handle
    }

    @Synchronized
    private fun close(device: String) {
        val handle = handles.remove(device) ?: return
        handle.closed = true
        runCatching { handle.connection.releaseInterface(handle.face) }
        runCatching { handle.connection.close() }
        Log.i(TAG, "closed ${handle.name}")
    }

    private fun ccidDevices(): List<UsbDevice> =
        usb?.deviceList?.values?.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CSCID }
        }.orEmpty().sortedBy { it.deviceName }

    private fun label(device: UsbDevice): String =
        runCatching { device.productName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: app.getString(R.string.usb_reader_default_name)

    /** One claimed interface's bulk pair; reads ask for whole packets, as USB requires. */
    private class BulkTransport(
        private val connection: UsbDeviceConnection,
        private val output: UsbEndpoint,
        private val input: UsbEndpoint,
        private val closed: () -> Boolean,
    ) : CcidTransport {
        override fun bulkOut(data: ByteArray, timeoutMs: Int) {
            var offset = 0
            while (offset < data.size) {
                if (closed()) throw IOException("the reader was unplugged")
                val count = connection.bulkTransfer(output, data, offset, data.size - offset, timeoutMs)
                if (count < 0) throw IOException("the reader did not take the command")
                offset += count
            }
        }

        override fun bulkIn(maxLength: Int, timeoutMs: Int): ByteArray {
            if (closed()) throw IOException("the reader was unplugged")
            val packet = input.maxPacketSize.coerceAtLeast(8)
            val buffer = ByteArray(((maxLength + packet - 1) / packet) * packet)
            val count = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs)
            return if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
    }

    companion object {
        private const val TAG = "VowiUsbSim"
        private const val POLL_SECONDS = 3L
        const val ACTION_PERMISSION = "com.vowi.vowidroid.USB_PERMISSION"
        const val ISSUE_PERMISSION = "usb_permission"
        const val ISSUE_NO_CARD = "usb_no_card"
        const val ISSUE_PIN = SimCards.ISSUE_PIN
        const val ISSUE_UNUSABLE = "usb_unusable"
    }
}
