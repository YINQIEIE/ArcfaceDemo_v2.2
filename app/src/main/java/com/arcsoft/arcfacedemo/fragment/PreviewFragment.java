package com.arcsoft.arcfacedemo.fragment;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.arcsoft.arcfacedemo.R;
import com.arcsoft.arcfacedemo.model.FRManager;
import com.arcsoft.arcfacedemo.widget.FaceRectView;

public class PreviewFragment extends Fragment implements ViewTreeObserver.OnGlobalLayoutListener {

    public final String TAG = getClass().getSimpleName();

    private Integer rgbCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;

    /**
     * 相机预览显示的控件，可为SurfaceView或TextureView
     */
    private View previewView;
    private FaceRectView faceRectView;

    private static final int ACTION_REQUEST_PERMISSIONS = 0x001;
    /**
     * 所需的所有权限信息
     */
    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE
    };
    private FRManager frManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WindowManager.LayoutParams attributes = getActivity().getWindow().getAttributes();
            attributes.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getActivity().getWindow().setAttributes(attributes);
        }

        // Activity启动后就锁定为启动时的方向
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_preview, container, false);

        previewView = view.findViewById(R.id.texture_preview);
        faceRectView = view.findViewById(R.id.face_rect_view);
        //测试使用
//        view.findViewById(R.id.flPreview).setOnClickListener(v -> {
//            frManager.detectLiveness(true).detectGender(true).detectAge(true);
//            frManager.resetFaceEngine();
//        });
        //在布局结束后才做初始化操作
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(this);
        return view;
    }


    /**
     * 在{@link #previewView}第一次布局完成后，去除该监听，并且进行引擎和相机的初始化
     */
    @Override
    public void onGlobalLayout() {
        previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(getActivity(), NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initFR();
        }
    }

    /**
     * 初始化人脸识别相关
     */
    private void initFR() {
        frManager = new FRManager(
                getActivity().getApplicationContext(),
                getActivity().getWindowManager().getDefaultDisplay().getRotation(),
                previewView,
                faceRectView);
        frManager.detectAge(false)
                .detectFaceAngle(false)
                .detectGender(false)
                .detectLiveness(false);
        frManager.setOnFaceFeatureInfoGetListener((faceFeature, requestId) -> {
            //子线程
            frManager.searchFace(faceFeature, requestId);
        });
        frManager.initialize();
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(getActivity().getApplicationContext(), neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    @Override
    public void onResume() {
        super.onResume();
        //回到桌面再次进入 APP 时执行
        if (null != frManager && frManager.isCameraInitialized())
            frManager.start();
    }

    @Override
    public void onDestroy() {
        if (null != frManager)
            frManager.destroy();
        super.onDestroy();
    }

}
