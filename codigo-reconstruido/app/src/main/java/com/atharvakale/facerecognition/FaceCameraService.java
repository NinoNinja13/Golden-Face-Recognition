package com.atharvakale.facerecognition;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import org.tensorflow.lite.Interpreter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Started only from the visible activity. Owns its camera until explicitly stopped. */
public final class FaceCameraService extends Service implements LifecycleOwner {
    static volatile boolean active; // reservation/start/stop coordination, NOT proof of an open camera
    static volatile String cameraStatus = "Serviço em segundo plano: parado";
    // Stopping background mode must not silently re-open the Activity preview.
    static volatile boolean pauseActivityPreview;
    static final String STOP = "com.golden.facerecognition.STOP_CAMERA";
    static final String LENS = "lens";
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private ExecutorService analyzer;
    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private FaceDetector detector;
    private Interpreter model;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expiryCheck = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            FaceNotifications.tick(thisService(), FaceNotifications.SERVICE, SystemClock.elapsedRealtime());
            faceOverlay.update(FaceNotifications.name());
            main.postDelayed(this, 250);
        }
    };
    private FaceCameraService thisService() { return this; }
    private volatile Map<String, float[][]> faces = java.util.Collections.emptyMap();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener facesListener = (prefs, key) -> {
        if ("map".equals(key) || key == null) loadFaces();
    };
    private float threshold;
    private volatile boolean alive;
    private int lens;
    private boolean bootStart;
    private boolean failed;
    private boolean cameraOpened;
    private BackgroundFaceOverlay faceOverlay;
    static final String SCREEN_PREFS = "GoldenScreenPolicy";
    static final String SCREEN_OFF_KEY = "recognize_screen_off";
    private volatile boolean screenPaused;
    private volatile int frameGeneration;
    private boolean cameraLifecycleStarted;
    private boolean screenReceiverRegistered;
    private final Runnable bootCameraTimeout = () -> {
        if (alive && bootStart && !screenPaused && !cameraOpened)
            fail(GoldenLanguage.text(this, "Câmera não abriu após reinício; toque na notificação para retomar.", "Camera did not open after restart; tap the notification to resume."));
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))
                refreshScreenPolicy();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener screenPreferenceListener = (prefs, key) -> {
        if (key == null || SCREEN_OFF_KEY.equals(key)) refreshScreenPolicy();
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener languagePreferenceListener = (prefs, key) -> {
        if (key == null || "language".equals(key)) main.post(() -> {
            if (!alive) return;
            FaceNotifications.createChannel(this);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(8765, notification());
        });
    };

    // The lifecycle gates only this service's CameraX use case; the service stays foreground.
    // isInteractive is Android's interactive-screen state, not a camera-state test.
    private void refreshScreenPolicy() {
        if (!alive) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        boolean continueOff = getSharedPreferences(SCREEN_PREFS, MODE_PRIVATE).getBoolean(SCREEN_OFF_KEY, false);
        boolean shouldRun = ScreenRecognitionPolicy.shouldRun(interactive, continueOff);
        if (!shouldRun) {
            if (!screenPaused) {
                screenPaused = true;
                frameGeneration++;
                cameraOpened = false;
                main.removeCallbacks(bootCameraTimeout);
                FaceNotifications.clear(this, FaceNotifications.SERVICE);
                faceOverlay.update(null);
                if (cameraLifecycleStarted) {
                    cameraLifecycleStarted = false;
                    lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
                }
            }
            cameraStatus = GoldenLanguage.text(this, "Reconhecimento pausado: tela apagada (câmera liberando)", "Recognition paused: screen off (releasing camera)");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(8765, notification());
        } else if (!cameraLifecycleStarted) {
            frameGeneration++;
            screenPaused = false;
            cameraOpened = false;
            cameraStatus = GoldenLanguage.text(this, "Serviço em segundo plano: iniciando câmera", "Background service: starting camera");
            cameraLifecycleStarted = true;
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(8765, notification());
            if (bootStart) { main.removeCallbacks(bootCameraTimeout); main.postDelayed(bootCameraTimeout, 20000); }
        }
    }

    @NonNull @Override public Lifecycle getLifecycle() { return lifecycle; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        faceOverlay = new BackgroundFaceOverlay(this);
        getSharedPreferences("HashMap", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(facesListener);
        getSharedPreferences(SCREEN_PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(screenPreferenceListener);
        getSharedPreferences("GoldenOptions", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(languagePreferenceListener);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (alive) return START_NOT_STICKY;
        active = true;
        FaceNotifications.acquire(this, FaceNotifications.SERVICE);
        cameraStatus = GoldenLanguage.text(this, "Serviço em segundo plano: iniciando câmera", "Background service: starting camera");
        failed = false;
        bootStart = intent != null && intent.getBooleanExtra(BootResumeReceiver.EXTRA_BOOT, false);
        pauseActivityPreview = true;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException(GoldenLanguage.text(this, "Permissão da câmera ausente", "Camera permission missing"));
            startForeground(8765, notification());
            alive = true;
            lens = intent == null ? CameraSelector.LENS_FACING_BACK : intent.getIntExtra(LENS, CameraSelector.LENS_FACING_BACK);
            threshold = getSharedPreferences("Distance", MODE_PRIVATE).getFloat("distance", 1f);
            loadFaces();
            try (AssetFileDescriptor afd = getAssets().openFd("mobile_face_net.tflite");
                 FileInputStream stream = new FileInputStream(afd.getFileDescriptor())) {
                model = new Interpreter(stream.getChannel().map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength()));
            }
            detector = FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build());
            FaceNotifications.createChannel(this);
            main.postDelayed(expiryCheck, 250);
            analyzer = Executors.newSingleThreadExecutor();
            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, screenFilter);
            screenReceiverRegistered = true;
            refreshScreenPolicy();
            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                if (!alive) return;
                try {
                    provider = future.get();
                    analysis = new ImageAnalysis.Builder().setTargetResolution(new Size(640, 480))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    analysis.setAnalyzer(analyzer, this::analyze);
                    CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lens).build();
                    if (!provider.hasCamera(selector)) {
                        fail(GoldenLanguage.text(this, "Câmera ", "Camera ") + (lens == CameraSelector.LENS_FACING_FRONT ? GoldenLanguage.text(this, "frontal", "front") : GoldenLanguage.text(this, "traseira", "rear")) + GoldenLanguage.text(this, " indisponível. Selecione outra.", " unavailable. Select another one."));
                        return;
                    }
                    androidx.camera.core.Camera camera = provider.bindToLifecycle(this, selector, analysis);
                    camera.getCameraInfo().getCameraState().observe(this, state -> {
                        if (!alive || screenPaused || state == null) return;
                        if (state.getError() != null) cameraStatus = GoldenLanguage.text(this, "Câmera em segundo plano: erro ", "Background camera: error ") + state.getError().getCode();
                        else switch (state.getType()) {
                            case OPEN: cameraOpened = true; cameraStatus = GoldenLanguage.text(this, "Câmera em segundo plano: aberta", "Background camera: open"); break;
                            case OPENING: cameraStatus = GoldenLanguage.text(this, "Câmera em segundo plano: abrindo", "Background camera: opening"); break;
                            case PENDING_OPEN: cameraStatus = GoldenLanguage.text(this, "Câmera em segundo plano: aguardando liberação", "Background camera: waiting for release"); break;
                            case CLOSING: cameraStatus = GoldenLanguage.text(this, "Câmera em segundo plano: fechando", "Background camera: closing"); break;
                            default: cameraStatus = GoldenLanguage.text(this, "Câmera em segundo plano: fechada", "Background camera: closed"); break;
                        }
                        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(8765, notification());
                    });
                } catch (Exception e) { fail(GoldenLanguage.text(this, "Erro ao abrir câmera: ", "Error opening camera: ") + e.getClass().getSimpleName()); }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) { fail(GoldenLanguage.text(this, "Não foi possível iniciar: ", "Could not start: ") + e.getClass().getSimpleName()); }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        String channel = "golden_face_camera";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(channel, GoldenLanguage.text(this, "Golden Face câmera", "Golden Face camera"), NotificationManager.IMPORTANCE_LOW));
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Intent stop = new Intent(this, FaceCameraService.class).setAction(STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        return new NotificationCompat.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(GoldenLanguage.text(this, "Golden Face — serviço", "Golden Face — service")).setContentText(GoldenStatus.localize(cameraStatus, "en".equals(getSharedPreferences("GoldenOptions", MODE_PRIVATE).getString("language", "pt-BR"))))
                .setContentIntent(content).setOngoing(true).addAction(0, GoldenLanguage.text(this, "Desligar", "Stop"), stopIntent).build();
    }
    private void observeFace(String name, int generation) {
        main.post(() -> {
            if (alive && !screenPaused && generation == frameGeneration) {
                FaceNotifications.observe(this, FaceNotifications.SERVICE, name, SystemClock.elapsedRealtime());
                faceOverlay.update(FaceNotifications.name());
            }
        });
    }
    private void loadFaces() {
        try {
            String json = getSharedPreferences("HashMap", MODE_PRIVATE).getString("map", "{}");
            Map<String, SimilarityClassifier.Recognition> saved = new Gson().fromJson(json,
                    new TypeToken<HashMap<String, SimilarityClassifier.Recognition>>(){}.getType());
            Map<String, float[][]> updated = new HashMap<>();
            if (saved != null) for (Map.Entry<String, SimilarityClassifier.Recognition> entry : saved.entrySet()) {
                if (entry.getValue() == null) continue;
                float[][] samples = FaceSamples.decode(entry.getValue().getExtra());
                if (samples.length > 0) updated.put(entry.getKey(), samples);
            }
            faces = java.util.Collections.unmodifiableMap(updated);
        } catch (RuntimeException e) {
            android.util.Log.e("GoldenFace", GoldenLanguage.text(this, "Falha ao atualizar rostos; mantendo lista anterior", "Failed to update faces; keeping previous list"), e);
        }
    }
    @SuppressLint("UnsafeExperimentalUsageError")
    private void analyze(@NonNull ImageProxy proxy) {
        final int generation = frameGeneration;
        if (!alive || screenPaused || proxy.getImage() == null) { proxy.close(); return; }
        Image image = proxy.getImage();
        try {
            InputImage input = InputImage.fromMediaImage(image, proxy.getImageInfo().getRotationDegrees());
            detector.process(input).addOnSuccessListener(found -> {
                if (!alive || screenPaused || generation != frameGeneration) return;
                try {
                    if (found.isEmpty() || faces.isEmpty()) { observeFace(null, generation); return; }
                    Bitmap frame = toBitmap(image);
                    Matrix transform = new Matrix(); transform.postRotate(proxy.getImageInfo().getRotationDegrees());
                    Bitmap rotated = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), transform, true);
                    Rect box = found.get(0).getBoundingBox();
                    int left = Math.max(0, box.left), top = Math.max(0, box.top);
                    int right = Math.min(rotated.getWidth(), box.right), bottom = Math.min(rotated.getHeight(), box.bottom);
                    if (right <= left || bottom <= top) { observeFace(null, generation); return; }
                    Bitmap crop = Bitmap.createBitmap(rotated, left, top, right-left, bottom-top);
                    if (lens == CameraSelector.LENS_FACING_FRONT) {
                        Matrix mirror = new Matrix(); mirror.postScale(-1, 1);
                        crop = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(), crop.getHeight(), mirror, true);
                    }
                    Bitmap scaled = Bitmap.createScaledBitmap(crop, 112, 112, true);
                    observeFace(match(scaled), generation);
                } catch (Exception e) { observeFace(null, generation); }
            }).addOnFailureListener(e -> { if (alive) observeFace(null, generation); })
                    .addOnCompleteListener(task -> proxy.close());
        } catch (Exception e) { if (alive) observeFace(null, generation); proxy.close(); }
    }
    private String match(Bitmap bitmap) {
        int[] pixels = new int[112 * 112]; bitmap.getPixels(pixels, 0, 112, 0, 0, 112, 112);
        ByteBuffer input = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            input.putFloat((((pixel >> 16) & 255) - 128f) / 128f);
            input.putFloat((((pixel >> 8) & 255) - 128f) / 128f);
            input.putFloat(((pixel & 255) - 128f) / 128f);
        }
        input.rewind(); float[][] output = new float[1][192]; model.run(input, output);
        float best = Float.MAX_VALUE; String name = null;
        for (Map.Entry<String, float[][]> entry : faces.entrySet()) {
            float distance = FaceSamples.distance(output[0], entry.getValue());
            if (distance < best) { best = distance; name = entry.getKey(); }
        }
        return best < threshold ? name : null;
    }
    private static Bitmap toBitmap(Image image) {
        int width = image.getWidth(), height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes(); int p = 0;
        ByteBuffer y = planes[0].getBuffer().duplicate(); int baseY = y.position();
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++)
            nv21[p++] = y.get(baseY + row * planes[0].getRowStride() + col * planes[0].getPixelStride());
        ByteBuffer u = planes[1].getBuffer().duplicate(), v = planes[2].getBuffer().duplicate();
        int ub = u.position(), vb = v.position();
        for (int row = 0; row < height / 2; row++) for (int col = 0; col < width / 2; col++) {
            nv21[p++] = v.get(vb + row * planes[2].getRowStride() + col * planes[2].getPixelStride());
            nv21[p++] = u.get(ub + row * planes[1].getRowStride() + col * planes[1].getPixelStride());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0, 0, width, height), 75, bytes);
        byte[] jpeg = bytes.toByteArray(); return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }
    private void fail(String message) {
        android.util.Log.e("GoldenFace", message);
        failed = true;
        cameraStatus = GoldenLanguage.text(this, "Falha no serviço: ", "Service failure: ") + message;
        if (bootStart) BootResumeReceiver.notifyResume(this);
        FaceNotifications.clear(this, FaceNotifications.SERVICE);
        if (faceOverlay != null) faceOverlay.update(null);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        stopSelf();
    }
    @Override public void onDestroy() {
        alive = false; active = false;
        if (!failed) cameraStatus = GoldenLanguage.text(this, "Serviço em segundo plano: parado", "Background service: stopped");
        if (faceOverlay != null) faceOverlay.clear();
        getSharedPreferences("HashMap", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(facesListener);
        getSharedPreferences(SCREEN_PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(screenPreferenceListener);
        getSharedPreferences("GoldenOptions", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(languagePreferenceListener);
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        main.removeCallbacksAndMessages(null);
        FaceNotifications.release(this, FaceNotifications.SERVICE);
        if (analysis != null) analysis.clearAnalyzer();
        if (provider != null && analysis != null) provider.unbind(analysis);
        if (cameraLifecycleStarted) lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (analyzer != null) analyzer.shutdown();
        if (detector != null) detector.close();
        if (model != null) model.close();
        super.onDestroy();
    }
}
