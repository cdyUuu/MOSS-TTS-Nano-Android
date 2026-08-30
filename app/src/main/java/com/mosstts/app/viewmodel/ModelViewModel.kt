package com.mosstts.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mosstts.app.MossTTSApp
import com.mosstts.app.data.ModelDownloader
import com.mosstts.app.service.ModelDownloadService
import com.mosstts.app.util.AppLogger
import com.mosstts.app.util.ModelExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = (application as MossTTSApp).modelDownloader

    val mirrorSources = ModelDownloader.MIRROR_SOURCES

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _downloadProgress = MutableStateFlow<ModelDownloader.DownloadProgress?>(null)
    val downloadProgress: StateFlow<ModelDownloader.DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isVoiceCloneReady = MutableStateFlow(false)
    val isVoiceCloneReady: StateFlow<Boolean> = _isVoiceCloneReady.asStateFlow()

    private val _modelSize = MutableStateFlow(0L)
    val modelSize: StateFlow<Long> = _modelSize.asStateFlow()

    private val _selectedMirrorId = MutableStateFlow("hf_mirror")
    val selectedMirrorId: StateFlow<String> = _selectedMirrorId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _exportProgress = MutableStateFlow("" )
    val exportProgress: StateFlow<String> = _exportProgress.asStateFlow()

    private val _isTestingSpeed = MutableStateFlow(false)
    val isTestingSpeed: StateFlow<Boolean> = _isTestingSpeed.asStateFlow()

    private val _speedTestResults = MutableStateFlow<List<ModelDownloader.MirrorSpeedTestResult>>(emptyList())
    val speedTestResults: StateFlow<List<ModelDownloader.MirrorSpeedTestResult>> = _speedTestResults.asStateFlow()

    // 引擎初始化回调（由外部设置）
    var onEngineInitRequested: (() -> Unit)? = null

    init {
        checkModelStatus()
        viewModelScope.launch {
            downloader.progress.collect {
                _downloadProgress.value = it
                if (it?.isComplete == true) {
                    _isDownloading.value = false
                    _isPaused.value = false
                    checkModelStatus()
                    onEngineInitRequested?.invoke()
                }
            }
        }
    }

    fun checkModelStatus() {
        _isModelReady.value = downloader.isModelReady()
        _isVoiceCloneReady.value = downloader.isVoiceCloneReady()
        _modelSize.value = downloader.getModelSize()
    }

    fun selectMirror(mirrorId: String) {
        _selectedMirrorId.value = mirrorId
    }

    fun startDownload() {
        if (_isDownloading.value && !_isPaused.value) return
        if (_isPaused.value) {
            resumeDownload()
            return
        }
        _isDownloading.value = true
        _isPaused.value = false
        _errorMessage.value = null
        AppLogger.info("ModelViewModel", "开始下载模型，镜像源: ${_selectedMirrorId.value}")
        // 启动前台服务（保活+通知）
        ModelDownloadService.start(getApplication())
        // 实际下载在 ViewModel 中执行
        viewModelScope.launch {
            try {
                val success = downloader.downloadAll(_selectedMirrorId.value)
                _isDownloading.value = false
                _isPaused.value = false
                if (success) {
                    checkModelStatus()
                    onEngineInitRequested?.invoke()
                    AppLogger.info("ModelViewModel", "模型下载完成")
                } else {
                    _errorMessage.value = _downloadProgress.value?.error ?: "下载失败，请检查网络后重试"
                    AppLogger.error("ModelViewModel", "模型下载失败")
                }
            } catch (e: Exception) {
                _isDownloading.value = false
                _isPaused.value = false
                _errorMessage.value = "下载异常: ${e.message}"
                AppLogger.error("ModelViewModel", "下载异常", e)
            } finally {
                // 停止前台服务
                ModelDownloadService.cancel(getApplication())
            }
        }
    }

    fun pauseDownload() {
        if (!_isDownloading.value || _isPaused.value) return
        _isPaused.value = true
        AppLogger.info("ModelViewModel", "暂停下载")
        ModelDownloadService.pause(getApplication())
    }

    fun resumeDownload() {
        if (!_isDownloading.value || !_isPaused.value) return
        _isPaused.value = false
        AppLogger.info("ModelViewModel", "继续下载")
        ModelDownloadService.resume(getApplication())
    }

    fun cancelDownload() {
        _isDownloading.value = false
        _isPaused.value = false
        AppLogger.info("ModelViewModel", "取消下载")
        ModelDownloadService.cancel(getApplication())
    }

    fun exportModels(outputUri: Uri) {
        viewModelScope.launch {
            _isExporting.value = true
            _exportProgress.value = "准备导出..."
            AppLogger.info("ModelViewModel", "开始导出模型到: $outputUri")
            val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelExporter.exportModels(getApplication(), outputUri) { current, total, fileName ->
                    _exportProgress.value = "正在导出 ($current/$total): $fileName"
                }
            }
            _isExporting.value = false
            _exportProgress.value = ""
            if (success) {
                AppLogger.info("ModelViewModel", "模型导出成功")
                _successMessage.value = "模型导出成功，文件已保存到选择的位置"
            } else {
                _errorMessage.value = "模型导出失败"
                AppLogger.error("ModelViewModel", "模型导出失败")
            }
        }
    }

    fun importModels(inputUri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            AppLogger.info("ModelViewModel", "开始导入模型: $inputUri")
            val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelExporter.importModels(getApplication(), inputUri)
            }
            _isImporting.value = false
            if (success) {
                checkModelStatus()
                AppLogger.info("ModelViewModel", "模型导入成功")
                _successMessage.value = "模型导入成功，已加载新模型"
            } else {
                _errorMessage.value = "模型导入失败，请检查文件格式"
                AppLogger.error("ModelViewModel", "模型导入失败")
            }
        }
    }

    fun testMirrorSpeeds() {
        if (_isTestingSpeed.value) return
        _isTestingSpeed.value = true
        _speedTestResults.value = emptyList()
        viewModelScope.launch {
            try {
                val results = downloader.testAllMirrorsSpeed()
                _speedTestResults.value = results
                // 自动选择最快的镜像源
                val fastest = results.firstOrNull { it.success }
                if (fastest != null) {
                    _selectedMirrorId.value = fastest.mirrorId
                    AppLogger.info("ModelViewModel", "自动选择最快镜像源: ${fastest.mirrorName}")
                }
            } catch (e: Exception) {
                AppLogger.error("ModelViewModel", "测速失败", e)
            } finally {
                _isTestingSpeed.value = false
            }
        }
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond <= 0 -> "0 B/s"
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            else -> String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024))
        }
    }

    fun deleteModels() {
        downloader.deleteModels()
        checkModelStatus()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSuccess() {
        _successMessage.value = null
    }

    fun formatSize(bytes: Long): String {
        return ModelExporter.formatSize(bytes)
    }
}
