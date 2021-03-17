package com.softbankrobotics.peppermapping.util

import android.content.Context
import android.content.Context.MODE_APPEND
import android.content.Context.MODE_PRIVATE
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamableBuffer
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamableBufferFactory
import com.aldebaran.qi.sdk.util.copyToStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer


fun Context.writeToInternalStorage(string: String, outputFilename: String,
                                   appendIfExists: Boolean = false) {
    val mode = if (appendIfExists) MODE_PRIVATE or MODE_APPEND else MODE_PRIVATE
    val fileMap = openFileOutput(outputFilename, mode)
    fileMap.bufferedWriter().use { it.write(string) }
}

fun Context.readFromInternalStorage(inputFilename: String): String {
    val file = openFileInput(inputFilename)
    return file.bufferedReader().use { it.readText() }
}

fun Context.writeToInternalStorage(buffer: StreamableBuffer, outputFilename: String,
                                   appendIfExists: Boolean = false) {
    val mode = if (appendIfExists) MODE_PRIVATE or MODE_APPEND else MODE_PRIVATE
    val fileMap = openFileOutput(outputFilename, mode)
    buffer.copyToStream(fileMap)
}

fun Context.readStreamableBufferFromInternalStorage(inputFilename: String): StreamableBuffer {
    val file = openFileInput(inputFilename)
    return StreamableBufferFactory.fromFunction(file.channel.size()) { offset: Long, size: Long ->
        try {
            val byteArray = ByteArray(size.toInt())
            file.read(byteArray, 0, size.toInt())
            ByteBuffer.wrap(byteArray)
        } catch (e: FileNotFoundException) {
            ByteBuffer.allocate(0)
        } catch (e: IOException) {
            ByteBuffer.allocate(0)
        }
    }
}
