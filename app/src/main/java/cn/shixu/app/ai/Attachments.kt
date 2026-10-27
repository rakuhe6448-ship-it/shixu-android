package cn.shixu.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import cn.shixu.app.data.readBounded
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object Attachments {
    fun directory(context: Context) = File(context.filesDir,"attachments").apply { mkdirs() }
    fun file(context: Context, name: String): File {
        require(Regex("[a-zA-Z0-9-]+\\.jpg").matches(name)) { "图片路径不合法" }
        return File(directory(context),name)
    }
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(20*1024*1024) } ?: error("无法读取图片")
        require(bytes.size <= 20*1024*1024) { "单张图片不能超过 20 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别这张图片" }
        var sample = 1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample > 2000) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("图片解码失败")
        val exif=runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()
        val matrix=Matrix().apply {
            if(exif?.isFlipped == true) postScale(-1f,1f)
            postRotate((exif?.rotationDegrees ?: 0).toFloat())
        }
        val bitmap=if(matrix.isIdentity) decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also { decoded.recycle() }
        val name = UUID.randomUUID().toString()+".jpg"
        try { file(context,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) } } finally { bitmap.recycle() }
        name
    }
    suspend fun ocr(context: Context, names: List<String>): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val results=mutableListOf<String>()
            for(name in names) results += recognizer.process(InputImage.fromFilePath(context,Uri.fromFile(file(context,name)))).await().text
            results.joinToString("\n\n")
        }
        finally { recognizer.close() }
    }
}
