package com.toss.cardapp.hw

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.toss.cardapp.hw.sp.SpLink
import com.toss.cardapp.hw.sp.SpReader

interface CardEvents {
    fun onMagCard(track1: String?, track2: String?, track3: String?)
    fun onIcCard(atr: String?)
    fun onError(code: Int, message: String?)
}

/**
 * Raw-binder client for the stock SunmiPayHardwareService (com.sunmi.pay.hardware_v3), taken from the
 * com.toss.cardprobe probe. Transaction codes come from the service's ReadCardOptV2 stub (v3.3.357).
 * Only card detection, APDU exchange and card power-off are used; no EMV kernel, PIN or key APIs.
 */
class PayHardware(private val context: Context) : ServiceConnection {

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
    }

    var listener: ConnectionListener? = null
    @Volatile private var readCard: IBinder? = null
    @Volatile private var direct = false
    private var bound = false

    val connected: Boolean get() = direct || readCard != null

    // Stock service first; without it, talk to the reader directly over its UART (hw/sp).
    fun bind(): Boolean {
        if (bound || direct) return true
        bound = context.bindService(Intent(SVC_ACTION).setPackage(SVC_PKG), this, Context.BIND_AUTO_CREATE)
        if (bound) return true
        if (!SpLink.available()) return false
        Thread {
            direct = try { SpReader.connect(); true } catch (e: Exception) { false }
            if (direct) listener?.onConnected() else listener?.onDisconnected()
        }.start()
        return true
    }

    fun unbind() {
        cancelCheckCard()
        if (bound) context.unbindService(this)
        bound = false
        readCard = null
        if (direct) SpReader.disconnect()
        direct = false
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        readCard = try { getReadCardOpt(service) } catch (e: RemoteException) { null }
        if (readCard != null) listener?.onConnected() else listener?.onDisconnected()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        readCard = null
        listener?.onDisconnected()
    }

    private fun getReadCardOpt(svc: IBinder): IBinder? = call(svc, DEVICE_PROVIDE, TX_GET_READ_CARD_OPT_V2, {}) {
        it.readStrongBinder()
    }

    fun checkCard(cardTypes: Int, timeoutSec: Int, events: CardEvents) {
        if (direct) return SpReader.checkCard(cardTypes and CARD_IC != 0, cardTypes and CARD_MAGNETIC != 0, timeoutSec, events)
        val rc = readCard ?: throw RemoteException("service not connected")
        call(rc, READ_CARD, TX_CHECK_CARD, {
            it.writeInt(cardTypes)
            it.writeStrongBinder(Callback(events))
            it.writeInt(timeoutSec)
        }) {}
    }

    fun cancelCheckCard() {
        if (direct) return SpReader.cancelCheckCard()
        val rc = readCard ?: return
        try { call(rc, READ_CARD, TX_CANCEL_CHECK_CARD, {}) {} } catch (_: RemoteException) {}
    }

    fun transmitApdu(cardType: Int, apdu: ByteArray): ByteArray {
        if (direct) return SpReader.transmitApdu(apdu)
        val rc = readCard ?: throw RemoteException("service not connected")
        return call(rc, READ_CARD, TX_TRANSMIT_APDU, {
            it.writeInt(cardType)
            it.writeByteArray(apdu)
            it.writeInt(RECV_BUFFER)
        }) { r ->
            val len = r.readInt()
            val buf = r.createByteArray() ?: ByteArray(0)
            if (len < 0) throw RemoteException("transmitApdu error $len")
            buf.copyOf(minOf(len, buf.size))
        }
    }

    fun cardOff(cardType: Int): Int {
        if (direct) return try { SpReader.cardOff() } catch (_: Exception) { -1 }
        val rc = readCard ?: return -1
        return try { call(rc, READ_CARD, TX_CARD_OFF, { it.writeInt(cardType) }) { it.readInt() } } catch (_: RemoteException) { -1 }
    }

    fun cardExistStatus(cardType: Int): Int {
        if (direct) return try { SpReader.cardExistStatus() } catch (_: Exception) { -1 }
        val rc = readCard ?: return -1
        return try { call(rc, READ_CARD, TX_CARD_EXIST_STATUS, { it.writeInt(cardType) }) { it.readInt() } } catch (_: RemoteException) { -1 }
    }

    private inline fun <T> call(target: IBinder, iface: String, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(iface)
            write(d)
            target.transact(code, d, r, 0)
            r.readException()
            return read(r)
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private class Callback(private val events: CardEvents) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            data.enforceInterface(CALLBACK)
            when (code) {
                1 -> readBundle(data).let { b -> events.onMagCard(b?.getString("TRACK1"), b?.getString("TRACK2"), b?.getString("TRACK3")) }
                2 -> events.onIcCard(data.readString())
                4 -> events.onError(data.readInt(), data.readString())
                5 -> events.onIcCard(readBundle(data)?.getString("atr"))
                7 -> readBundle(data).let { b -> events.onError(b?.getInt("code") ?: -1, b?.getString("message")) }
                3, 6 -> events.onError(ERR_UNSUPPORTED_CARD, "contactless card")
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }

        private fun readBundle(p: Parcel): Bundle? =
            if (p.readInt() != 0) Bundle.CREATOR.createFromParcel(p).also { it.classLoader = javaClass.classLoader } else null
    }

    companion object {
        const val CARD_MAGNETIC = 1
        const val CARD_IC = 2
        const val ERR_UNSUPPORTED_CARD = -9001

        private const val SVC_PKG = "com.sunmi.pay.hardware_v3"
        private const val SVC_ACTION = "sunmi.intent.action.PAY_HARDWARE"
        private const val DEVICE_PROVIDE = "com.sunmi.pay.hardware.aidl.DeviceProvide"
        private const val READ_CARD = "com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2"
        private const val CALLBACK = "com.sunmi.pay.hardware.aidlv2.readcard.CheckCardCallbackV2"
        private const val TX_GET_READ_CARD_OPT_V2 = 10
        private const val TX_CHECK_CARD = 1
        private const val TX_CANCEL_CHECK_CARD = 2
        private const val TX_TRANSMIT_APDU = 5
        private const val TX_CARD_OFF = 6
        private const val TX_CARD_EXIST_STATUS = 7
        private const val RECV_BUFFER = 512
    }
}
