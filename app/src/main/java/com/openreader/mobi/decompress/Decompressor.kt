package com.openreader.mobi.decompress

interface Decompressor {

    fun decompress(data: ByteArray): ByteArray

}
