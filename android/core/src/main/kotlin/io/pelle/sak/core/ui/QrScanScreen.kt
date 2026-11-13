package io.pelle.sak.core.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.core.R
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.SakProcess
import io.pelle.sak.core.Screen
import io.pelle.sak.core.qr.QrCamera
import io.pelle.sak.core.qr.QrScan

// the content of an app: the shared screens before the sign-in (UX-7), else the screens of the app;
// the scan of a QR code over them while it is open. The screen below stays as it was and is hidden
// from talkback meanwhile. appName names the app on the first screen
@Composable
fun SakContent(
    model: SakModel<*>,
    appName: String,
    content: @Composable () -> Unit,
) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val scan by model.scan.collectAsStateWithLifecycle()
    val open = scan
    Box(Modifier.fillMaxSize()) {
        Box(if (open != null) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
            when (val current = screen) {
                is Screen.Deployment -> DeploymentScreen(appName, current, model::connect, model::scanQr)
                is Screen.SignIn -> SignInScreen(current, model)
                else -> content()
            }
        }
        if (open != null) QrScanScreen(open, model)
    }
}

// the scan of the QR code of the web app that connects the app (SECURITY-3): the camera asked for
// when the scan opens, not before (PRIVACY-7), its preview with the frame to aim at, the light and
// back; refused, the scan says why and leads to the settings of the app or back to the sign-in without
// a code. Signed in already, a dialog asks before the code replaces the session
@Composable
fun QrScanScreen(
    scan: QrScan,
    model: SakModel<*>,
    camera: QrCamera = SakProcess.qrCamera,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.mayUseCamera()) }
    // asked once for each scan, not again after a rotation
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
            if (allowed) model.cameraAllowed() else model.cameraDenied()
        }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    // back from the settings of the app, where the camera may have been allowed
    LifecycleResumeEffect(Unit) {
        if (!granted && context.mayUseCamera()) {
            granted = true
            model.cameraAllowed()
        }
        onPauseOrDispose {}
    }
    // the keyboard of the field below goes
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    BackHandler(onBack = model::closeScan)
    if (scan.denied && !granted) {
        CameraDenied(context, model)
    } else {
        Scanner(scan, granted, camera, model)
    }
    scan.confirm?.let { switch ->
        AlertDialog(
            onDismissRequest = model::closeScan,
            title = { Text(stringResource(if (switch.same) R.string.qr_again_title else R.string.qr_switch_title)) },
            text = {
                Text(
                    if (switch.same) {
                        stringResource(R.string.qr_again_text, switch.from, switch.user)
                    } else {
                        stringResource(R.string.qr_switch_text, switch.from, switch.to)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = model::confirmQr, modifier = Modifier.testTag("qr-confirm")) {
                    Text(stringResource(if (switch.same) R.string.login_sign_in else R.string.qr_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = model::closeScan, modifier = Modifier.testTag("qr-stay")) { Text(stringResource(R.string.qr_stay)) }
            },
        )
    }
}

// the preview full-screen, the frame to aim at in its middle; back and the light at the top, what the
// scan does below the frame, at the side in landscape
@Composable
private fun Scanner(
    scan: QrScan,
    granted: Boolean,
    camera: QrCamera,
    model: SakModel<*>,
) {
    var torch by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val hasLight = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("qr-scanner")) {
        val landscape = maxWidth > maxHeight
        if (granted) camera.Preview(torch, model::signInWithQr, Modifier.fillMaxSize())
        Viewfinder(Modifier.fillMaxSize())
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = model::closeScan, modifier = Modifier.testTag("qr-back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.qr_back))
            }
            Spacer(Modifier.weight(1f))
            // a phone without a light has the button disabled (UX-8)
            FilledIconToggleButton(
                checked = torch,
                onCheckedChange = { torch = it },
                enabled = hasLight && granted,
                modifier = Modifier.testTag("qr-torch"),
            ) {
                Icon(
                    painterResource(R.drawable.ic_bolt),
                    contentDescription = stringResource(if (torch) R.string.qr_torch_off else R.string.qr_torch_on),
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier
                    .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .then(if (landscape) Modifier.width(260.dp) else Modifier.fillMaxWidth()),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    scan.error != null -> {
                        Error(scan.error)
                        Button(onClick = model::scanAgain, modifier = Modifier.testTag("qr-scan-again")) { Text(stringResource(R.string.qr_scan_again)) }
                    }
                    scan.busy ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(stringResource(R.string.qr_signing_in))
                        }
                    else -> Text(stringResource(R.string.qr_point), modifier = Modifier.testTag("qr-point"))
                }
            }
        }
    }
}

// the frame to aim at: a square in the middle, the preview around it darker
@Composable
private fun Viewfinder(modifier: Modifier) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.65f
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
        val corner = CornerRadius(24.dp.toPx())
        val hole = RoundRect(topLeft.x, topLeft.y, topLeft.x + side, topLeft.y + side, corner)
        val shade =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addRoundRect(hole)
            }
        drawPath(shade, Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.White, topLeft, Size(side, side), corner, style = Stroke(width = 3.dp.toPx()))
    }
}

// the camera was refused: why the scan cannot work, the settings of the app, where it can be allowed,
// and the sign-in without a code
@Composable
private fun CameraDenied(
    context: Context,
    model: SakModel<*>,
) {
    Surface(Modifier.fillMaxSize().testTag("qr-denied")) {
        Page(
            stringResource(R.string.qr_scan),
            navigationIcon = {
                IconButton(onClick = model::closeScan, modifier = Modifier.testTag("qr-back")) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.qr_back))
                }
            },
        ) { padding ->
            Form(padding) {
                Text(stringResource(R.string.qr_denied))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.testTag("qr-settings"),
                ) { Text(stringResource(R.string.qr_open_settings)) }
                OutlinedButton(onClick = model::closeScan, modifier = Modifier.testTag("qr-without-code")) {
                    Text(stringResource(R.string.qr_without_code))
                }
            }
        }
    }
}

private fun Context.mayUseCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
