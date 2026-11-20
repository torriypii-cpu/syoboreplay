package com.example.retrorec

/** エンコード済みAACフレーム（ADTSヘッダ付き）を直近 maxDurationUs ぶんだけ保持する */
class AacRingBuffer(private val maxDurationUs: Long) {
    private class Frame(val ptsUs: Long, val data: ByteArray)

    private val frames = ArrayDeque<Frame>()
    private var bytes = 0L

    @Synchronized
    fun add(ptsUs: Long, data: ByteArray) {
        frames.addLast(Frame(ptsUs, data))
        bytes += data.size
        while (frames.isNotEmpty() && ptsUs - frames.first().ptsUs > maxDurationUs) {
            bytes -= frames.removeFirst().data.size
        }
    }

    @Synchronized
    fun bufferedUs(): Long =
        if (frames.size < 2) 0 else frames.last().ptsUs - frames.first().ptsUs

    @Synchronized
    fun bufferedBytes(): Long = bytes

    /** 直近 lastUs ぶんのフレームを返す（ADTSなので単純連結で再生可能なファイルになる） */
    @Synchronized
    fun snapshot(lastUs: Long): List<ByteArray> {
        if (frames.isEmpty()) return emptyList()
        val from = frames.last().ptsUs - lastUs
        return frames.filter { it.ptsUs >= from }.map { it.data }
    }

    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
    }
}
