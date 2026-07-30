package ng1ok.yggdrasilmanager.root

import android.os.Parcel
import android.os.Parcelable

/** Binder-safe representation of one root-side HWBP event. */
class RootHwbpEvent(
    val seq: Long,
    val dropped: Long,
    val tid: Int,
    val tgid: Int,
    val comm: String,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val watchedAddr: Long,
    val pc: Long,
    val sp: Long,
    val pstate: Long,
    val stackAddr: Long,
    val stackRequestedSize: Int,
    val stackCopiedSize: Int,
    val regs: LongArray,
    val stack: ByteArray,
) : Parcelable {
    private constructor(source: Parcel) : this(
        seq = source.readLong(),
        dropped = source.readLong(),
        tid = source.readInt(),
        tgid = source.readInt(),
        comm = source.readString().orEmpty(),
        mode = source.readInt(),
        len = source.readInt(),
        flags = source.readInt(),
        watchedAddr = source.readLong(),
        pc = source.readLong(),
        sp = source.readLong(),
        pstate = source.readLong(),
        stackAddr = source.readLong(),
        stackRequestedSize = source.readInt(),
        stackCopiedSize = source.readInt(),
        regs = source.createLongArray() ?: LongArray(0),
        stack = source.createByteArray() ?: ByteArray(0),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(seq)
        dest.writeLong(dropped)
        dest.writeInt(tid)
        dest.writeInt(tgid)
        dest.writeString(comm)
        dest.writeInt(mode)
        dest.writeInt(len)
        dest.writeInt(this.flags)
        dest.writeLong(watchedAddr)
        dest.writeLong(pc)
        dest.writeLong(sp)
        dest.writeLong(pstate)
        dest.writeLong(stackAddr)
        dest.writeInt(stackRequestedSize)
        dest.writeInt(stackCopiedSize)
        dest.writeLongArray(regs)
        dest.writeByteArray(stack)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RootHwbpEvent> {
        override fun createFromParcel(source: Parcel): RootHwbpEvent = RootHwbpEvent(source)

        override fun newArray(size: Int): Array<RootHwbpEvent?> = arrayOfNulls(size)
    }
}
