package cn.shixu.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while(true) {
        val count = read(buffer)
        if(count < 0) break
        require(out.size()+count <= limit) { "文件或响应超出大小限制" }
        out.write(buffer,0,count)
    }
    return out.toByteArray()
}
