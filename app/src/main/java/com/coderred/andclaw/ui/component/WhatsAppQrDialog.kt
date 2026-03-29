package com.coderred.andclaw.ui.component

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.format.DateFormat
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.coderred.andclaw.R
import com.coderred.andclaw.ui.screen.dashboard.WhatsAppQrState
import com.coderred.andclaw.ui.theme.StatusRunning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun WhatsAppQrDialog(
    state: WhatsAppQrState,
    onDismiss: () -> Unit,
    onStartGateway: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    AlertDialog(
        // 바깥 터치/뒤로가기로 닫히지 않게 고정한다.
        // 닫기는 하단 Close 버튼으로만 허용한다.
        onDismissRequest = {},
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.dashboard_whatsapp_qr_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is WhatsAppQrState.Loading -> {
                        Box(
                            modifier = Modifier.size(256.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.whatsapp_qr_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is WhatsAppQrState.Waiting -> {
                        Box(
                            modifier = Modifier.size(256.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.welcome_connecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is WhatsAppQrState.QrReady -> {
                        val bitmap = remember(state.qrData, state.isDataUrl) {
                            if (state.isDataUrl) {
                                decodeDataUrlBitmap(state.qrData)
                            } else {
                                generateQrBitmap(state.qrData, 512)
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "WhatsApp QR Code",
                                modifier = Modifier.size(256.dp),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.dashboard_whatsapp_qr_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.dashboard_whatsapp_qr_instruction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        QrMetadataText(state = state)
                    }
                    is WhatsAppQrState.Connected -> {
                        Box(
                            modifier = Modifier.size(256.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusRunning,
                                modifier = Modifier.size(80.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.whatsapp_connected),
                            style = MaterialTheme.typography.titleMedium,
                            color = StatusRunning,
                        )
                    }
                    is WhatsAppQrState.Error -> {
                        Box(
                            modifier = Modifier.size(256.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.whatsapp_connect_error, state.message),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    is WhatsAppQrState.GatewayNotRunning -> {
                        Box(
                            modifier = Modifier.size(256.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.whatsapp_gateway_not_running),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> { /* Idle — should not be shown */ }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is WhatsAppQrState.QrReady) {
                    TextButton(
                        onClick = {
                            val bitmap = if (state.isDataUrl) {
                                decodeDataUrlBitmap(state.qrData)
                            } else {
                                generateQrBitmap(state.qrData, 512)
                            }
                            if (bitmap != null) {
                                shareQrBitmap(context, bitmap)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.whatsapp_qr_share))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (state is WhatsAppQrState.GatewayNotRunning && onStartGateway != null) {
                    TextButton(onClick = onStartGateway) {
                        Text(stringResource(R.string.whatsapp_start_gateway))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_model_close))
                }
            }
        },
    )
}

@Composable
private fun QrMetadataText(state: WhatsAppQrState.QrReady) {
    val context = LocalContext.current
    val issuedAtText = remember(state.issuedAtMs) {
        DateFormat.getTimeFormat(context).format(Date(state.issuedAtMs))
    }
    val nowMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = state.issuedAtMs,
        key2 = state.expiresAtMs,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remainingMs = (state.expiresAtMs - nowMs).coerceAtLeast(0L)
    val remainingText = remember(remainingMs) { formatRemainingMmSs(remainingMs) }

    Text(
        text = "#${state.attempt}  ·  $issuedAtText  ·  ⏳$remainingText",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatRemainingMmSs(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun shareQrBitmap(context: android.content.Context, bitmap: Bitmap) {
    try {
        val sharedDir = File(context.cacheDir, "shared")
        sharedDir.mkdirs()
        val file = File(sharedDir, "whatsapp_qr.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
        // 공유 실패 시 무시
    }
}

private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? {
    return try {
        // data:image/png;base64,<data>
        val base64Data = dataUrl.substringAfter(",")
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }
}

private fun generateQrBitmap(data: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
