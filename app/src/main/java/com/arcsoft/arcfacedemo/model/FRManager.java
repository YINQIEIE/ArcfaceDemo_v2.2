package com.arcsoft.arcfacedemo.model;

import android.content.Context;
import android.graphics.Point;
import android.hardware.Camera;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.arcsoft.arcfacedemo.R;
import com.arcsoft.arcfacedemo.faceserver.CompareResult;
import com.arcsoft.arcfacedemo.faceserver.FaceServer;
import com.arcsoft.arcfacedemo.util.DrawHelper;
import com.arcsoft.arcfacedemo.util.camera.CameraHelper;
import com.arcsoft.arcfacedemo.util.camera.CameraListener;
import com.arcsoft.arcfacedemo.util.face.FaceHelper;
import com.arcsoft.arcfacedemo.util.face.FaceListener;
import com.arcsoft.arcfacedemo.widget.FaceRectView;
import com.arcsoft.face.AgeInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.Face3DAngle;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.GenderInfo;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.VersionInfo;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 人脸识别管理类
 */
public class FRManager {

    public final String TAG = this.getClass().getSimpleName();

    private Context context;//ApplicationContext
    private int previewWidth, previewHeight;//预览 View 宽高
    private int rotation;//屏幕方向
    /**
     * 相机预览显示的控件，可为SurfaceView或TextureView
     */
    private View previewView;
    private FaceRectView faceRectView;//人脸追踪框
    private FaceEngine faceEngine;
    private int afCode = -1;
    private DrawHelper drawHelper;//人脸追踪框绘制 Helper
    private FaceHelper faceHelper;//人脸识别
    private Camera.Size previewSize;//找出的相机预览分辨率

    //设置引擎支持的类型
    private int processMask = FaceEngine.ASF_AGE | FaceEngine.ASF_FACE3DANGLE | FaceEngine.ASF_GENDER | FaceEngine.ASF_LIVENESS;

    private CameraHelper cameraHelper;//相机管理类
    private CameraListener cameraListener;//相机监听
    private FaceListener faceListener;//人脸识别监听

    public FRManager(Context context, int rotation, View previewView, FaceRectView faceRectView) {
        this.context = context;
        this.previewView = previewView;
        this.faceRectView = faceRectView;
        this.previewWidth = previewView.getWidth();
        this.previewHeight = previewView.getHeight();
        this.rotation = rotation;
    }

    /**
     * 初始化
     */
    public void initialize() {
        initEngine();
        initCamera();
        initLocalFaceData();
    }

    /**
     * 初始化本地人脸数据
     */
    private void initLocalFaceData() {
        FaceServer.getInstance().init(context.getApplicationContext());
    }

