package ng1ok.yggdrasilmanager.root

import android.os.Parcel
import android.os.Parcelable

/** Binder-safe representation of one root-side syscall event. */
class RootSyscallEvent(
    val seq: Long,
    val dropped: Long,
    val callId: Long,
    val phase: Int,
    val tid: Int,
    val tgid: Int,
    val syscallNr: Int,
    val eventFlags: Int,
    val ruleFlags: Int,
    val targetId: Long,
    val comm: String,
    val userCookie: Long,
    val argMask: Long,
    val argsBefore: LongArray,
    val argsAfter: LongArray,
    val returnBefore: Long,
    val returnAfter: Long,
    val captureCount: Int,
    val capturePayload: ByteArray,
    val relayDropped: Long,
    val backtraceFlags: Int = 0,
    val backtraceSeq: Long = 0L,
    val backtraceDropped: Long = 0L,
    val backtraceCallId: Long = 0L,
    val backtracePhase: Int = 0,
    val backtraceTid: Int = 0,
    val backtraceTgid: Int = 0,
    val backtraceSyscallNr: Int = 0,
    val backtraceStackRequestedSize: Int = 0,
    val backtraceStackCopiedSize: Int = 0,
    val backtraceStackAddr: Long = 0L,
    val backtraceRegs: LongArray = LongArray(0),
    val backtraceStack: ByteArray = ByteArray(0),
) : Parcelable {
    private constructor(source: Parcel) : this(
        seq = source.readLong(),
        dropped = source.readLong(),
        callId = source.readLong(),
        phase = source.readInt(),
        tid = source.readInt(),
        tgid = source.readInt(),
        syscallNr = source.readInt(),
        eventFlags = source.readInt(),
        ruleFlags = source.readInt(),
        targetId = source.readLong(),
        comm = source.readString().orEmpty(),
        userCookie = source.readLong(),
        argMask = source.readLong(),
        argsBefore = source.createLongArray() ?: LongArray(0),
        argsAfter = source.createLongArray() ?: LongArray(0),
        returnBefore = source.readLong(),
        returnAfter = source.readLong(),
        captureCount = source.readInt(),
        capturePayload = source.createByteArray() ?: ByteArray(0),
        relayDropped = source.readLong(),
        backtraceFlags = source.readInt(),
        backtraceSeq = source.readLong(),
        backtraceDropped = source.readLong(),
        backtraceCallId = source.readLong(),
        backtracePhase = source.readInt(),
        backtraceTid = source.readInt(),
        backtraceTgid = source.readInt(),
        backtraceSyscallNr = source.readInt(),
        backtraceStackRequestedSize = source.readInt(),
        backtraceStackCopiedSize = source.readInt(),
        backtraceStackAddr = source.readLong(),
        backtraceRegs = source.createLongArray() ?: LongArray(0),
        backtraceStack = source.createByteArray() ?: ByteArray(0),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(seq)
        dest.writeLong(dropped)
        dest.writeLong(callId)
        dest.writeInt(phase)
        dest.writeInt(tid)
        dest.writeInt(tgid)
        dest.writeInt(syscallNr)
        dest.writeInt(eventFlags)
        dest.writeInt(ruleFlags)
        dest.writeLong(targetId)
        dest.writeString(comm)
        dest.writeLong(userCookie)
        dest.writeLong(argMask)
        dest.writeLongArray(argsBefore)
        dest.writeLongArray(argsAfter)
        dest.writeLong(returnBefore)
        dest.writeLong(returnAfter)
        dest.writeInt(captureCount)
        dest.writeByteArray(capturePayload)
        dest.writeLong(relayDropped)
        dest.writeInt(backtraceFlags)
        dest.writeLong(backtraceSeq)
        dest.writeLong(backtraceDropped)
        dest.writeLong(backtraceCallId)
        dest.writeInt(backtracePhase)
        dest.writeInt(backtraceTid)
        dest.writeInt(backtraceTgid)
        dest.writeInt(backtraceSyscallNr)
        dest.writeInt(backtraceStackRequestedSize)
        dest.writeInt(backtraceStackCopiedSize)
        dest.writeLong(backtraceStackAddr)
        dest.writeLongArray(backtraceRegs)
        dest.writeByteArray(backtraceStack)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RootSyscallEvent> {
        override fun createFromParcel(source: Parcel): RootSyscallEvent = RootSyscallEvent(source)

        override fun newArray(size: Int): Array<RootSyscallEvent?> = arrayOfNulls(size)
    }
}
