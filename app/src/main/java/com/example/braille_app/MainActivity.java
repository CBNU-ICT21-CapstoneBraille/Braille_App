package com.example.braille_app;

import android.Manifest;
import android.os.Bundle;
import android.os.Build;
import android.widget.Button;
import android.graphics.Rect;
import android.graphics.RectF;
import android.widget.Toast;
import androidx.annotation.NonNull;
import android.content.pm.PackageManager;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import java.io.File;

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

//BLE
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.AdvertiseSettings.Builder;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    //Camera
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private View rectOverlay;
    private Camera camera;

    //BLE
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic characteristic;
    private static final String SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"; // 예시 서비스 UUID
    private static final String CHARACTERISTIC_UUID = "00002a37-0000-1000-8000-00805f9b34fb"; // 예시 캐릭터리스틱 UUID

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //카메라
        previewView = findViewById(R.id.previewView);
        rectOverlay = findViewById(R.id.rectOverlay);
        requestCameraPermission();

        //촬영 버튼
        Button photoButton = findViewById(R.id.Photo);
        photoButton.setOnClickListener(view -> takePhoto());

        //BLE
        //Android12 이상 BLE 관련 권한
        //권한 이미 부여 >> BLE 초기화
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions();
        } else {
            initializeBluetooth();
        }
    }

    //BLE 초기화
    private void initializeBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth is not available or disabled", Toast.LENGTH_SHORT).show();
            return;
        }

        //GATT 서버 시작 전 권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android12 이상 >> BLUETOOTH_CONNECT 권한 확인
            //권한X >> GATT 서버 시작X
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth Connect permission is required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        //GATT 서버 설정 메소드 호출
        //SecurityException 발생 >> 처리
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback);  // GATT 서버 열기
            setupGattServer();
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    //Android 12 이상 >> BLE 관련 권한 요청
    private void requestBluetoothPermissions() {
        ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                    Boolean connectGranted = permissions.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false);

                    //권한 부여 >> BLE 초기화
                    if (connectGranted) {
                        initializeBluetooth();
                    } else {
                        Toast.makeText(this, "Bluetooth Connect permission is required", Toast.LENGTH_SHORT).show();
                    }
                });

        requestMultiplePermissionsLauncher.launch(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
        });
    }

    //BLE advertisement 설정&시작
    private void startAdvertising() {
        //권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth Advertise permission is required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        //advertisemnet 설정(fast)
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true) //연결O
                .setTimeout(0) //타임아웃X
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) //전송 파워 레벨
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true) //기기 이름 포함
                .build();

        try {
            bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback); //advertisemnet 시작
        } catch (SecurityException e) {
            //예외
            Toast.makeText(this, "Bluetooth permission denied for advertising", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    //advertisement callback 설정
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Toast.makeText(MainActivity.this, "Advertising Started", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Toast.makeText(MainActivity.this, "Advertising Failed: " + errorCode, Toast.LENGTH_SHORT).show();
        }
    };

    //GATT 서버 설정
    private void setupGattServer() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);

        //권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth Connect permission is required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback); //GATT 서버 오픈

            BluetoothGattService service = new BluetoothGattService(
                    UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY); //service 생성

            characteristic = new BluetoothGattCharacteristic(
                    UUID.fromString(CHARACTERISTIC_UUID),
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ, //읽기&알림 characteristic
                    BluetoothGattCharacteristic.PERMISSION_READ); //읽기 권한

            service.addCharacteristic(characteristic); //service에 characteristic 추가
            gattServer.addService(service); // GATT 서버에 service 추가
        } catch (SecurityException e) {
            //예외
            Toast.makeText(this, "Bluetooth permission denied for GATT server", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    //GATT 서버 callback
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Toast.makeText(MainActivity.this, "Device Connected", Toast.LENGTH_SHORT).show();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Toast.makeText(MainActivity.this, "Device Disconnected", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            if (CHARACTERISTIC_UUID.equals(characteristic.getUuid().toString())) {
                //권한 체크
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(MainActivity.this, "Bluetooth Connect permission is required", Toast.LENGTH_SHORT).show();
                        return; //권한X >> 읽기 요청 처리X
                    }
                }
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue()); //읽기 요청 응답
                } catch (SecurityException e) {
                    Toast.makeText(MainActivity.this, "Bluetooth Connect permission denied", Toast.LENGTH_SHORT).show();
                    e.printStackTrace();
                }
            }
        }
    };


    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }


    //권한
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "권한 필요", Toast.LENGTH_SHORT).show();
                }
            });


    //카메라 시작
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

                //OCR ImageAnalysis Setting
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                //OCR 작동
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


    //사진 촬영
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

    //사진 저장 경로
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

    //OCR 타이밍
    private boolean isCameraPaused = false;
    private static final long CAMERA_PAUSE_DURATION = 5000;

    //OCR
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
                (overlayTop + offset) * scaleY, //상단 -50dp
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
                            //boundingBox 좌표 >> RectF 변환
                            RectF adjustedBoundingBox = new RectF(
                                    boundingBox.left,
                                    boundingBox.top,
                                    boundingBox.right,
                                    boundingBox.bottom
                            );

                            if (RectF.intersects(adjustedBoundingBox, overlayRect)) {
                                textDetectedInOverlay = true;
                                String recognizedText = block.getText();
                                //BLE 텍스트 전송
                                sendTextOverBLE(recognizedText);
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

    private void sendTextOverBLE(String text) {
        if (characteristic != null) {
            //권한 체크
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth Connect permission is required", Toast.LENGTH_SHORT).show();
                    return; //권한X >> 데이터 전송X
                }
            }
            try {
                characteristic.setValue(text.getBytes()); //인식된 텍스트 >> characteristic 값 설정
                gattServer.notifyCharacteristicChanged(null, characteristic, false); //연결된 장치 >> 알림 전송
            } catch (SecurityException e) {
                Toast.makeText(this, "Bluetooth Connect permission denied", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        }
    }

    //OCR timing >> Camera
    private void pauseCamera() {
        isCameraPaused = true;
        new android.os.Handler().postDelayed(() -> {
            isCameraPaused = false;
        }, CAMERA_PAUSE_DURATION);
    }
}