    /**
     * 初始化人脸识别引擎
     */
    private void initEngine() {
        faceEngine = new FaceEngine();
        afCode = faceEngine.init(context, FaceEngine.ASF_DETECT_MODE_VIDEO,
                //ConfigUtil.getFtOrient(context),
                FaceEngine.ASF_OP_270_ONLY,
                16, 20, FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_AGE | FaceEngine.ASF_FACE3DANGLE | FaceEngine.ASF_GENDER | FaceEngine.ASF_LIVENESS);
        VersionInfo versionInfo = new VersionInfo();
        faceEngine.getVersion(versionInfo);
        if (afCode != ErrorInfo.MOK) {
            Toast.makeText(context, context.getResources().getString(R.string.init_failed, afCode), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 初始化相机
     */
    private void initCamera() {
        initListeners();
        cameraHelper = new CameraHelper.Builder()
                .previewViewSize(new Point(previewWidth, previewHeight))
                .rotation(rotation)
                .specificCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT)
                .isMirror(false)
                .previewOn(previewView)
                .cameraListener(cameraListener)
                .build();
        cameraHelper.init();
        cameraHelper.start();
    }

    private void initListeners() {
        faceListener = new FaceListener() {
            @Override
            public void onFail(Exception e) {
                Log.e(TAG, "onFail: " + e.getMessage());
            }

            //请求FR的回调
            @Override
            public void onFaceFeatureInfoGet(@Nullable final FaceFeature faceFeature, final Integer requestId) {
                //FR成功
                if (faceFeature != null) {
                    //不做活体检测的情况，直接搜索
                    searchFace(faceFeature, requestId);
                }
            }

        };
        cameraListener = new CameraListener() {
            @Override
            public void onCameraOpened(Camera camera, int cameraId, int displayOrientation, boolean isMirror) {
                Log.i(TAG, "onCameraOpened: " + cameraId + "  " + displayOrientation + " " + isMirror);
                previewSize = camera.getParameters().getPreviewSize();
                calPreviewViewSize(previewSize);
                drawHelper = new DrawHelper(previewSize.width, previewSize.height, previewWidth, previewHeight, displayOrientation
                        , cameraId, isMirror, false, false);
                faceHelper = new FaceHelper.Builder()
                        .faceEngine(faceEngine)
                        .frThreadNum(1)
                        .previewSize(previewSize)
                        .faceListener(faceListener)
//                    .currentTrackId(ConfigUtil.getTrackId())
                        .build();
            }

            /**
             * 技术算预览 View 大小
             * @param previewSize 相机预览分辨率
             */
            private void calPreviewViewSize(Camera.Size previewSize) {
                previewHeight = (int) ((float) previewWidth / previewSize.height * previewSize.width);
                Log.i(TAG, "onCameraOpened: resetPreviewViewSize = " + previewWidth + " resetPreviewHeight = " + previewHeight);
                resetPreviewViewSize();
            }

            /**
             * preview 和 faceRectView 必须大小一样，且位置重合
             * 最直接的就是帧布局放在一起，所以可以用一个 LayoutParam
             */
            private void resetPreviewViewSize() {
                ViewGroup.LayoutParams layoutParams = previewView.getLayoutParams();
                layoutParams.height = previewHeight;
                previewView.setLayoutParams(layoutParams);
                faceRectView.setLayoutParams(layoutParams);
                Log.i(TAG, "onCameraOpened: resetPreviewWidth = " + previewView.getWidth() + " resetPreviewHeight = " + previewView.getHeight());
            }

            @Override
            public void onPreview(byte[] nv21, Camera camera) {
                //MainThread
                Log.i(TAG, "onPreview: " + Thread.currentThread().getName());

                if (faceRectView != null) {
                    faceRectView.clearFaceInfo();
                }
                List<FaceInfo> faceInfoList = new ArrayList<>();
                int code = faceEngine.detectFaces(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList);
                if (code == ErrorInfo.MOK && faceInfoList.size() > 0) {
                    faceHelper.requestFaceFeature(nv21, faceInfoList.get(0), previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, 0);
                    code = faceEngine.process(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList, processMask);
                    if (code != ErrorInfo.MOK) {
                        return;
                    }
                } else {
                    return;
                }

                List<AgeInfo> ageInfoList = new ArrayList<>();
                List<GenderInfo> genderInfoList = new ArrayList<>();
                List<Face3DAngle> face3DAngleList = new ArrayList<>();
                List<LivenessInfo> faceLivenessInfoList = new ArrayList<>();
                int ageCode = faceEngine.getAge(ageInfoList);
                int genderCode = faceEngine.getGender(genderInfoList);
                int face3DAngleCode = faceEngine.getFace3DAngle(face3DAngleList);
                int livenessCode = faceEngine.getLiveness(faceLivenessInfoList);

                //有其中一个的错误码不为0，return
                if ((ageCode | genderCode | face3DAngleCode | livenessCode) != ErrorInfo.MOK) {
                    return;
                }
                if (faceRectView != null && drawHelper != null) {
                    List<DrawInfo> drawInfoList = new ArrayList<>();
                    for (int i = 0; i < faceInfoList.size(); i++) {
                        drawInfoList.add(new DrawInfo(drawHelper.adjustRect(faceInfoList.get(i).getRect()), genderInfoList.get(i).getGender(), ageInfoList.get(i).getAge(), faceLivenessInfoList.get(i).getLiveness(), null));
                    }
                    drawHelper.draw(faceRectView, drawInfoList);
                }
            }

            @Override
            public void onCameraClosed() {
                Log.i(TAG, "onCameraClosed: ");
            }

            @Override
            public void onCameraError(Exception e) {
                Log.i(TAG, "onCameraError: " + e.getMessage());
            }

            @Override
            public void onCameraConfigurationChanged(int cameraID, int displayOrientation) {
                if (drawHelper != null) {
                    drawHelper.setCameraDisplayOrientation(displayOrientation);
                }
                Log.i(TAG, "onCameraConfigurationChanged: " + cameraID + "  " + displayOrientation);
            }
        };

    }


    /**
     * 人脸比对
     *
     * @param frFace    人脸特征
     * @param requestId id
     */
    private void searchFace(final FaceFeature frFace, final Integer requestId) {
        Observable
                .create(new ObservableOnSubscribe<CompareResult>() {
                    @Override
                    public void subscribe(ObservableEmitter<CompareResult> emitter) {
                        CompareResult compareResult = FaceServer.getInstance().getTopOfFaceLib(frFace);
                        if (compareResult == null) {
                            emitter.onError(null);
                        } else {
                            emitter.onNext(compareResult);
                        }
                    }
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<CompareResult>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(CompareResult compareResult) {
                        if (compareResult == null || compareResult.getUserName() == null) {
                            return;
                        }
                        if (compareResult.getSimilar() > 0.8f) {
                            Toast.makeText(context.getApplicationContext(), "欢迎" + compareResult.getUserName(), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Toast.makeText(context.getApplicationContext(), "未识别出人脸", Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onComplete() {
                        Log.i(TAG, "onComplete: recognize done!");
                    }
                });
    }

    /**
     * 销毁引擎
     */
    private void unInitEngine() {
        if (afCode == 0) {
            afCode = faceEngine.unInit();
            Log.i(TAG, "unInitEngine: " + afCode);
        }
    }

    /**
     * 开始人脸检测
     */
    public void start() {
        if (null != cameraHelper)
            cameraHelper.start();
    }

    /**
     * 销毁
     */
    public void destroy() {
        previewView = null;
        faceRectView = null;
        if (cameraHelper != null) {
            cameraHelper.release();
            cameraHelper = null;
        }
        unInitEngine();
    }

    public boolean isCameraInitialized() {
        return null != cameraHelper && cameraHelper.isCameraInitialized();
    }
}
