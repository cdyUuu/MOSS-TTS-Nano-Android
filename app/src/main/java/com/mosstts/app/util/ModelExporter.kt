package com.mosstts.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 模型导出/导入工具。将模型文件打包为 zip 导出，或从 zip 导入。
 */
object ModelExporter {

    private const val TAG = "ModelExporter"
    private const val MODEL_DIR_NAME = "models"

    /**
     * 导出模型到指定 Uri。
     */
    fun exportModels(
        context: Context,
        outputUri: Uri,
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null,
    ): Boolean {
        return try {
            val modelDir = File(context.filesDir, MODEL_DIR_NAME)
            if (!modelDir.exists()) {
                AppLogger.error(TAG, "模型目录不存在: ${modelDir.absolutePath}")
                return false
            }

            val files = modelDir.walkTopDown().filter { it.isFile }.toList()
            val totalSize = files.sumOf { it.length() }
            AppLogger.info(TAG, "开始导出模型，文件数: ${files.size}, 总大小: ${formatSize(totalSize)}")

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    zipOut.setLevel(java.util.zip.Deflater.BEST_SPEED)
                    files.forEachIndexed { index, file ->
                        val relativePath = file.relativeTo(modelDir).path
                        AppLogger.debug(TAG, "添加文件: $relativePath (${formatSize(file.length())})")
                        onProgress?.invoke(index, files.size, file.name)
                        zipOut.putNextEntry(ZipEntry(relativePath))
                        FileInputStream(file).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zipOut.write(buffer, 0, read)
                            }
                        }
                        zipOut.closeEntry()
                    }
                    onProgress?.invoke(files.size, files.size, "完成")
                }
            }
            AppLogger.info(TAG, "模型导出成功")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "模型导出失败: ${e.message}", e)
            false
        }
    }

    /**
     * 从指定 Uri 导入模型。
     */
    fun importModels(context: Context, inputUri: Uri): Boolean {
        return try {
            val modelDir = File(context.filesDir, MODEL_DIR_NAME)
            val tempDir = File(context.filesDir, "models_import_temp")

            // 清理临时目录
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            tempDir.mkdirs()

            AppLogger.info(TAG, "开始导入模型...")

            // 解压到临时目录
            var fileCount = 0
            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outputFile = File(tempDir, entry.name)
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { output ->
                                zipIn.copyTo(output)
                            }
                            fileCount++
                            AppLogger.debug(TAG, "解压文件: ${entry.name} (${outputFile.length() / 1024}KB)")
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            AppLogger.info(TAG, "解压完成，文件数: $fileCount")

            // 验证关键文件
            val ttsDir = File(tempDir, "MOSS-TTS-Nano-100M-ONNX")
            val codecDir = File(tempDir, "MOSS-Audio-Tokenizer-Nano-ONNX")
            if (!ttsDir.exists() || !codecDir.exists()) {
                AppLogger.error(TAG, "导入失败：模型目录结构不正确")
                tempDir.deleteRecursively()
                return false
            }

            // 替换旧模型
            if (modelDir.exists()) {
                val backupDir = File(context.filesDir, "models_backup_${System.currentTimeMillis()}")
                modelDir.renameTo(backupDir)
                AppLogger.info(TAG, "旧模型已备份到: ${backupDir.name}")
            }

            tempDir.renameTo(modelDir)
            AppLogger.info(TAG, "模型导入成功")
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "模型导入失败: ${e.message}", e)
            false
        }
    }

    /**
     * 获取模型目录大小。
     */
    fun getModelSize(context: Context): Long {
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        return if (modelDir.exists()) {
            modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0
    }

    /**
     * 格式化文件大小。
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
