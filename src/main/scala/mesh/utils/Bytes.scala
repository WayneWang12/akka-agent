package mesh.utils

/**
 * CodecUtils.
 */
object Bytes {
  /**
   * to byte array.
   *
   * @param v value.
   * @param b byte array.
   */
  def short2bytes(v: Short, b: Array[Byte]): Unit = {
    short2bytes(v, b, 0)
  }

  def short2bytes(v: Short, b: Array[Byte], off: Int): Unit = {
    b(off + 1) = v.toByte
    b(off + 0) = (v >>> 8).toByte
  }

  /**
   * to byte array.
   *
   * @param v   value.
   * @param b   byte array.
   * @param off array offset.
   */
  def int2bytes(v: Int, b: Array[Byte], off: Int): Unit = {
    b(off + 3) = v.toByte
    b(off + 2) = (v >>> 8).toByte
    b(off + 1) = (v >>> 16).toByte
    b(off + 0) = (v >>> 24).toByte
  }

  def long2bytes(v: Long, b: Array[Byte], off: Int): Unit = {
    b(off + 7) = v.toByte
    b(off + 6) = (v >>> 8).toByte
    b(off + 5) = (v >>> 16).toByte
    b(off + 4) = (v >>> 24).toByte
    b(off + 3) = (v >>> 32).toByte
    b(off + 2) = (v >>> 40).toByte
    b(off + 1) = (v >>> 48).toByte
    b(off + 0) = (v >>> 56).toByte
  }

  /**
   * to long.
   *
   * @param b   byte array.
   * @param off offset.
   * @return long.
   */
  def bytes2long(b: Array[Byte], off: Int): Long = ((b(off + 7) & 0xFFL) << 0) + ((b(off + 6) & 0xFFL) << 8) + ((b(off + 5) & 0xFFL) << 16) + ((b(off + 4) & 0xFFL) << 24) + ((b(off + 3) & 0xFFL) << 32) + ((b(off + 2) & 0xFFL) << 40) + ((b(off + 1) & 0xFFL) << 48) + (b(off + 0).toLong << 56)
}

