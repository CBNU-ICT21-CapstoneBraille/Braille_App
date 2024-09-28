package com.example.braille_app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.concurrent.ExecutionException;
import android.graphics.Rect;
import android.graphics.RectF;

//CameraX
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import android.view.View;

//MLKit
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import androidx.camera.core.ExperimentalGetImage;

public class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private ImageAnalysis imageAnalysis;
    private View rectOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        rectOverlay = findViewById(R.id.rectOverlay);
        requestCameraPermission();

        Button photoButton = findViewById(R.id.Photo);
        photoButton.setOnClickListener(view -> takePhoto());
    }


    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }


    //Authority
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "권한 필요", Toast.LENGTH_SHORT).show();
                }
            });


    //Start Camera
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                //ImageAnalysis Settings for OCR
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                //OCR works
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new ImageAnalysis.Analyzer() {
                    @ExperimentalGetImage
                    @Override
                    public void analyze(@NonNull ImageProxy imageProxy) {
                        processImageProxy(imageProxy);  // 이미지 분석 및 OCR 처리
                    }
                });

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }


    //Take Photo
    private void takePhoto() {
        if (imageCapture != null) {
            File photoFile = new File(getOutputDirectory(), System.currentTimeMillis() + ".jpg");

            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            Toast.makeText(MainActivity.this, "저장 성공: " + photoFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            exception.printStackTrace();
                            Toast.makeText(MainActivity.this, "저장 실패", Toast.LENGTH_SHORT).show();
                        }
                    }
            );
        }
    }

    //Photo Path
    private File getOutputDirectory() {
        File mediaDir = getExternalMediaDirs()[0];
        File outputDir = new File(mediaDir, getResources().getString(R.string.app_name));
        if (!outputDir.exists()) {
            outputDir.mkdir();
        }
        return outputDir;
    }

    //Rect
    private int convertDpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    //OCR Timing
    private boolean isCameraPaused = false;
    private static final long CAMERA_PAUSE_DURATION = 5000;

    //Methods for OCR
    @ExperimentalGetImage
    private void processImageProxy(ImageProxy imageProxy) {
        if (isCameraPaused) {
            imageProxy.close();
            return;
        }

        int imageWidth = imageProxy.getWidth();
        int imageHeight = imageProxy.getHeight();
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

        int previewWidth = previewView.getWidth();
        int previewHeight = previewView.getHeight();

        int[] overlayLocation = new int[2];
        rectOverlay.getLocationInWindow(overlayLocation);
        int overlayLeft = overlayLocation[0] - previewView.getLeft();
        int overlayTop = overlayLocation[1] - previewView.getTop();
        int overlayRight = overlayLeft + rectOverlay.getWidth();
        int overlayBottom = overlayTop + rectOverlay.getHeight();
        
        float scaleX = (float) imageWidth / previewWidth;
        float scaleY = (float) imageHeight / previewHeight;

        int offset = convertDpToPx(50);

        RectF overlayRect = new RectF(
                overlayLeft * scaleX,
                (overlayTop + offset) * scaleY, // 상단을 50dp 만큼 줄임
                overlayRight * scaleX,
                overlayBottom * scaleY
        );

        InputImage inputImage = InputImage.fromMediaImage(imageProxy.getImage(), rotationDegrees);
        com.google.mlkit.vision.text.TextRecognizer recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

        recognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    boolean textDetectedInOverlay = false;

                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        Rect boundingBox = block.getBoundingBox();
                        if (boundingBox != null) {
                            // boundingBox 좌표를 RectF로 변환
                            RectF adjustedBoundingBox = new RectF(
                                    boundingBox.left,
                                    boundingBox.top,
                                    boundingBox.right,
                                    boundingBox.bottom
                            );

                            if (RectF.intersects(adjustedBoundingBox, overlayRect)) {
                                textDetectedInOverlay = true;
                                String recognizedText = block.getText();
                                Toast.makeText(MainActivity.this, recognizedText, Toast.LENGTH_SHORT).show();
                                break;
                            }
                        }
                    }

                    if (textDetectedInOverlay) {
                        pauseCamera();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(MainActivity.this, "텍스트 인식 실패", Toast.LENGTH_SHORT).show();
                })
                .addOnCompleteListener(task -> {
                    imageProxy.close();
                });
    }


    private void pauseCamera() {
        isCameraPaused = true;
        new android.os.Handler().postDelayed(() -> {
            isCameraPaused = false;
        }, CAMERA_PAUSE_DURATION);
    }
}