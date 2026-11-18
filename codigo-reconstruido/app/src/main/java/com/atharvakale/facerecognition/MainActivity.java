package com.atharvakale.facerecognition;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LifecycleOwner;

import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.Pair;
import android.util.Size;
import android.view.View;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ImmersiveUi goldenImmersiveUi;
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (goldenImmersiveUi != null) goldenImmersiveUi.onFocusChanged(hasFocus);
    }

    private GoldenIntegration golden;
    private boolean activityCameraBound;
    private boolean previewResumed;
    private static final int PREVIEW_NOTIFICATION_REQUEST_CODE = 102;
    private final android.os.Handler previewNoticeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable previewNoticeExpiry = new Runnable() {
        @Override public void run() {
            if (!previewResumed) return;
            FaceNotifications.tick(MainActivity.this, FaceNotifications.PREVIEW, android.os.SystemClock.elapsedRealtime());
            previewNoticeHandler.postDelayed(this, 250);
        }
    };
    private void observePreviewFace(String name) {
        if (!previewResumed || !activityCameraBound || FaceCameraService.active || enrollmentOpen || !start) return;
        if (!FaceNotifications.owns(FaceNotifications.PREVIEW)) FaceNotifications.acquire(this, FaceNotifications.PREVIEW);
        FaceNotifications.observe(this, FaceNotifications.PREVIEW, name, android.os.SystemClock.elapsedRealtime());
    }
    // Only runtime permissions needed for the visible camera and face notifications.
    // Flags survive recreation and process death; never automatically repeat a denial.
    private static final String PERM_PREF = "GoldenFirstPermissions";
    private boolean permDialogVisible;
    private boolean permRequestInFlight;
    private boolean permRecoveryPending;
    private android.content.SharedPreferences permissionPrefs() { return getSharedPreferences(PERM_PREF, MODE_PRIVATE); }
    private boolean cameraAllowed() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean notificationAllowed() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean canPresentPermissionUi() {
        return !isFinishing() && !isDestroyed() && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
    }
    private void openPermissionSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, gfText("Abra Configurações > Apps > Golden Face > Permissões e notificações.", "Open Settings > Apps > Golden Face > Permissions and notifications."), Toast.LENGTH_LONG).show();
        }
    }
    private void requestPermissionFromUser(String permission, int code, String askedKey) {
        if (permRequestInFlight || !canPresentPermissionUi()) return;
        boolean asked = permissionPrefs().getBoolean(askedKey, false);
        if (asked && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            openPermissionSettings();
            return;
        }
        permissionPrefs().edit().putBoolean(askedKey, true).apply();
        permRequestInFlight = true;
        androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{permission}, code);
    }
    private void showPermissionHelp() {
        if (!canPresentPermissionUi() || permDialogVisible) { permRecoveryPending = true; return; }
        permRecoveryPending = false;
        permDialogVisible = true;
        String camera = cameraAllowed() ? gfText("permitida", "allowed") : gfText("não permitida", "not allowed");
        String notices = notificationAllowed() && NotificationManagerCompat.from(this).areNotificationsEnabled() ? gfText("permitidas", "allowed") : gfText("não permitidas", "not allowed");
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(gfText("Permissões do Golden Face", "Golden Face permissions"))
            .setMessage(gfText("Câmera: ", "Camera: ") + camera + gfText("\nNotificações: ", "\nNotifications: ") + notices + gfText("\n\nA câmera identifica rostos no aparelho. As notificações entregam o nome à Golden e mostram o serviço. Você pode continuar sem elas. Em Configurações, confira também o canal Golden Face rostos.", "\n\nThe camera identifies faces on the device. Notifications deliver the name to Golden and show the service. You can continue without them. In Settings, also check the Golden Face faces channel."))
            .setPositiveButton(gfText("Tentar permitir", "Try to allow"), (d,w) -> {
                if (!cameraAllowed()) requestPermissionFromUser(Manifest.permission.CAMERA, MY_CAMERA_REQUEST_CODE, "camera_asked");
                else if (!notificationAllowed()) requestPermissionFromUser(Manifest.permission.POST_NOTIFICATIONS, PREVIEW_NOTIFICATION_REQUEST_CODE, "notifications_asked");
                else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) openPermissionSettings();
            })
            .setNeutralButton(gfText("Configurações", "Settings"), (d,w) -> openPermissionSettings())
            .setNegativeButton(gfText("Agora não", "Not now"), null).create();
        dialog.setOnDismissListener(d -> permDialogVisible = false);
        dialog.show();
    }
    private void advanceFirstPermissions() {
        if (this instanceof GuidedEnrollmentActivity || !canPresentPermissionUi() || permDialogVisible || permRequestInFlight) return;
        android.content.SharedPreferences p = permissionPrefs();
        if (!p.contains("started") && !p.getBoolean("done", false)) {
            p.edit().putBoolean("started", true).apply();
        }
        FirstPermissionPlan.Step step = FirstPermissionPlan.next(p.getBoolean("done", false), p.getBoolean("started", false), cameraAllowed(), notificationAllowed(), p.getBoolean("camera_asked", false), p.getBoolean("notifications_asked", false));
        if (step == FirstPermissionPlan.Step.DONE) { p.edit().putBoolean("done", true).apply(); return; }
        if (step == FirstPermissionPlan.Step.INTRO) {
            permDialogVisible = true;
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(gfText("Conheça o Golden Face", "Welcome to Golden Face"))
                .setMessage(gfText("Para reconhecer rostos no aparelho, o Golden Face usa a câmera. Para enviar o nome à Golden e mostrar o serviço, usa notificações. Vamos pedir somente o que ainda faltar; você pode continuar sem permitir.", "Golden Face uses the camera to recognize faces on your device. Notifications deliver the name to Golden and show the service. We will ask only for missing permissions; you can continue without allowing them."))
                .setPositiveButton(gfText("Continuar", "Continue"), (d,w) -> { p.edit().putBoolean("started", true).apply(); getWindow().getDecorView().post(this::advanceFirstPermissions); })
                .setNegativeButton(gfText("Agora não", "Not now"), (d,w) -> p.edit().putBoolean("done", true).apply())
                .setCancelable(false).create();
            dialog.setOnDismissListener(d -> permDialogVisible = false);
            dialog.show();
            return;
        }
        if (step == FirstPermissionPlan.Step.CAMERA) {
            requestPermissionFromUser(Manifest.permission.CAMERA, MY_CAMERA_REQUEST_CODE, "camera_asked"); return;
        }
        if (step == FirstPermissionPlan.Step.NOTIFICATIONS) {
            requestPermissionFromUser(Manifest.permission.POST_NOTIFICATIONS, PREVIEW_NOTIFICATION_REQUEST_CODE, "notifications_asked"); return;
        }
        p.edit().putBoolean("done", true).apply();
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        getWindow().getDecorView().post(this::afterPermissionResume);
    }
    private void afterPermissionResume() {
        if (!canPresentPermissionUi() || this instanceof GuidedEnrollmentActivity) return;
        if (getIntent().getBooleanExtra("golden_restore_permissions", false)) {
            getIntent().removeExtra("golden_restore_permissions");
            boolean overlayNeeded = getSharedPreferences("GoldenOptions", MODE_PRIVATE).getBoolean("overlay", false) && Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this);
            boolean permissionsNeeded = !cameraAllowed() || !notificationAllowed() || !NotificationManagerCompat.from(this).areNotificationsEnabled();
            if (overlayNeeded) {
                new AlertDialog.Builder(this).setTitle(gfText("Permissões após restaurar", "Permissions after restore"))
                  .setMessage(gfText("A sobreposição precisa de autorização neste aplicativo. Confira também câmera e notificações.", "Overlay needs permission in this app. Also check camera and notifications."))
                  .setPositiveButton(gfText("Sobreposição", "Overlay"), (d,w) -> { try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (RuntimeException ignored) {} })
                  .setNeutralButton(gfText("Câmera e notificações", "Camera and notifications"), (d,w) -> showPermissionHelp())
                  .setNegativeButton(gfText("Agora não", "Not now"), null).show();
            } else if (permissionsNeeded) showPermissionHelp();
            else advanceFirstPermissions();
        } else if (permRecoveryPending) showPermissionHelp(); else advanceFirstPermissions();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("golden_permission_request_in_flight", permRequestInFlight);
        state.putBoolean("golden_permission_recovery_pending", permRecoveryPending);
        super.onSaveInstanceState(state);
    }


    private static final int BACKUP_EXPORT = 4811, BACKUP_IMPORT = 4812;
    private final SharedPreferences.OnSharedPreferenceChangeListener backupFacesListener = (p,k) -> {
        if (k == null || "map".equals(k)) {
            try { registered = readFromSP(false); }
            catch (RuntimeException ex) { android.util.Log.e("GoldenFace", "Cannot reload faces", ex); }
        }
    };
    private byte[] pendingBackupExport;
    private void backupPicker(boolean export) {
        if (FaceCameraService.active) {
            Toast.makeText(this, gfText("Desligue a câmera em segundo plano antes do backup ou restauração.", "Stop the background camera before backup or restore."), Toast.LENGTH_LONG).show(); return;
        }
        if (!export) { openBackupPicker(false); return; }
        try {
            String json = GoldenBackup.snapshot(this); BackupCodec.validate(json);
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > GoldenBackup.MAX_BYTES) throw new IOException("Backup too large");
            pendingBackupExport = bytes;
        } catch (Exception ex) {
            pendingBackupExport = null;
            Toast.makeText(this, gfText("Não foi possível preparar o backup. Nenhum arquivo foi escolhido.", "Could not prepare the backup. No file was selected."), Toast.LENGTH_LONG).show(); return;
        }
        new AlertDialog.Builder(this).setTitle(gfText("Criar um novo arquivo", "Create a new file"))
            .setMessage(gfText("Escolha um nome novo. Se escolher um arquivo existente, o seletor pode substituí-lo antes mesmo de o backup ser gravado. Uma falha pode deixar o arquivo incompleto.", "Choose a new filename. Selecting an existing file may replace it before the backup is written. A failure may leave the file incomplete."))
            .setNegativeButton(gfText("Cancelar", "Cancel"), (d,w) -> pendingBackupExport = null)
            .setOnCancelListener(d -> pendingBackupExport = null)
            .setPositiveButton(gfText("Continuar", "Continue"), (d,w) -> openBackupPicker(true)).show();
    }
    private void openBackupPicker(boolean export) {
        try {
            Intent i = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
            if (export) i.putExtra(Intent.EXTRA_TITLE, "Golden-Face-backup-" + java.util.UUID.randomUUID().toString() + ".json");
            startActivityForResult(i, export ? BACKUP_EXPORT : BACKUP_IMPORT);
        } catch (RuntimeException ex) { pendingBackupExport = null; Toast.makeText(this, gfText("Não foi possível abrir o seletor de arquivos.", "Could not open the file picker."), Toast.LENGTH_LONG).show(); }
    }
    private void backupResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) { pendingBackupExport = null; return; }
        if (FaceCameraService.active) { pendingBackupExport = null; Toast.makeText(this, gfText("Desligue a câmera em segundo plano e tente novamente. O arquivo escolhido pode estar incompleto.", "Stop the background camera and try again. The selected file may be incomplete."), Toast.LENGTH_LONG).show(); return; }
        Uri uri = data.getData();
        if (requestCode == BACKUP_EXPORT) {
            byte[] bytes = pendingBackupExport; pendingBackupExport = null;
            try {
                if (bytes == null) throw new IOException("Prepared backup lost");
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("No output stream"); out.write(bytes); out.flush();
                }
                Toast.makeText(this, gfText("Backup exportado.", "Backup exported."), Toast.LENGTH_LONG).show();
            } catch (Exception ex) { Toast.makeText(this, gfText("Falha ao exportar. O arquivo selecionado pode estar incompleto; tente novamente.", "Export failed. The selected file may be incomplete; try again."), Toast.LENGTH_LONG).show(); }
            return;
        }
        final com.google.gson.JsonObject validated;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { validated = BackupCodec.validate(GoldenBackup.read(in)); }
        catch (Exception ex) { Toast.makeText(this, gfText("Backup inválido ou não pôde ser lido. Nada foi alterado.", "Invalid backup or unreadable file. Nothing changed."), Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(this).setTitle(gfText("Restaurar backup?", "Restore backup?"))
            .setMessage(gfText("Isso substitui todos os rostos e as configurações deste aplicativo experimental. Exporte um backup atual antes se desejar guardá-los.", "This replaces all faces and settings of this experimental app. Export a current backup first if you want to keep them."))
            .setNegativeButton(gfText("Cancelar", "Cancel"), null)
            .setPositiveButton(gfText("Substituir", "Replace"), (d,w) -> {
                if (FaceCameraService.active) { Toast.makeText(this, gfText("Desligue a câmera em segundo plano e tente novamente.", "Stop the background camera and try again."), Toast.LENGTH_LONG).show(); return; }
                try {
                    GoldenBackup.replace(this, validated);
                } catch (GoldenBackup.RecoveryRequired ex) {
                    // Do not retry in this process: a failed SharedPreferences commit can
                    // leave its in-memory state different from disk. Startup retries fresh.
                    Toast.makeText(this, gfText("Recuperação não concluída. O aplicativo vai fechar; reabra para tentar recuperar os dados.", "Recovery incomplete. The app will close; reopen it to retry recovery."), Toast.LENGTH_LONG).show();
                    finishAffinity();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 1000);
                    return;
                } catch (Exception ex) {
                    try { registered = readFromSP(false); }
                    catch (RuntimeException ignored) { finish(); }
                    Toast.makeText(this, gfText("Restauração não concluída. Confira os dados antes de tentar novamente.", "Restore incomplete. Check the data before trying again."), Toast.LENGTH_LONG).show();
                    return;
                }
                // The transaction has committed; later UI errors must not be reported as rollback.
                try {
                    registered = readFromSP(false);
                    FaceCameraService.pauseActivityPreview = true;
                    getIntent().removeExtra(BootResumeReceiver.EXTRA_RESUME);
                    Toast.makeText(this, gfText("Backup restaurado. Confira as permissões de câmera, notificações e sobreposição nas opções.", "Backup restored. Check camera, notification and overlay permissions in options."), Toast.LENGTH_LONG).show();
                    getIntent().putExtra("golden_restore_permissions", true);
                    recreate();
                } catch (Exception ex) {
                    Toast.makeText(this, gfText("Backup restaurado, mas a tela não atualizou. Feche e reabra o aplicativo.", "Backup restored, but the screen did not refresh. Close and reopen the app."), Toast.LENGTH_LONG).show();
                    finish();
                }
            }).show();
    }

    FaceDetector detector;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    PreviewView previewView;
    // One CameraX Preview use case; only its output surface changes during enrollment.
    private Preview activityPreview;
    private PreviewView enrollmentPreviewView;
    private TextView enrollmentCameraStatus;
    private boolean resumeBackgroundAfterEnrollment;
    private boolean guidedClosingForRestore;
    private final android.os.Handler enrollmentHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    ImageView face_preview;
    Interpreter tfLite;
    TextView reco_name,preview_info,textAbove_preview;
    Button recognize,camera_switch, actions;
    ImageButton add_face;
    CameraSelector cameraSelector;
    boolean developerMode=false;
    float distance= 1.0f;
    boolean start=true,flipX=false;
    Context context=MainActivity.this;
    int cam_face=CameraSelector.LENS_FACING_BACK; //Default Back Camera

    int[] intValues;
    int inputSize=112;  //Input size for model
    boolean isModelQuantized=false;
    float[][] embeedings;
    // Only the most recent live detection is eligible for an explicit manual capture.
    private Bitmap latestFace;
    private float[] latestEmbedding;
    private long latestFaceAt;
    // A new generation invalidates pending ML Kit results and manual captures.
    private volatile int cameraGeneration;

    private void invalidateActivityCamera() {
        FaceNotifications.release(this, FaceNotifications.PREVIEW);
        cameraGeneration++;
        activityPreview = null;
        latestFace = null;
        latestEmbedding = null;
        latestFaceAt = 0;
    }
    private boolean enrollmentOpen;
    float IMAGE_MEAN = 128.0f;
    float IMAGE_STD = 128.0f;
    int OUTPUT_SIZE=192; //Output size of model
    ProcessCameraProvider cameraProvider;
    private static final int MY_CAMERA_REQUEST_CODE = 100;
    private static final int NOTIFICATION_REQUEST_CODE = 101;
    private final android.os.Handler serviceStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshServiceStatus = new Runnable() {
        @Override public void run() {
            android.widget.TextView label = findViewById(R.id.goldenServiceStatus);
            if (label != null) label.setText(GoldenStatus.localize(FaceCameraService.cameraStatus, "en".equals(getSharedPreferences("GoldenOptions", MODE_PRIVATE).getString("language", "pt-BR"))));
            serviceStatusHandler.postDelayed(this, 500);
        }
    };
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    // Same persisted choice used by attachBaseContext / the options menu.
    // Exact UI strings only; never translate registered names or arbitrary values.
    private String gfText(String pt, String en) {
        return "en".equals(getSharedPreferences("GoldenOptions", MODE_PRIVATE).getString("language", "pt-BR")) ? en : pt;
    }
    @Override protected void attachBaseContext(android.content.Context base) {
        String code = base.getSharedPreferences("GoldenOptions", MODE_PRIVATE).getString("language", "pt-BR");
        java.util.Locale locale = "en".equals(code) ? java.util.Locale.ENGLISH : new java.util.Locale("pt", "BR");
        android.content.res.Configuration cfg = new android.content.res.Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    private void chooseLanguage() {
        String lang = getSharedPreferences("GoldenOptions", MODE_PRIVATE).getString("language", "pt-BR");
        new AlertDialog.Builder(this).setTitle(R.string.gf_language)
            .setSingleChoiceItems(new String[]{"Português (Brasil)", "English"}, "en".equals(lang) ? 1 : 0,
                (dialog, which) -> {
                    getSharedPreferences("GoldenOptions", MODE_PRIVATE).edit().putString("language", which == 1 ? "en" : "pt-BR").apply();
                    dialog.dismiss(); recreate();
                }).setNegativeButton(R.string.gf_cancel, null).show();
    }

    private int optionDp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    // Match the approved camera group without modifying control behavior.
    private android.widget.LinearLayout optionGroup(String title, int gap) {
        android.widget.LinearLayout group = new android.widget.LinearLayout(this);
        group.setOrientation(android.widget.LinearLayout.VERTICAL);
        group.setPadding(gap, gap, gap, gap);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xff176bb2);
        bg.setCornerRadius(optionDp(18));
        group.setBackground(bg);
        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setText(title);
        heading.setTextColor(android.graphics.Color.WHITE);
        heading.setTextSize(19);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(gap, gap, gap, optionDp(12));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        group.addView(heading);
        return group;
    }

    private void createOptionCards(AlertDialog.Builder builder, String[] names,
            DialogInterface.OnClickListener listener, AlertDialog[] dialogRef) {
        android.widget.LinearLayout outer = new android.widget.LinearLayout(this);
        outer.setOrientation(android.widget.LinearLayout.VERTICAL);
        int gap = optionDp(8);
        outer.setPadding(gap, gap, gap, gap);
        android.widget.Button help = new android.widget.Button(this);
        help.setText("?");
        help.setContentDescription(getString(R.string.gf_toggle_help));
        outer.addView(help, new android.widget.LinearLayout.LayoutParams(optionDp(56), optionDp(48)));
        android.widget.Button permissionHelp = new android.widget.Button(this);
        permissionHelp.setAllCaps(false);
        permissionHelp.setText(gfText("Câmera e notificações", "Camera and notifications"));
        outer.addView(permissionHelp);
        permissionHelp.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            showPermissionHelp();
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout cards = new android.widget.LinearLayout(this);
        cards.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(cards); outer.addView(scroll);
        String[] descriptions = getResources().getStringArray(R.array.gf_option_help);
        java.util.ArrayList<android.widget.TextView> details = new java.util.ArrayList<>();
        // A visual group only: all buttons and their original option indexes remain unchanged.
        android.widget.LinearLayout cameraGroup = new android.widget.LinearLayout(this);
        cameraGroup.setOrientation(android.widget.LinearLayout.VERTICAL);
        cameraGroup.setPadding(gap, gap, gap, gap);
        android.graphics.drawable.GradientDrawable cameraBg = new android.graphics.drawable.GradientDrawable();
        cameraBg.setColor(0xff176bb2);
        cameraBg.setCornerRadius(optionDp(18));
        cameraGroup.setBackground(cameraBg);
        android.widget.TextView cameraTitle = new android.widget.TextView(this);
        cameraTitle.setText(gfText("Câmera em segundo plano", "Background camera"));
        cameraTitle.setTextColor(android.graphics.Color.WHITE);
        cameraTitle.setTextSize(19);
        cameraTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        cameraTitle.setPadding(gap, gap, gap, optionDp(12));
        cameraGroup.addView(cameraTitle);
        android.widget.LinearLayout faceGroup = optionGroup(gfText("Rostos cadastrados", "Registered faces"), gap);
        android.widget.LinearLayout recognitionGroup = optionGroup(gfText("Reconhecimento", "Recognition"), gap);
        android.widget.LinearLayout continuityGroup = optionGroup(gfText("Funcionamento em segundo plano", "Background operation"), gap);
        for (int i = 0; i < names.length; i++) {
            if (i == 0 || i == 6 || i == 13) {
                android.widget.LinearLayout.LayoutParams groupLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                groupLp.setMargins(0, 0, 0, gap);
                cards.addView(i == 0 ? faceGroup : i == 6 ? recognitionGroup : continuityGroup, groupLp);
            }
            if (i == 9) {
                android.widget.LinearLayout.LayoutParams groupLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
                groupLp.setMargins(0, 0, 0, gap);
                cards.addView(cameraGroup, groupLp);
            }
            final int option = i;
            android.widget.LinearLayout card = new android.widget.LinearLayout(this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setPadding(gap, gap, gap, gap);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xfff3f3f3); bg.setCornerRadius(optionDp(14)); card.setBackground(bg);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, gap);
            // Keep the existing service toggle, lens selection and screen-off switch together.
            if (i == 9 || i == 11 || i == 14) cameraGroup.addView(card, lp);
            else if (i <= 5 || i == 12) faceGroup.addView(card, lp);
            else if (i == 6 || i == 7) recognitionGroup.addView(card, lp);
            else if (i == 13 || i == 16) continuityGroup.addView(card, lp);
            else cards.addView(card, lp);
            if (i == 14) {
                android.widget.Switch control = new android.widget.Switch(this);
                control.setText(names[i]);
                control.setTextColor(android.graphics.Color.WHITE);
                android.graphics.drawable.GradientDrawable switchBg = new android.graphics.drawable.GradientDrawable();
                switchBg.setColor(0xff424242);
                switchBg.setCornerRadius(optionDp(4));
                control.setBackground(switchBg);
                control.setPadding(gap, gap, gap, gap);
                control.setChecked(getSharedPreferences(FaceCameraService.SCREEN_PREFS, MODE_PRIVATE).getBoolean(FaceCameraService.SCREEN_OFF_KEY, false));
                control.setOnCheckedChangeListener((button, checked) -> getSharedPreferences(FaceCameraService.SCREEN_PREFS, MODE_PRIVATE).edit().putBoolean(FaceCameraService.SCREEN_OFF_KEY, checked).apply());
                card.addView(control);
            } else {
                android.widget.Button button = new android.widget.Button(this);
                button.setAllCaps(false); button.setText(names[i]); card.addView(button);
                button.setOnClickListener(v -> { if (dialogRef[0] != null) { dialogRef[0].dismiss(); listener.onClick(dialogRef[0], option); } });
            }
            android.widget.TextView detail = new android.widget.TextView(this);
            detail.setText(descriptions[i]); detail.setTextColor(0xff666666); detail.setTextSize(13);
            detail.setPadding(gap, gap, gap, 0); detail.setVisibility(View.GONE); card.addView(detail); details.add(detail);
        }
        android.widget.LinearLayout backupGroup = optionGroup(gfText("Backup e restauração", "Backup and restore"), gap);
        android.widget.LinearLayout.LayoutParams backupLp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        backupLp.setMargins(0, 0, 0, gap); cards.addView(backupGroup, backupLp);
        android.widget.Button exportButton = new android.widget.Button(this); exportButton.setAllCaps(false);
        exportButton.setText(gfText("Exportar backup", "Export backup")); backupGroup.addView(exportButton);
        android.widget.Button importButton = new android.widget.Button(this); importButton.setAllCaps(false);
        importButton.setText(gfText("Restaurar backup", "Restore backup")); backupGroup.addView(importButton);
        exportButton.setOnClickListener(v -> { if (dialogRef[0] != null) dialogRef[0].dismiss(); backupPicker(true); });
        importButton.setOnClickListener(v -> { if (dialogRef[0] != null) dialogRef[0].dismiss(); backupPicker(false); });
        help.setOnClickListener(v -> { boolean show = !details.isEmpty() && details.get(0).getVisibility() != View.VISIBLE;
            for (android.widget.TextView detail : details) detail.setVisibility(show ? View.VISIBLE : View.GONE); });
        builder.setView(outer);
    }

    @Override protected void onDestroy() {
        getSharedPreferences("HashMap", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(backupFacesListener);
        super.onDestroy();
    }

    private void requestBatteryExemption() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            Toast.makeText(this, gfText("O Android desta versão não possui essa otimização.", "This Android version does not have this optimization."), Toast.LENGTH_LONG).show();
            return;
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) {
            Toast.makeText(this, gfText("Não foi possível verificar a otimização de bateria.", "Could not check battery optimization."), Toast.LENGTH_LONG).show();
            return;
        }
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, gfText("A otimização de bateria já está desativada para o Golden Face.", "Battery optimization is already disabled for Golden Face."), Toast.LENGTH_LONG).show();
            return;
        }
        Intent request = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        request.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, gfText("A tela de otimização de bateria não está disponível neste aparelho.", "The battery optimization screen is unavailable on this device."), Toast.LENGTH_LONG).show();
        }
    }

    private void offerBootOption() {
        boolean enabled = getSharedPreferences(BootResumeReceiver.PREF, MODE_PRIVATE).getBoolean("resume", false);
        new AlertDialog.Builder(this).setTitle(gfText("Retomar após reinício", "Resume after reboot"))
            .setMessage(gfText("Android até 10: tentará iniciar a câmera automaticamente; se falhar, avisa por notificação. Android 11 ou mais recente: não abre a câmera no boot; envia uma notificação para tocar e retomar com o app aberto. Notificações precisam estar permitidas. Após parada forçada, o Android pode não entregar o boot.", "Android 10 and earlier: the app will try to start the camera automatically and notify you if it fails. Android 11 and later: the camera will not start at boot; tap the notification to resume with the app open. Notifications must be allowed. After a force stop, Android may not deliver the boot event."))
            .setPositiveButton(enabled ? gfText("Desativar", "Disable") : gfText("Ativar", "Enable"), (d,w) -> {
                getSharedPreferences(BootResumeReceiver.PREF, MODE_PRIVATE).edit().putBoolean("resume", !enabled).apply();
                if (enabled) ((android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(BootResumeReceiver.NOTIFICATION_ID);
                else if (!NotificationManagerCompat.from(this).areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
                    Toast.makeText(this, gfText("Ative as notificações para receber o aviso de retomada.", "Enable notifications to receive the resume notification."), Toast.LENGTH_LONG).show();
            }).setNegativeButton(gfText("Cancelar", "Cancel"), null).show();
    }

    String modelFile="mobile_face_net.tflite"; //model name

    private HashMap<String, SimilarityClassifier.Recognition> registered = new HashMap<>(); //saved Faces
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        goldenImmersiveUi = new ImmersiveUi(this);
        if (savedInstanceState != null) {
            permRequestInFlight = savedInstanceState.getBoolean("golden_permission_request_in_flight", false);
            permRecoveryPending = savedInstanceState.getBoolean("golden_permission_recovery_pending", false);
        }
        golden = new GoldenIntegration(this);
        registered=readFromSP(); //Load saved faces from memory when app starts
        getSharedPreferences("HashMap", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(backupFacesListener);
        setContentView(R.layout.activity_main);
        face_preview =findViewById(R.id.imageView);
        reco_name =findViewById(R.id.textView);
        preview_info =findViewById(R.id.textView2);
        textAbove_preview =findViewById(R.id.textAbovePreview);
        add_face=findViewById(R.id.imageButton);
        add_face.setVisibility(View.VISIBLE);

        SharedPreferences sharedPref = getSharedPreferences("Distance",Context.MODE_PRIVATE);
        distance = sharedPref.getFloat("distance",1.00f);

        face_preview.setVisibility(View.INVISIBLE);
        recognize=findViewById(R.id.button3);
        camera_switch=findViewById(R.id.button5);
        actions=findViewById(R.id.button2);
        actions.setText(gfText("AÇÕES", "ACTIONS"));
        recognize.setText(gfText("Adicionar rosto", "Add face"));
        reco_name.setHint(gfText("Adicionar rosto", "Add face"));
        ((TextView)findViewById(R.id.goldenCredits)).setText(gfText(
            "Projeto original:\nhttps://github.com/atharvakale31/Real-Time_Face_Recognition_Android\n\nModificações: Toqi IA e Alex Pereira Franco",
            "Original project:\nhttps://github.com/atharvakale31/Real-Time_Face_Recognition_Android\n\nModifications: Toqi AI and Alex Pereira Franco"));
        textAbove_preview.setText(gfText("Rosto reconhecido:", "Recognized face:"));
//        preview_info.setText("        Recognized Face:");
        //On-screen Action Button
        actions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(R.string.gf_options_title);

                // add a checkbox list
                String[] names = getResources().getStringArray(R.array.gf_options);
                names[9] = getString(FaceCameraService.active ? R.string.gf_camera_off : R.string.gf_camera_on);
                names[11] = getString(R.string.gf_camera_choice, backgroundCameraName());
                final AlertDialog[] optionDialog = new AlertDialog[1];

                createOptionCards(builder, names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        switch (which)
                        {
                            case 0:
                                displaynameListview();
                                break;
                            case 1:
                                updatenameListview();
                                break;
                            case 2:
                                insertToSP(registered,0); //mode: 0:save all, 1:clear all, 2:update all
                                break;
                            case 3:
                                registered.putAll(readFromSP());
                                break;
                            case 4:
                                clearnameList();
                                break;
                            case 5:
                                addFace();
                                break;
                            case 6:
                                testHyperparameter();
                                break;
                            case 7:
                                developerMode();
                                break;
                            case 8:
                                golden.configure();
                                break;
                            case 9:
                                toggleBackgroundCamera();
                                break;
                            case 10:
                                if (FaceCameraService.active) {
                                    Toast.makeText(context, gfText("Desligue o modo em segundo plano primeiro.", "Turn off background mode first."), Toast.LENGTH_LONG).show();
                                } else {
                                    FaceCameraService.pauseActivityPreview = false;
                                    cameraBind();
                                }
                                break;
                            case 11:
                                chooseBackgroundCamera();
                                break;
                            case 12:
                                deleteSelectedRecognitions();
                                break;
                            case 13:
                                offerBootOption();
                                break;
                            case 15:
                                chooseLanguage();
                                break;
                            case 16:
                                requestBatteryExemption();
                                break;
                        }

                    }
                }, optionDialog);


                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                });
                builder.setNegativeButton(R.string.gf_cancel, null);

                // create and show the alert dialog
                AlertDialog dialog = builder.create();
                optionDialog[0] = dialog;
                dialog.show();
            }
        });

        //On-screen switch to toggle between Cameras.
        camera_switch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (FaceCameraService.active) {
                    Toast.makeText(context, gfText("Desligue a câmera em segundo plano para trocar a câmera.", "Turn off the background camera before switching cameras."), Toast.LENGTH_LONG).show();
                    return;
                }
                if (cam_face==CameraSelector.LENS_FACING_BACK) {
                    cam_face = CameraSelector.LENS_FACING_FRONT;
                    flipX=true;
                }
                else {
                    cam_face = CameraSelector.LENS_FACING_BACK;
                    flipX=false;
                }
                invalidateActivityCamera();
                if (cameraProvider != null) cameraProvider.unbindAll();
                activityCameraBound = false;
                cameraBind();
            }
        });

        add_face.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                addFace();
            }
        }));


        // Both the labelled button and the legacy + lead to the same guided flow.
        recognize.setOnClickListener(v -> addFace());

        //Load model
        try {
            tfLite=new Interpreter(loadModelFile(MainActivity.this,modelFile));
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Initialize Face Detector
        FaceDetectorOptions highAccuracyOpts =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .build();
        detector = FaceDetection.getClient(highAccuracyOpts);
        if (this instanceof GuidedEnrollmentActivity) {
            getWindow().getDecorView().post(() -> { if (!isFinishing() && !enrollmentOpen) addFace(); });
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        previewResumed = true;
        previewNoticeHandler.removeCallbacks(previewNoticeExpiry);
        previewNoticeHandler.post(previewNoticeExpiry);
        if (golden != null) golden.resume();
        serviceStatusHandler.removeCallbacks(refreshServiceStatus);
        serviceStatusHandler.post(refreshServiceStatus);
        Intent launch = getIntent();
        if (launch != null && launch.getBooleanExtra(BootResumeReceiver.EXTRA_RESUME, false)) {
            launch.removeExtra(BootResumeReceiver.EXTRA_RESUME);
            if (!FaceCameraService.active) toggleBackgroundCamera();
        }
        if (!enrollmentOpen && !guidedClosingForRestore && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !FaceCameraService.active && !FaceCameraService.pauseActivityPreview && !activityCameraBound) cameraBind();
    }

    @Override
    protected void onPause() {
        previewResumed = false;
        previewNoticeHandler.removeCallbacks(previewNoticeExpiry);
        FaceNotifications.release(this, FaceNotifications.PREVIEW);
        serviceStatusHandler.removeCallbacks(refreshServiceStatus);
        if (golden != null) golden.pause();
        super.onPause();
    }

    private int backgroundCamera() {
        int lens = getSharedPreferences("GoldenBackgroundCamera", MODE_PRIVATE).getInt("lens", CameraSelector.LENS_FACING_BACK);
        return BackgroundCameraSelection.normalize(lens);
    }
    private String backgroundCameraName() {
        return getString(backgroundCamera() == CameraSelector.LENS_FACING_FRONT ? R.string.gf_front : R.string.gf_back);
    }
    private void chooseBackgroundCamera() {
        if (!BackgroundCameraSelection.canChange(FaceCameraService.active)) {
            Toast.makeText(this, gfText("Desligue o modo em segundo plano para trocar a câmera.", "Turn off background mode before switching cameras."), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(gfText("Câmera em segundo plano", "Background camera"))
            .setSingleChoiceItems(new String[]{gfText("Frontal", "Front"), gfText("Traseira", "Back")}, backgroundCamera() == CameraSelector.LENS_FACING_FRONT ? 0 : 1,
                (dialog, which) -> {
                    int selected = BackgroundCameraSelection.fromChoice(which);
                    getSharedPreferences("GoldenBackgroundCamera", MODE_PRIVATE).edit().putInt("lens", selected).apply();
                    dialog.dismiss();
                    Toast.makeText(this, gfText("Câmera ", "Camera ") + backgroundCameraName() + gfText(" selecionada para segundo plano.", " selected for background use."), Toast.LENGTH_LONG).show();
                }).setNegativeButton(gfText("Cancelar", "Cancel"), null).show();
    }
    private void toggleBackgroundCamera() {
        if (FaceCameraService.active) {
            stopService(new Intent(this, FaceCameraService.class));
            Toast.makeText(this, gfText("Desligando câmera em segundo plano.", "Turning off the background camera."), Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showPermissionHelp();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionFromUser(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_REQUEST_CODE, "notifications_asked");
            return;
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, gfText("Ative as notificações do Golden Face nas configurações e tente novamente.", "Enable Golden Face notifications in settings and try again."), Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm = (android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel faceChannel = nm.getNotificationChannel("golden_face_names");
            if (faceChannel != null && faceChannel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
                Toast.makeText(this, gfText("Ative o canal Golden Face rostos nas configurações e tente novamente.", "Enable the Golden Face faces notification channel in settings and try again."), Toast.LENGTH_LONG).show();
                return;
            }
        }
        golden.clear();
        FaceNotifications.acquire(this, FaceNotifications.SERVICE); // handoff before invalidating preview
        invalidateActivityCamera();
        if (cameraProvider != null) cameraProvider.unbindAll();
        activityCameraBound = false;
        FaceCameraService.active = true;
        FaceCameraService.cameraStatus = "Serviço em segundo plano: iniciando câmera";
        FaceCameraService.pauseActivityPreview = true;
        try {
            Intent intent = new Intent(this, FaceCameraService.class).putExtra(FaceCameraService.LENS, backgroundCamera());
            ContextCompat.startForegroundService(this, intent);
            Toast.makeText(this, gfText("Iniciando câmera ", "Starting ") + backgroundCameraName() + gfText(" local. Salve os rostos antes de ativar.", " local camera. Save faces before enabling it."), Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            FaceCameraService.active = false;
            FaceNotifications.release(this, FaceNotifications.SERVICE);
            FaceCameraService.cameraStatus = "Falha ao iniciar serviço: " + e.getClass().getSimpleName();
            FaceCameraService.pauseActivityPreview = false;
            Toast.makeText(this, gfText("Não foi possível iniciar a câmera: ", "Could not start the camera: ") + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            if (!guidedClosingForRestore) cameraBind();
        }
    }

    private void testHyperparameter()
    {

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(gfText("Selecione o parâmetro:", "Select parameter:"));

        // add a checkbox list
        String[] names= {gfText("Distância máxima do vizinho mais próximo", "Maximum nearest-neighbor distance")};

        builder.setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                switch (which)
                {
                    case 0:
//                        Toast.makeText(context, "Clicked", Toast.LENGTH_SHORT).show();
                        hyperparameters();
                        break;

                }

            }

            });
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.setNegativeButton(gfText("Cancelar", "Cancel"), null);

        // create and show the alert dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    private void developerMode()
    {
        if (developerMode) {
            developerMode = false;
            Toast.makeText(context, gfText("Modo desenvolvedor DESLIGADO", "Developer mode OFF"), Toast.LENGTH_SHORT).show();
        }
        else {
            developerMode = true;
            Toast.makeText(context, gfText("Modo desenvolvedor LIGADO", "Developer mode ON"), Toast.LENGTH_SHORT).show();
        }
    }
    private interface GalleryResult { void accept(Uri uri); }
    private GalleryResult galleryResult;
    private EditText enrollmentName;
    private int galleryGeneration;
    private final GalleryImportGate galleryGate = new GalleryImportGate();
    private static final int GALLERY_REQUEST = 803;
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_EXPORT || requestCode == BACKUP_IMPORT) { backupResult(requestCode, resultCode, data); return; }
        if (requestCode == GALLERY_REQUEST) {
            GalleryResult callback = galleryResult;
            galleryResult = null;
            if (callback != null && resultCode == Activity.RESULT_OK && data != null && data.getData() != null)
                callback.accept(data.getData());
            else if (callback == null && resultCode == Activity.RESULT_OK && data != null && data.getData() != null)
                Toast.makeText(this, gfText("O cadastro foi interrompido. Abra-o e escolha a foto novamente.", "Enrollment was interrupted. Open it and choose the photo again."), Toast.LENGTH_LONG).show();
        }
    }
    private Bitmap loadGalleryBitmap(Uri uri) throws IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Não foi possível abrir a foto");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Imagem inválida");
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        Bitmap b;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Não foi possível abrir a foto");
            b = BitmapFactory.decodeStream(in, null, opts);
        }
        if (b == null) throw new IOException("Imagem inválida");
        int orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) orientation = new androidx.exifinterface.media.ExifInterface(in).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation);
        } catch (IOException ignored) { /* Images without EXIF are upright. */ }
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setRotate(180); m.postScale(-1, 1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
            default: return b;
        }
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }
    private float[] embeddingForPhoto(Bitmap face) {
        if (tfLite == null) throw new IllegalStateException("Modelo indisponível");
        Bitmap scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true);
        int[] pixels = new int[inputSize * inputSize];
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);
        ByteBuffer data = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder());
        for (int px : pixels) {
            data.putFloat(((px >> 16 & 255) - IMAGE_MEAN) / IMAGE_STD);
            data.putFloat(((px >> 8 & 255) - IMAGE_MEAN) / IMAGE_STD);
            data.putFloat(((px & 255) - IMAGE_MEAN) / IMAGE_STD);
        }
        data.rewind();
        float[][] result = new float[1][OUTPUT_SIZE];
        tfLite.run(data, result);
        for (float value : result[0]) if (Float.isNaN(value) || Float.isInfinite(value))
            throw new IllegalStateException("Amostra inválida");
        return result[0];
    }
    private boolean hasEnrollmentName(EditText name) {
        if (!name.getText().toString().trim().isEmpty()) return true;
        name.setError(gfText("Digite um nome primeiro", "Enter a name first")); name.requestFocus(); return false;
    }
    private void addFace() { addFace(null); }
    private void addFace(String existingName) {
        if (enrollmentOpen) return;
        enrollmentOpen = true;
        FaceNotifications.release(this, FaceNotifications.PREVIEW);
        start = true;
        golden.clear();
        final String[] steps = {gfText("Frente (obrigatória)", "Front (required)"), gfText("Direita (opcional)", "Right (optional)"), gfText("Esquerda (opcional)", "Left (optional)"), gfText("Luz baixa (opcional)", "Low light (optional)"), gfText("Olhos fechados (opcional)", "Eyes closed (optional)")};
        final int[] step = {0};
        final float[][] frozen = {null};
        final ArrayList<float[]> captured = new ArrayList<>();
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        EditText name = new EditText(this);
        name.setHint(gfText("Nome (use o mesmo para acrescentar amostras)", "Name (use the same name to add samples)"));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        enrollmentName = name;
        if (existingName != null) { name.setText(existingName); name.setEnabled(false); }
        // Name checks are shared by every action that advances or captures.
        TextView instruction = new TextView(this);
        ImageView frozenPreview = new ImageView(this);
        frozenPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frozenPreview.setContentDescription(gfText("Amostra congelada", "Frozen sample"));
        panel.addView(name);
        panel.addView(instruction);
        // Do not reparent a running SurfaceView across windows. Keep one camera and
        // switch its Preview output to a TextureView-backed view in this dialog.
        PreviewView dialogPreview = new PreviewView(this);
        dialogPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        dialogPreview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        enrollmentPreviewView = dialogPreview;
        TextView liveLabel = new TextView(this);
        liveLabel.setText(gfText("Prévia ao vivo (aguardando câmera)", "Live preview (waiting for camera)"));
        enrollmentCameraStatus = liveLabel;
        panel.addView(liveLabel);
        Button cameraButton = new Button(this);
        cameraButton.setText(gfText("Iniciar câmera para cadastro", "Start camera for enrollment"));
        panel.addView(cameraButton);
        Button switchCamera = new Button(this);
        switchCamera.setText(gfText("Trocar câmera frontal / traseira", "Switch front / back camera"));
        panel.addView(switchCamera);
        switchCamera.setOnClickListener(v -> camera_switch.performClick());
        panel.addView(dialogPreview, new android.widget.LinearLayout.LayoutParams(-1, (int)(240 * getResources().getDisplayMetrics().density)));
        TextView frozenLabel = new TextView(this);
        frozenLabel.setText(gfText("Amostra congelada (após MANUAL)", "Frozen sample (after MANUAL)"));
        panel.addView(frozenLabel);
        panel.addView(frozenPreview, new android.widget.LinearLayout.LayoutParams(-1, (int)(120 * getResources().getDisplayMetrics().density)));
        Button manual = new Button(this); manual.setText(gfText("MANUAL: capturar / atualizar", "MANUAL: capture / update")); panel.addView(manual);
        Button gallery = new Button(this); gallery.setText(gfText("Adicionar foto da galeria", "Add photo from gallery")); panel.addView(gallery);
        Button next = new Button(this); next.setText(gfText("Próximo passo", "Next step")); panel.addView(next);
        Button skip = new Button(this); skip.setText(gfText("Pular passo opcional", "Skip optional step")); panel.addView(skip);
        Runnable refresh = () -> instruction.setText(gfText("Passo ", "Step ") + (step[0]+1) + "/" + steps.length + ": " + steps[step[0]] + gfText("\nPosicione o rosto e toque em MANUAL. A imagem capturada fica congelada.", "\nPosition the face and tap MANUAL. The captured image stays frozen."));
        refresh.run();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(gfText("Cadastro guiado", "Guided enrollment")).setView(scroll)
            .setPositiveButton(gfText("Salvar", "Save"), null).setNegativeButton(gfText("Cancelar", "Cancel"), (d,w) -> {}).create();
        androidx.lifecycle.Observer<PreviewView.StreamState> streamObserver = state -> {
            if (!FaceCameraService.active) liveLabel.setText(state == PreviewView.StreamState.STREAMING
                ? gfText("Prévia ao vivo", "Live preview") : gfText("Prévia ao vivo (aguardando câmera)", "Live preview (waiting for camera)"));
        };
        dialogPreview.getPreviewStreamState().observe(this, streamObserver);
        dialog.setOnDismissListener(d -> {
            enrollmentOpen = false; galleryGeneration++; galleryGate.finish(); galleryResult = null; enrollmentName = null;
            dialogPreview.getPreviewStreamState().removeObserver(streamObserver);
            enrollmentPreviewView = null;
            enrollmentCameraStatus = null;
            if (activityPreview != null && activityCameraBound)
                activityPreview.setSurfaceProvider(previewView.getSurfaceProvider());
            if (resumeBackgroundAfterEnrollment) {
                resumeBackgroundAfterEnrollment = false;
                // The exported Activity must stay visible while requesting foreground camera start.
                if (this instanceof GuidedEnrollmentActivity) {
                    guidedClosingForRestore = true;
                    enrollmentHandler.postDelayed(() -> restoreGuidedBackground(10), 400);
                } else {
                    enrollmentHandler.postDelayed(() -> restoreBackgroundAfterEnrollment(10), 400);
                }
            } else if (this instanceof GuidedEnrollmentActivity) finish();
        });
        cameraButton.setOnClickListener(v -> startEnrollmentCamera());
        manual.setOnClickListener(v -> {
            if (!hasEnrollmentName(name)) return;
            if (galleryGate.isBusy()) { Toast.makeText(this, gfText("Aguarde o processamento da foto.", "Wait while the photo is processed."), Toast.LENGTH_SHORT).show(); return; }
            galleryGeneration++; galleryResult = null;
            if (latestFace == null || latestEmbedding == null || android.os.SystemClock.elapsedRealtime() - latestFaceAt > 1000) {
                Toast.makeText(this, gfText("Nenhum rosto detectado agora. Ajuste a câmera ou a luz e tente novamente.", "No face detected. Adjust the camera or lighting and try again."), Toast.LENGTH_LONG).show();
                return;
            }
            frozen[0] = latestEmbedding.clone();
            frozenPreview.setImageBitmap(latestFace.copy(Bitmap.Config.ARGB_8888, false));
            Toast.makeText(this, gfText("Captura congelada. MANUAL atualiza se desejar.", "Capture frozen. Tap MANUAL to update it if desired."), Toast.LENGTH_SHORT).show();
        });
        next.setOnClickListener(v -> {
            if (!hasEnrollmentName(name)) return;
            if (galleryGate.isBusy()) { Toast.makeText(this, gfText("Aguarde o processamento da foto.", "Wait while the photo is processed."), Toast.LENGTH_SHORT).show(); return; }
            galleryGeneration++; galleryResult = null;
            if (step[0] == steps.length-1) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return; }
            if (frozen[0] == null) { Toast.makeText(this, gfText("Toque em MANUAL antes de avançar.", "Tap MANUAL before continuing."), Toast.LENGTH_SHORT).show(); return; }
            captured.add(frozen[0]); frozen[0] = null; frozenPreview.setImageDrawable(null); step[0]++; refresh.run();
        });
        skip.setOnClickListener(v -> {
            if (!hasEnrollmentName(name)) return;
            if (galleryGate.isBusy()) { Toast.makeText(this, gfText("Aguarde o processamento da foto.", "Wait while the photo is processed."), Toast.LENGTH_SHORT).show(); return; }
            galleryGeneration++; galleryResult = null;
            if (step[0] == 0) { Toast.makeText(this, gfText("A amostra de frente é obrigatória.", "The front sample is required."), Toast.LENGTH_SHORT).show(); return; }
            if (step[0] == steps.length-1) { frozen[0] = null; frozenPreview.setImageDrawable(null); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return; }
            frozen[0] = null; frozenPreview.setImageDrawable(null); step[0]++; refresh.run();
        });
        gallery.setOnClickListener(v -> {
            if (!hasEnrollmentName(name)) return;
            if (galleryGate.isBusy()) { Toast.makeText(this, gfText("Aguarde o processamento da foto.", "Wait while the photo is processed."), Toast.LENGTH_SHORT).show(); return; }
            final int generation = ++galleryGeneration;
            galleryResult = uri -> {
                if (!enrollmentOpen || galleryGeneration != generation) return;
                // A newly selected image replaces the current step, even on failure.
                frozen[0] = null; frozenPreview.setImageDrawable(null);
                if (!hasEnrollmentName(name)) return;
                galleryGate.start();
                final Bitmap image;
                try { image = loadGalleryBitmap(uri); }
                catch (IOException | RuntimeException | OutOfMemoryError e) {
                    galleryGate.finish();
                    Toast.makeText(this, gfText("Não foi possível abrir a foto.", "Could not open the photo."), Toast.LENGTH_LONG).show(); return;
                }
                try { detector.process(InputImage.fromBitmap(image, 0)).addOnSuccessListener(faces -> {
                    if (!enrollmentOpen || galleryGeneration != generation) return;
                    galleryGate.finish();
                    if (!hasEnrollmentName(name)) return;
                    if (faces.size() != 1) {
                        Toast.makeText(this, gfText("Escolha uma foto com exatamente um rosto visível.", "Choose a photo with exactly one visible face."), Toast.LENGTH_LONG).show(); return;
                    }
                    try {
                        Rect box = faces.get(0).getBoundingBox();
                        int l = Math.max(0, box.left), t = Math.max(0, box.top);
                        int r = Math.min(image.getWidth(), box.right), b = Math.min(image.getHeight(), box.bottom);
                        if (r <= l || b <= t) throw new IllegalArgumentException("Rosto inválido");
                        Bitmap face = Bitmap.createBitmap(image, l, t, r-l, b-t);
                        float[] embedding = embeddingForPhoto(face);
                        frozen[0] = embedding;
                        frozenPreview.setImageBitmap(face);
                        Toast.makeText(this, gfText("Foto pronta. Toque em Próximo passo ou Salvar.", "Photo ready. Tap Next step or Save."), Toast.LENGTH_LONG).show();
                    } catch (RuntimeException | OutOfMemoryError e) {
                        Toast.makeText(this, gfText("Não foi possível processar o rosto.", "Could not process the face."), Toast.LENGTH_LONG).show();
                    }
                }).addOnFailureListener(e -> {
                    if (enrollmentOpen && galleryGeneration == generation) {
                        galleryGate.finish();
                        Toast.makeText(this, gfText("Não foi possível detectar um rosto na foto.", "Could not detect a face in the photo."), Toast.LENGTH_LONG).show();
                    }
                }); } catch (RuntimeException | OutOfMemoryError e) {
                    galleryGate.finish();
                    Toast.makeText(this, gfText("Não foi possível detectar um rosto na foto.", "Could not detect a face in the photo."), Toast.LENGTH_LONG).show();
                }
            };
            try {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.setType("image/*");
                pick.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(pick, GALLERY_REQUEST);
            } catch (RuntimeException e) {
                galleryResult = null;
                Toast.makeText(this, gfText("Nenhuma galeria disponível.", "No gallery available."), Toast.LENGTH_LONG).show();
            }
        });
        dialog.setOnShowListener(d -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            if (FaceCameraService.active) {
                liveLabel.setText(gfText("Câmera em segundo plano ativa. Toque em Iniciar câmera para pausar durante o cadastro.", "Background camera active. Tap Start camera to pause it during enrollment."));
            } else {
                liveLabel.setText(gfText("Escolha uma foto ou inicie a câmera para cadastro.", "Choose a photo or start the camera for enrollment."));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (galleryGate.isBusy()) { Toast.makeText(this, gfText("Aguarde o processamento da foto.", "Wait while the photo is processed."), Toast.LENGTH_SHORT).show(); return; }
            String key = name.getText().toString().trim();
            if (key.isEmpty()) { name.setError(gfText("Digite um nome", "Enter a name")); return; }
            ArrayList<float[]> all = new ArrayList<>(captured);
            if (frozen[0] != null) all.add(frozen[0]);
            if (all.isEmpty()) { Toast.makeText(this, gfText("Capture a amostra de frente primeiro.", "Capture the front sample first."), Toast.LENGTH_LONG).show(); return; }
            SimilarityClassifier.Recognition previous = registered.get(key);
            Object old = previous == null ? null : previous.getExtra();
            if (previous != null && FaceSamples.decode(old).length == 0) {
                Toast.makeText(this, gfText("Cadastro anterior inválido; nada foi substituído.", "The previous enrollment is invalid; nothing was replaced."), Toast.LENGTH_LONG).show(); return;
            }
            SimilarityClassifier.Recognition result = new SimilarityClassifier.Recognition("0", "", -1f);
            result.setExtra(FaceSamples.append(old, all.toArray(new float[all.size()][])));
            HashMap<String, SimilarityClassifier.Recognition> updated = new HashMap<>(registered);
            updated.put(key, result);
            String json = new Gson().toJson(updated);
            if (!getSharedPreferences("HashMap", MODE_PRIVATE).edit().putString("map", json).commit()) {
                Toast.makeText(this, gfText("Não foi possível salvar. Tente novamente.", "Could not save. Try again."), Toast.LENGTH_LONG).show(); return;
            }
            registered = updated;
            Toast.makeText(this, gfText("Salvo: ", "Saved: ") + key + " (" + all.size() + gfText(" nova(s) amostra(s)).", " new sample(s))."), Toast.LENGTH_LONG).show();
            dialog.dismiss();
            });
        });
        dialog.show();
    }
    private void restoreGuidedBackground(int retries) {
        if (!guidedClosingForRestore || isFinishing() || isDestroyed()) return;
        // Do not start a camera foreground service from a background/destroyed Activity.
        if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            guidedClosingForRestore = false;
            Toast.makeText(this, gfText("Retomada interrompida. Abra o Golden Face para iniciar a câmera.", "Resume was interrupted. Open Golden Face to start the camera."), Toast.LENGTH_LONG).show();
            finish(); return;
        }
        if (FaceCameraService.active) {
            if (retries > 0) enrollmentHandler.postDelayed(() -> restoreGuidedBackground(retries - 1), 300);
            else {
                guidedClosingForRestore = false;
                Toast.makeText(this, gfText("A câmera anterior não liberou a tempo; confira o modo Golden.", "The previous camera was not released in time; check Golden mode."), Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            guidedClosingForRestore = false;
            Toast.makeText(this, gfText("Autorize câmera e notificações no Golden Face para retomar.", "Allow camera and notifications in Golden Face to resume."), Toast.LENGTH_LONG).show();
            finish(); return;
        }
        // toggle has its own channel/notification checks and catches start failures.
        // Never call it while active: that would stop the service rather than start it.
        toggleBackgroundCamera();
        guidedClosingForRestore = false;
        finish();
    }
    private void restoreBackgroundAfterEnrollment(int retries) {
        if (enrollmentOpen) {
            resumeBackgroundAfterEnrollment = true;
            return;
        }
        if (FaceCameraService.active) {
            if (retries > 0) enrollmentHandler.postDelayed(() -> restoreBackgroundAfterEnrollment(retries - 1), 300);
            else Toast.makeText(this, gfText("Não foi possível reiniciar a câmera em segundo plano; confira o modo Golden.", "Could not restart the background camera; check Golden mode."), Toast.LENGTH_LONG).show();
            return;
        }
        toggleBackgroundCamera();
    }
    private void startEnrollmentCamera() {
        if (!enrollmentOpen || enrollmentName == null || !hasEnrollmentName(enrollmentName)) return;
        if (FaceCameraService.active) {
            resumeBackgroundAfterEnrollment = true;
            stopService(new Intent(this, FaceCameraService.class));
            // Give the foreground service time to release its CameraX use case.
            enrollmentHandler.postDelayed(() -> {
                if (!enrollmentOpen) return;
                if (FaceCameraService.active) {
                    if (enrollmentCameraStatus != null) enrollmentCameraStatus.setText(gfText("Aguardando liberar a câmera. Toque em Iniciar câmera novamente.", "Waiting for the camera to be released. Tap Start camera again."));
                    return;
                }
                startEnrollmentCamera();
            }, 400);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionFromUser(Manifest.permission.CAMERA, MY_CAMERA_REQUEST_CODE, "camera_asked");
            return;
        }
        FaceCameraService.pauseActivityPreview = false;
        if (activityCameraBound && activityPreview != null && enrollmentPreviewView != null) {
            activityPreview.setSurfaceProvider(enrollmentPreviewView.getSurfaceProvider());
        } else if (!activityCameraBound) {
            cameraBind();
        }
    }
    private  void clearnameList()
    {
        AlertDialog.Builder builder =new AlertDialog.Builder(context);
        builder.setTitle(gfText("Deseja apagar todos os rostos cadastrados?", "Delete all enrolled faces?"));
        builder.setPositiveButton(gfText("Apagar todos", "Delete all"), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                registered.clear();
                insertToSP(registered,1);
                Toast.makeText(context, gfText("Rostos apagados", "Faces cleared"), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(gfText("Cancelar", "Cancel"),null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    private void updatenameListview() {
        if (registered.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(gfText("Nenhum rosto cadastrado", "No faces enrolled")).setPositiveButton("OK", null).show();
            return;
        }
        String[] names = registered.keySet().toArray(new String[0]);
        java.util.Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        new AlertDialog.Builder(this).setTitle(gfText("Escolha uma pessoa para acrescentar amostras", "Choose a person to add samples"))
            .setItems(names, (dialog, which) -> addFace(names[which]))
            .setNegativeButton(gfText("Cancelar", "Cancel"), null).show();
    }
    private void deleteSelectedRecognitions()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        if(registered.isEmpty()) {
            builder.setTitle(gfText("Nenhum rosto cadastrado", "No faces enrolled"));
            builder.setPositiveButton("OK",null);
        }
        else{
            builder.setTitle(gfText("Selecione os rostos para excluir:", "Select faces to delete:"));

        // add a checkbox list
        String[] names= new String[registered.size()];
        boolean[] checkedItems = new boolean[registered.size()];
         int i=0;
                for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet())
                {
                    //System.out.println("NAME"+entry.getKey());
                    names[i]=entry.getKey();
                    checkedItems[i]=false;
                    i=i+1;

                }

                builder.setMultiChoiceItems(names, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        // user checked or unchecked a box
                        //Toast.makeText(MainActivity.this, names[which], Toast.LENGTH_SHORT).show();
                       checkedItems[which]=isChecked;

                    }
                });


        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                       // System.out.println("status:"+ Arrays.toString(checkedItems));
                        for(int i=0;i<checkedItems.length;i++)
                        {
                            //System.out.println("status:"+checkedItems[i]);
                            if(checkedItems[i])
                            {
//                                Toast.makeText(MainActivity.this, names[i], Toast.LENGTH_SHORT).show();
                                registered.remove(names[i]);
                            }

                        }
                insertToSP(registered,2); //mode: 0:save all, 1:clear all, 2:update all
                Toast.makeText(context, gfText("Rostos atualizados", "Faces updated"), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(gfText("Cancelar", "Cancel"), null);

        // create and show the alert dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    }
    private void hyperparameters()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(gfText("Distância euclidiana", "Euclidean distance"));
        builder.setMessage(gfText("0,00 -> Correspondência perfeita\n1,00 -> Padrão\nAtive o modo desenvolvedor para encontrar o valor ideal\n\nValor atual:", "0.00 -> Perfect match\n1.00 -> Default\nTurn on developer mode to find the optimal value\n\nCurrent value:"));
        // Set up the input
        final EditText input = new EditText(context);

        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        builder.setView(input);
        SharedPreferences sharedPref = getSharedPreferences("Distance",Context.MODE_PRIVATE);
        distance = sharedPref.getFloat("distance",1.00f);
        input.setText(String.valueOf(distance));
        // Set up the buttons
        builder.setPositiveButton(gfText("Atualizar", "Update"), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                //Toast.makeText(context, input.getText().toString(), Toast.LENGTH_SHORT).show();

               distance= Float.parseFloat(input.getText().toString());


                SharedPreferences sharedPref = getSharedPreferences("Distance",Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putFloat("distance", distance);
                editor.apply();

            }
        });
        builder.setNegativeButton(gfText("Cancelar", "Cancel"), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

                dialog.cancel();
            }
        });

        builder.show();
    }


    private void displaynameListview()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
       // System.out.println("Registered"+registered);
        if(registered.isEmpty())
            builder.setTitle(gfText("Nenhum rosto cadastrado", "No faces enrolled"));
        else
            builder.setTitle(gfText("Rostos cadastrados:", "Enrolled faces:"));

        // add a checkbox list
        String[] names= new String[registered.size()];
        boolean[] checkedItems = new boolean[registered.size()];
        int i=0;
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet())
        {
            //System.out.println("NAME"+entry.getKey());
            names[i]=entry.getKey();
            checkedItems[i]=false;
            i=i+1;

        }
        builder.setItems(names,null);



        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });

            // create and show the alert dialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PREVIEW_NOTIFICATION_REQUEST_CODE || requestCode == MY_CAMERA_REQUEST_CODE || requestCode == NOTIFICATION_REQUEST_CODE) {
            permRequestInFlight = false;
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (requestCode == MY_CAMERA_REQUEST_CODE && granted) {
                if (enrollmentOpen) startEnrollmentCamera(); else cameraBind();
            } else if (requestCode == NOTIFICATION_REQUEST_CODE && granted) {
                toggleBackgroundCamera();
            }
            if (!permissionPrefs().getBoolean("done", false) && permissionPrefs().getBoolean("started", false)) {
                getWindow().getDecorView().post(this::advanceFirstPermissions);
            } else if (!granted) {
                permRecoveryPending = true;
                getWindow().getDecorView().post(() -> { if (canPresentPermissionUi()) showPermissionHelp(); });
            }
        }
    }

    private MappedByteBuffer loadModelFile(Activity activity, String MODEL_FILE) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    //Bind camera and preview view
    private void cameraBind()
    {
        if (FaceCameraService.active || FaceCameraService.pauseActivityPreview || activityCameraBound ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        activityCameraBound = true;
        final int generation = ++cameraGeneration;
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        final ListenableFuture<ProcessCameraProvider> providerFuture = cameraProviderFuture;

        previewView=findViewById(R.id.previewView);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();

                if (generation != cameraGeneration || FaceCameraService.active || !activityCameraBound) return;
                cameraProvider = provider;
                bindPreview(provider, generation);
            } catch (ExecutionException | InterruptedException | RuntimeException e) {
                if (generation == cameraGeneration) {
                    activityCameraBound = false;
                    activityPreview = null;
                    Toast.makeText(this, gfText("Não foi possível abrir a câmera. Verifique a permissão e tente reativar a prévia.", "Could not open the camera. Check permission and try reactivating the preview."), Toast.LENGTH_LONG).show();
                }
                android.util.Log.e("GoldenFace", "Camera bind failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
    void bindPreview(@NonNull ProcessCameraProvider cameraProvider, final int generation) {
        final boolean mirrorFrame = flipX;
        Preview preview = new Preview.Builder()
                .build();

        cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(cam_face)
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) //Latest frame is shown
                        .build();

        Executor executor = Executors.newSingleThreadExecutor();
        imageAnalysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                if (generation != cameraGeneration) { imageProxy.close(); return; }
                try {
                    Thread.sleep(0);  //Camera preview refreshed every 10 millisec(adjust as required)
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                InputImage image = null;


                @SuppressLint("UnsafeExperimentalUsageError")
                // Camera Feed-->Analyzer-->ImageProxy-->mediaImage-->InputImage(needed for ML kit face detection)

                Image mediaImage = imageProxy.getImage();

                if (mediaImage == null) { imageProxy.close(); return; }
                try {
                    image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
//                    System.out.println("Rotation "+imageProxy.getImageInfo().getRotationDegrees());

//                System.out.println("ANALYSIS");

                //Process acquired image to detect faces
                Task<List<Face>> result =
                        detector.process(image)
                                .addOnSuccessListener(
                                        new OnSuccessListener<List<Face>>() {
                                            @Override
                                            public void onSuccess(List<Face> faces) {
                                                if (generation != cameraGeneration || !activityCameraBound) return;
                                                try {
                                                if(faces.size()!=0) {

                                                    Face face = faces.get(0); //Get first face from detected faces
//                                                    System.out.println(face);

                                                    //mediaImage to Bitmap
                                                    Bitmap frame_bmp = toBitmap(mediaImage);

                                                    int rot = imageProxy.getImageInfo().getRotationDegrees();

                                                    //Adjust orientation of Face
                                                    Bitmap frame_bmp1 = rotateBitmap(frame_bmp, rot, false, false);



                                                    //Get bounding box of face
                                                    RectF boundingBox = new RectF(face.getBoundingBox());

                                                    //Crop out bounding box from whole Bitmap(image)
                                                    Bitmap cropped_face = getCropBitmapByCPU(frame_bmp1, boundingBox);

                                                    if(mirrorFrame)
                                                        cropped_face = rotateBitmap(cropped_face, 0, true, false);
                                                    // Preserve the unsquashed face for the frozen UI; the model still
                                                    // receives its original 112x112 input. getResizedBitmap recycles its input.
                                                    Bitmap proportionalFace = cropped_face.copy(Bitmap.Config.ARGB_8888, false);
                                                    Bitmap scaled = getResizedBitmap(cropped_face, 112, 112);

                                                    if(start) {
                                                        recognizeImage(scaled, true); // Camera frame.
                                                        latestFace = proportionalFace;
                                                    }
//                                                    System.out.println(boundingBox);

                                                }
                                                else
                                                {
                                                    latestFace = null;
                                                    latestEmbedding = null;
                                                    golden.clear();
                                                    observePreviewFace(null);
                                                    if(registered.isEmpty())
                                                        reco_name.setText(gfText("Adicionar rosto", "Add face"));
                                                    else
                                                        reco_name.setText(gfText("Nenhum rosto detectado!", "No face detected!"));
                                                }
                                                } catch (RuntimeException e) {
                                                    latestFace = null;
                                                    latestEmbedding = null;
                                                    observePreviewFace(null);
                                                    android.util.Log.e("GoldenFace", "Camera frame conversion failed", e);
                                                }
                                            }
                                        })
                                .addOnFailureListener(
                                        new OnFailureListener() {
                                            @Override
                                            public void onFailure(@NonNull Exception e) {
                                                if (generation == cameraGeneration && activityCameraBound) observePreviewFace(null);
                                            }
                                        })
                                .addOnCompleteListener(new OnCompleteListener<List<Face>>() {
                            @Override
                            public void onComplete(@NonNull Task<List<Face>> task) {

                                imageProxy.close(); //v.important to acquire next frame for analysis
                            }
                        });
                } catch (RuntimeException e) {
                    imageProxy.close();
                    android.util.Log.e("GoldenFace", "Camera frame processing failed", e);
                }

            }
        });


        cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, imageAnalysis, preview);
        activityPreview = preview;
        if (enrollmentOpen && enrollmentPreviewView != null)
            preview.setSurfaceProvider(enrollmentPreviewView.getSurfaceProvider());


    }

    public void recognizeImage(final Bitmap bitmap) {
        recognizeImage(bitmap, false); // Imported images must not trigger live events.
    }

    private void recognizeImage(final Bitmap bitmap, boolean fromCamera) {

        // set Face to Preview
        if (!enrollmentOpen) face_preview.setImageBitmap(bitmap);

        //Create ByteBuffer to store normalized image

        ByteBuffer imgData = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);

        imgData.order(ByteOrder.nativeOrder());

        intValues = new int[inputSize * inputSize];

        //get pixel values from Bitmap to normalize
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        imgData.rewind();

        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int pixelValue = intValues[i * inputSize + j];
                if (isModelQuantized) {
                    // Quantized model
                    imgData.put((byte) ((pixelValue >> 16) & 0xFF));
                    imgData.put((byte) ((pixelValue >> 8) & 0xFF));
                    imgData.put((byte) (pixelValue & 0xFF));
                } else { // Float model
                    imgData.putFloat((((pixelValue >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat((((pixelValue >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat(((pixelValue & 0xFF) - IMAGE_MEAN) / IMAGE_STD);

                }
            }
        }
        //imgData is input to our model
        Object[] inputArray = {imgData};

        Map<Integer, Object> outputMap = new HashMap<>();


        embeedings = new float[1][OUTPUT_SIZE]; //output of model will be stored in this variable

        outputMap.put(0, embeedings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap); //Run model
        if (fromCamera) {
            latestFace = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            latestEmbedding = embeedings[0].clone();
            latestFaceAt = android.os.SystemClock.elapsedRealtime();
        }



        float distance_local = Float.MAX_VALUE;
        String id = "0";
        String label = "?";
        String notificationName = null;

        //Compare new face with saved Faces.
        if (registered.size() > 0) {

            final List<Pair<String, Float>> nearest = findNearest(embeedings[0]);//Find 2 closest matching face

            if (nearest.get(0) != null) {

                final String name = nearest.get(0).first; //get name and distance of closest matching face
               // label = name;
                distance_local = nearest.get(0).second;
                // The original button shows gfText("Adicionar rosto", "Add face") when recognition mode is active.
                if (fromCamera) {
                    if (distance_local < distance && !enrollmentOpen) notificationName = name;
                    if (distance_local < distance && !enrollmentOpen)
                        golden.publish(name);
                    else
                        golden.clear();
                }
                if (developerMode)
                {
                    if(distance_local<distance) //If distance between Closest found face is more than 1.000 ,then output UNKNOWN face.
                        reco_name.setText(gfText("Mais próximo: ", "Nearest: ")+name +gfText("\nDistância: ", "\nDistance: ")+ String.format("%.3f",distance_local)+gfText("\n2º mais próximo: ", "\n2nd nearest: ")+nearest.get(1).first +gfText("\nDistância: ", "\nDistance: ")+ String.format("%.3f",nearest.get(1).second));
                    else
                        reco_name.setText(gfText("Desconhecido ", "Unknown ")+gfText("\nDistância: ", "\nDistance: ")+String.format("%.3f",distance_local)+gfText("\nMais próximo: ", "\nNearest: ")+name +gfText("\nDistância: ", "\nDistance: ")+ String.format("%.3f",distance_local)+gfText("\n2º mais próximo: ", "\n2nd nearest: ")+nearest.get(1).first +gfText("\nDistância: ", "\nDistance: ")+ String.format("%.3f",nearest.get(1).second));

//                    System.out.println("nearest: " + name + " - distance: " + distance_local);
                }
                else
                {
                    if(distance_local<distance) //If distance between Closest found face is more than 1.000 ,then output UNKNOWN face.
                        reco_name.setText(name);
                    else
                        reco_name.setText(gfText("Desconhecido", "Unknown"));
//                    System.out.println("nearest: " + name + " - distance: " + distance_local);
                }



                }
            }

        if (fromCamera) observePreviewFace(notificationName);

//            final int numDetectionsOutput = 1;
//            final ArrayList<SimilarityClassifier.Recognition> recognitions = new ArrayList<>(numDetectionsOutput);
//            SimilarityClassifier.Recognition rec = new SimilarityClassifier.Recognition(
//                    id,
//                    label,
//                    distance);
//
//            recognitions.add( rec );

    }
//    public void register(String name, SimilarityClassifier.Recognition rec) {
//        registered.put(name, rec);
//    }

    //Compare Faces by distance between face embeddings
    private List<Pair<String, Float>> findNearest(float[] emb) {
        List<Pair<String, Float>> neighbour_list = new ArrayList<Pair<String, Float>>();
        Pair<String, Float> ret = null; //to get closest match
        Pair<String, Float> prev_ret = null; //to get second closest match
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet())
        {

            final String name = entry.getKey();
            float[][] samples = FaceSamples.decode(entry.getValue().getExtra());
            if (samples.length == 0) continue;
            float distance = FaceSamples.distance(emb, samples);
            if (ret == null || distance < ret.second) {
                prev_ret=ret;
                ret = new Pair<>(name, distance);
            }
        }
        if(prev_ret==null) prev_ret=ret;
        neighbour_list.add(ret);
        neighbour_list.add(prev_ret);

        return neighbour_list;

    }
    public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        if (resizedBitmap != bm) bm.recycle();
        return resizedBitmap;
    }
    private static Bitmap getCropBitmapByCPU(Bitmap source, RectF cropRectF) {
        Bitmap resultBitmap = Bitmap.createBitmap((int) cropRectF.width(),
                (int) cropRectF.height(), Bitmap.Config.ARGB_8888);
        Canvas cavas = new Canvas(resultBitmap);

        // draw background
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        cavas.drawRect(
                new RectF(0, 0, cropRectF.width(), cropRectF.height()),
                paint);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-cropRectF.left, -cropRectF.top);

        cavas.drawBitmap(source, matrix, paint);

        if (source != null && !source.isRecycled()) {
            source.recycle();
        }

        return resultBitmap;
    }

    private static Bitmap rotateBitmap(
            Bitmap bitmap, int rotationDegrees, boolean flipX, boolean flipY) {
        Matrix matrix = new Matrix();

        // Rotate the image back to straight.
        matrix.postRotate(rotationDegrees);

        // Mirror the image along the X or Y axis.
        matrix.postScale(flipX ? -1.0f : 1.0f, flipY ? -1.0f : 1.0f);
        Bitmap rotatedBitmap =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // Recycle the old bitmap if it has changed.
        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }
        return rotatedBitmap;
    }

    private static byte[] YUV_420_888toNV21(Image image) {
        Image.Plane[] planes = image.getPlanes();
        return Yuv420ToNv21.convert(image.getWidth(), image.getHeight(),
                planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride());
    }

    private Bitmap toBitmap(Image image) {

        byte[] nv21=YUV_420_888toNV21(image);


        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);

        byte[] imageBytes = out.toByteArray();
        //System.out.println("bytes"+ Arrays.toString(imageBytes));

        //System.out.println("FORMAT"+image.getFormat());

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    //Save Faces to Shared Preferences.Conversion of Recognition objects to json string
    private void insertToSP(HashMap<String, SimilarityClassifier.Recognition> jsonMap,int mode) {
        if(mode==1)  //mode: 0:save all, 1:clear all, 2:update all
            jsonMap.clear();
        else if (mode==0) {
            HashMap<String, SimilarityClassifier.Recognition> merged = readFromSP();
            merged.putAll(jsonMap);
            jsonMap = merged;
        }
        String jsonString = new Gson().toJson(jsonMap);
//        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : jsonMap.entrySet())
//        {
//            System.out.println("Entry Input "+entry.getKey()+" "+  entry.getValue().getExtra());
//        }
        SharedPreferences sharedPreferences = getSharedPreferences("HashMap", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("map", jsonString);
        //System.out.println("Input josn"+jsonString.toString());
        editor.apply();
        Toast.makeText(context, gfText("Rostos salvos", "Faces saved"), Toast.LENGTH_SHORT).show();
    }

    //Load Faces from Shared Preferences.Json String to Recognition object
    private HashMap<String, SimilarityClassifier.Recognition> readFromSP(){ return readFromSP(true); }
    private HashMap<String, SimilarityClassifier.Recognition> readFromSP(boolean showToast){
        SharedPreferences sharedPreferences = getSharedPreferences("HashMap", MODE_PRIVATE);
        String defValue = new Gson().toJson(new HashMap<String, SimilarityClassifier.Recognition>());
        String json=sharedPreferences.getString("map",defValue);
       // System.out.println("Output json"+json.toString());
        TypeToken<HashMap<String,SimilarityClassifier.Recognition>> token = new TypeToken<HashMap<String,SimilarityClassifier.Recognition>>() {};
        HashMap<String,SimilarityClassifier.Recognition> retrievedMap=new Gson().fromJson(json,token.getType());
       // System.out.println("Output map"+retrievedMap.toString());

        //During type conversion and save/load procedure,format changes(eg float converted to double).
        //So embeddings need to be extracted from it in required format(eg.double to float).
        if (retrievedMap == null) return new HashMap<>();
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : retrievedMap.entrySet()) {
            if (entry.getValue() == null) continue;
            float[][] decoded = FaceSamples.decode(entry.getValue().getExtra());
            // Leave malformed entries untouched rather than silently deleting their contents.
            if (decoded.length > 0) entry.getValue().setExtra(decoded);
        }
//        System.out.println("OUTPUT"+ Arrays.deepToString(outut));
        if (showToast) Toast.makeText(context, gfText("Rostos carregados", "Faces loaded"), Toast.LENGTH_SHORT).show();
        return retrievedMap;
    }

}
