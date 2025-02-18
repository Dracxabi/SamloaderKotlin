package tk.zwander.common.view.pages

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.soywiz.korio.async.launch
import io.ktor.utils.io.core.internal.*
import kotlinx.coroutines.*
import tk.zwander.common.data.DownloadFileInfo
import tk.zwander.common.model.DownloadModel
import tk.zwander.common.tools.*
import tk.zwander.common.util.vectorResource
import tk.zwander.common.view.HybridButton
import tk.zwander.common.view.MRFLayout
import tk.zwander.common.view.ProgressInfo
import kotlin.time.ExperimentalTime

/**
 * The FusClient for retrieving firmware.
 */
@OptIn(DangerousInternalIoApi::class)
val client = FusClient()

/**
 * Delegate retrieving the download location to the platform.
 */
expect object PlatformDownloadView {
    suspend fun getInput(fileName: String, callback: suspend CoroutineScope.(DownloadFileInfo?) -> Unit)
    fun onStart()
    fun onFinish()
    fun onProgress(status: String, current: Long, max: Long)
}

/**
 * The Downloader View.
 * @param model the Download model.
 * @param scrollState a shared scroll state.
 */
@DangerousInternalIoApi
@ExperimentalTime
@Composable
fun DownloadView(model: DownloadModel, scrollState: ScrollState) {
    val canCheckVersion = !model.manual && model.model.isNotBlank()
            && model.region.isNotBlank() && model.job == null

    val canDownload = model.model.isNotBlank() && model.region.isNotBlank() && model.fw.isNotBlank()
            && model.job == null

    val canChangeOption = model.job == null

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        val rowSize = remember { mutableStateOf(0.dp) }

        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .onSizeChanged { rowSize.value = it.width.dp },
        ) {
            HybridButton(
                onClick = {
                    model.job = model.scope.launch(Dispatchers.Main) {
                        PlatformDownloadView.onStart()
                        try {
                            model.statusText = "Downloading"
                            val (path, fileName, size, crc32) = Request.getBinaryFile(client, model.fw, model.model, model.region)
                            val request = Request.createBinaryInit(fileName, client.nonce)

                            client.makeReq(FusClient.Request.BINARY_INIT, request)

                            val fullFileName = fileName.replace(".zip",
                                "_${model.fw.replace("/", "_")}_${model.region}.zip")

                            PlatformDownloadView.getInput(fullFileName) { info ->
                                if (info != null) {
                                    val (response, md5) = client.downloadFile(path + fileName, info.downloadFile.length)

                                    Downloader.download(response, size, info.downloadFile.openOutputStream(true), info.downloadFile.length) { current, max, bps ->
                                        model.progress = current to max
                                        model.speed = bps

                                        PlatformDownloadView.onProgress("Downloading", current, max)
                                    }

                                    model.speed = 0L

                                    if (crc32 != null) {
                                        model.statusText = "Checking CRC"
                                        val result = CryptUtils.checkCrc32(info.downloadFile.openInputStream(), size, crc32) { current, max, bps ->
                                            model.progress = current to max
                                            model.speed = bps

                                            PlatformDownloadView.onProgress("Checking CRC32", current, max)
                                        }

                                        if (!result) {
                                            model.endJob("CRC check failed. Please delete the file and download again.")
                                            return@getInput
                                        }
                                    }

                                    if (md5 != null) {
                                        model.statusText = "Checking MD5"
                                        model.progress = 1L to 2L

                                        PlatformDownloadView.onProgress("Checking MD5", 0, 1)

                                        val result = withContext(Dispatchers.Default) {
                                            CryptUtils.checkMD5(md5, info.downloadFile.openInputStream())
                                        }

                                        if (!result) {
                                            model.endJob("MD5 check failed. Please delete the file and download again.")
                                            return@getInput
                                        }
                                    }

                                    model.statusText = "Decrypting Firmware"

                                    val key = if (fullFileName.endsWith(".enc2")) CryptUtils.getV2Key(model.fw, model.model, model.region) else
                                        CryptUtils.getV4Key(model.fw, model.model, model.region)

                                    CryptUtils.decryptProgress(info.downloadFile.openInputStream(), info.decryptFile.openOutputStream(), key, size) { current, max, bps ->
                                        model.progress = current to max
                                        model.speed = bps

                                        PlatformDownloadView.onProgress("Decrypting", current, max)
                                    }

                                    model.endJob("Done")
                                } else {
                                    model.endJob("")
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                e.printStackTrace()
                                model.endJob("Error: ${e.message}")
                            }
                        }

                        PlatformDownloadView.onFinish()
                    }
                },
                enabled = canDownload,
                vectorIcon = vectorResource("download.xml"),
                text = "Download",
                description = "Download Firmware",
                parentSize = rowSize.value
            )

            Spacer(Modifier.width(8.dp))

            HybridButton(
                onClick = {
                    model.job = model.scope.launch {
                        val (fw, os) = try {
                            VersionFetch.getLatestVersion(model.model, model.region).also {
                                model.endJob("")
                            }
                        } catch (e: Exception) {
                            model.endJob("Error checking for firmware. Make sure the model and region are correct.\nMore info: ${e.message}")
                            "" to ""
                        }

                        model.fw = fw
                        model.osCode = os
                    }
                },
                enabled = canCheckVersion,
                text = "Check for Updates",
                vectorIcon = vectorResource("refresh.xml"),
                description = "Check for Firmware Updates",
                parentSize = rowSize.value
            )

            Spacer(Modifier.weight(1f))

            HybridButton(
                onClick = {
                    PlatformDownloadView.onFinish()
                    model.endJob("")
                },
                enabled = model.job != null,
                text = "Cancel",
                description = "Cancel",
                vectorIcon = vectorResource("cancel.xml"),
                parentSize = rowSize.value
            )
        }

        Spacer(Modifier.height(8.dp))

//        val boxSource = remember { MutableInteractionSource() }
//
//        Row(
//            modifier = Modifier.align(Alignment.End)
//                .clickable(
//                    interactionSource = boxSource,
//                    indication = null
//                ) {
//                    model.manual = !model.manual
//                }
//                .padding(4.dp)
//        ) {
//            Text(
//                text = "Manual",
//                modifier = Modifier.align(Alignment.CenterVertically)
//            )
//
//            Spacer(Modifier.width(8.dp))
//
//            Checkbox(
//                checked = model.manual,
//                onCheckedChange = {
//                    model.manual = it
//                },
//                modifier = Modifier.align(Alignment.CenterVertically),
//                enabled = canChangeOption,
//                colors = CheckboxDefaults.colors(
//                    checkedColor = MaterialTheme.colors.primary,
//                ),
//                interactionSource = boxSource
//            )
//        }
//
//        Spacer(Modifier.height(8.dp))

        MRFLayout(model, canChangeOption, model.manual && canChangeOption)

        if (!model.manual && model.osCode.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))

            Text(
                text = "OS Version: ${model.osCode}"
            )
        }

        Spacer(Modifier.height(16.dp))

        ProgressInfo(model)
    }
}