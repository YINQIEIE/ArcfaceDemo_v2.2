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
import com.arcsoft.arcfacedemo.util.TrackUtil;
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
//    private int processMask = FaceEngine.ASF_AGE | FaceEngine.ASF_FACE3DANGLE | FaceEngine.ASF_GENDER | FaceEngine.ASF_LIVENESS;
    private int processMask = FaceEngine.ASF_NONE;

    private CameraHelper cameraHelper;//相机管理类
    private CameraListener cameraListener;//相机监听
    private FaceListener faceListener;//人脸识别监听

    //默认不支持
    private boolean detectAge = false;
    private boolean detectFaceAngle = false;
    private boolean detectGender = false;
    private boolean detectLiveness = false;
    private boolean supportMultiFace = true;

    private OnFaceFeatureInfoGetListener onFaceFeatureInfoGetListener;
    private int TIME_DELAY = 2000;//两次提取人脸之间的时间间隔

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
        FaceServer.getInstance().init(context);
    }

    /**
     * 初始化人脸识别引擎
     */
    private void initEngine() {
        faceEngine = new FaceEngine();
        afCode = faceEngine.init(context, FaceEngine.ASF_DETECT_MODE_VIDEO,
                //ConfigUtil.getFtOrient(context),
                FaceEngine.ASF_OP_270_ONLY,
                16, 20, FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_FACE_RECOGNITION | processMask);
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
                Log.i(TAG, "onFaceFeatureInfoGet callback: " + Thread.currentThread().getName());
                Log.i(TAG, "onFaceFeatureInfoGet callback: " + (faceFeature == null));
                if (null == faceFeature) return;//未获取到人脸特征
                if (null != onFaceFeatureInfoGetListener)
                    onFaceFeatureInfoGetListener.onFaceFeatureInfoGet(faceFeature, requestId);
                //FR成功
                //不做活体检测的情况，直接搜索
//                searchFace(faceFeature, requestId);
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
                        .timeDealy(TIME_DELAY)
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
//                new Handler().postDelayed(() -> Log.i(TAG, "resetPreviewViewSize: resetPreviewWidth = " + previewView.getWidth() + " resetPreviewHeight = " + previewView.getHeight()), 500);
            }

            @Override
            public void onPreview(byte[] nv21, Camera camera) {
                //MainThread
//                Log.i(TAG, "onPreview: " + Thread.currentThread().getName());
                if (null == faceEngine) return;
                if (faceRectView != null) {
                    faceRectView.clearFaceInfo();
                }
                List<FaceInfo> faceInfoList = new ArrayList<>();
                int code = faceEngine.detectFaces(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList);
                if (code == ErrorInfo.MOK && faceInfoList.size() > 0) {
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

                int ageCode = 0;
                if (detectAge) {
                    ageCode = faceEngine.getAge(ageInfoList);
                }

                int genderCode = 0;
                if (detectGender)
                    genderCode = faceEngine.getGender(genderInfoList);

                int face3DAngleCode = 0;
                if (detectFaceAngle)
                    face3DAngleCode = faceEngine.getFace3DAngle(face3DAngleList);

                int livenessCode = 0;
                if (detectLiveness) {
                    TrackUtil.keepMaxFace(faceInfoList);
                    livenessCode = faceEngine.getLiveness(faceLivenessInfoList);
                }
                //有其中一个的错误码不为0 或者最大人脸未通过活体检测 return
                if ((detectAge && ageCode != ErrorInfo.MOK) ||
                        (detectGender && genderCode != ErrorInfo.MOK) ||
                        (detectFaceAngle && face3DAngleCode != ErrorInfo.MOK) ||
                        (detectLiveness && livenessCode != ErrorInfo.MOK) ||
                        (detectLiveness && faceLivenessInfoList.size() != faceInfoList.size())) {
                    return;
                }
                if (faceRectView != null && drawHelper != null) {
                    List<DrawInfo> drawInfoList = new ArrayList<>();
                    for (int i = 0; i < faceInfoList.size(); i++) {
                        drawInfoList.add(new DrawInfo(drawHelper.adjustRect(faceInfoList.get(i).getRect()),
                                detectGender ? genderInfoList.get(i).getGender() : GenderInfo.UNKNOWN,
                                detectAge ? ageInfoList.get(i).getAge() : AgeInfo.UNKNOWN_AGE,
                                detectLiveness ? faceLivenessInfoList.get(i).getLiveness() : LivenessInfo.UNKNOWN
                                , null));
                    }
                    drawHelper.draw(faceRectView, drawInfoList);
                }
                if (!supportMultiFace) {
                    //如果不支持活体检测，此时人脸信息集合可能不止一个，执行保留最大人脸操作
                    //如果支持活体检测，则已经执行过保留最大人脸操作，无需再次执行
                    if (!detectLiveness)
                        TrackUtil.keepMaxFace(faceInfoList);
                    faceHelper.requestFaceFeature(nv21, faceInfoList.get(0), previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, 0);
                } else {
                    for (int i = 0; i < faceInfoList.size(); i++) {
                        faceHelper.requestFaceFeature(nv21, faceInfoList.get(i), previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, 0);
                    }
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
    public static void searchFace(Context mContext, final FaceFeature frFace, final Integer requestId) {
        String TAG = "FRManager#searchFace()";
        Observable
                .create((ObservableOnSubscribe<CompareResult>) emitter -> {
                    CompareResult compareResult = FaceServer.getInstance().getTopOfFaceLib(frFace);
                    if (compareResult == null) {
                        emitter.onError(null);
                    } else {
                        emitter.onNext(compareResult);
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
                            Toast.makeText(mContext, "欢迎" + compareResult.getUserName(), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Toast.makeText(mContext, "未识别出人脸", Toast.LENGTH_LONG).show();
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


    // 若 A or B = C，则
    // C or B = C
    // C & B = B
    // C xor B = A

    /**
     * 设置是否支持年龄检测
     * 在{@link #initialize()}之前调用
     * 如果在调用了{@link #initialize()}之后再次调用，需要调用{@link #resetFaceEngine()}使设置生效
     *
     * @param support true:支持 false:不支持
     * @return 当前对象
     */
    public FRManager detectAge(boolean support) {
        this.detectAge = support;
        if (support)
            processMask |= FaceEngine.ASF_AGE;
        else {
            if ((processMask | FaceEngine.ASF_AGE) == FaceEngine.ASF_AGE)
                processMask ^= FaceEngine.ASF_AGE;
        }
        return this;
    }

    /**
     * 设置是否支持性角度检测
     * 在{@link #initialize()}之前调用
     * 如果在调用了{@link #initialize()}之后再次调用，需要调用{@link #resetFaceEngine()}使设置生效
     *
     * @param support true:支持 false:不支持
     * @return 当前对象
     */
    public FRManager detectFaceAngle(boolean support) {
        this.detectFaceAngle = support;
        if (support)
            processMask |= FaceEngine.ASF_FACE3DANGLE;
        else {
            if ((processMask | FaceEngine.ASF_FACE3DANGLE) == FaceEngine.ASF_FACE3DANGLE)
                processMask ^= FaceEngine.ASF_FACE3DANGLE;
        }
        return this;
    }

    /**
     * 设置是否支持性别检测
     * 在{@link #initialize()}之前调用
     * 如果在调用了{@link #initialize()}之后再次调用，需要调用{@link #resetFaceEngine()}使设置生效
     *
     * @param support true:支持 false:不支持
     * @return 当前对象
     */
    public FRManager detectGender(boolean support) {
        this.detectGender = support;
        if (support)
            processMask |= FaceEngine.ASF_GENDER;
        else {
            if ((processMask | FaceEngine.ASF_GENDER) == FaceEngine.ASF_GENDER)
                processMask ^= FaceEngine.ASF_GENDER;
        }
        return this;
    }

    /**
     * 设置是否支持活体检测
     * 在{@link #initialize()}之前调用
     * 如果在调用了{@link #initialize()}之后再次调用，需要调用{@link #resetFaceEngine()}使设置生效
     *
     * @param support true:支持 false:不支持
     * @return 当前对象
     */
    public FRManager detectLiveness(boolean support) {
        this.detectLiveness = support;
        if (support)
            processMask |= FaceEngine.ASF_LIVENESS;
        else {
            if ((processMask | FaceEngine.ASF_LIVENESS) == FaceEngine.ASF_LIVENESS)
                processMask ^= FaceEngine.ASF_LIVENESS;
        }
        return this;
    }

    /**
     * 设置两次人脸提取特征之间的时间间隔，默认 2S
     * 在{@link #initialize()}之前调用
     * 如果在调用了{@link #initialize()}之后再次调用，需要调用{@link #resetFaceEngine()}使设置生效
     *
     * @param millionSeconds 毫秒数
     * @return FRManager 对象
     */
    public FRManager setTimeDelay(int millionSeconds) {
        this.TIME_DELAY = millionSeconds;
        return this;
    }

    /**
     * 重新设置人脸识别引擎参数
     */
    public void resetFaceEngine() {
        unInitEngine();
        initEngine();
        faceHelper = new FaceHelper.Builder()
                .faceEngine(faceEngine)
                .frThreadNum(1)
                .previewSize(previewSize)
                .faceListener(faceListener)
//                    .currentTrackId(ConfigUtil.getTrackId())
                .timeDealy(TIME_DELAY)
                .build();
    }

    public void setOnFaceFeatureInfoGetListener(OnFaceFeatureInfoGetListener onFaceFeatureInfoGetListener) {
        this.onFaceFeatureInfoGetListener = onFaceFeatureInfoGetListener;
    }


    public interface OnFaceFeatureInfoGetListener {
        void onFaceFeatureInfoGet(FaceFeature faceFeature, int requestId);
    }
}
