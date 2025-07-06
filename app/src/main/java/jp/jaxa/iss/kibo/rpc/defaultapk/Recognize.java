package jp.jaxa.iss.kibo.rpc.defaultapk;

import android.content.Context;
import android.graphics.Bitmap;
import android.provider.ContactsContract;
import android.renderscript.ScriptGroup;
import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.android.Utils; // OpenCV Mat と Bitmap 間の変換に必要

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.CastOp;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.content.res.AssetManager;
import android.graphics.BitmapFactory;

/**
 * TensorFlow Lite を使用して YOLO モデルでオブジェクト検出を実行するクラス。
 * OpenCV Mat 形式の画像を入力として受け取り、検出されたオブジェクトの種類と数を返します。
 */
public class Recognize {
    private final float INPUT_MEAN = 0f;
    private final float INPUT_STANDARD_DEVIATION = 255f;
    private DataType INPUT_IMAGE_TYPE = DataType.FLOAT32;
    private DataType OUTPUT_IMAGE_TYPE = DataType.FLOAT32;
    private final float CONFIDENCE_THRESHOLD = 0.01F;
    private final float IOU_THRESHOLD = 0.7F;

    private static final String TAG = "Recognize";
    private final Context context;
    private final String modelPath;
    private final String[] labels = {
            "coin",
            "compass",
            "coral",
            "crystal",
            "diamond",
            "emerald",
            "fossil",
            "key",
            "letter",
            "shell",
            "treasure_box" };
    private int tensorWidth = 0;
    private int tensorHeight = 0;
    private int numChannel = 0;
    private int numElements = 0;
    private ImageProcessor imageProcessor = new ImageProcessor.Builder()
            .add(new NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
            .add(new CastOp(INPUT_IMAGE_TYPE))
            .build(); // preprocess input
    private Interpreter.Options options;
    private Interpreter interpreter;

    public static class Detection {
        public final int classId;
        public final float confidence;
        public final float x1, y1, x2, y2;

        public Detection(int classId, float confidence, float x1, float y1, float x2, float y2) {
            this.classId = classId;
            this.confidence = confidence;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    public class Result {
        public int n;
        public double confidence;

        public Result() {
            this.n = 0;
            this.confidence = 0;
        }

        public Result(int number, double confidence) {
            this.n = number;
            this.confidence = confidence;
        }
    }

    /**
     * Recognize の新しいインスタンスを初期化します。
     *
     * @param context   アプリケーションのコンテキスト。
     * @param modelPath assets フォルダ内の TensorFlow Lite モデルファイルへのパス (例:
     *                  "yolov5s.tflite")。
     */
    public Recognize(Context context, String modelPath) {
        this.context = context;
        this.modelPath = modelPath;
        initializeObjectDetector();
    }

    /**
     * ObjectDetector を初期化します。
     * モデルの読み込みと設定を行います。
     */
    private void initializeObjectDetector() {
        try {
            Log.i("Recognize", "load_model" + modelPath);
            this.options = new Interpreter.Options();
            this.interpreter = new Interpreter(FileUtil.loadMappedFile(context, modelPath), this.options);

            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outputShape = interpreter.getOutputTensor(0).shape();

            tensorWidth = inputShape[1];
            tensorHeight = inputShape[2];
            numChannel = outputShape[1];
            numElements = outputShape[2];

            Log.i(TAG, "TensorFlow Lite ObjectDetector initialized successfully.");
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize TensorFlow Lite ObjectDetector: " + e.getMessage());
            // エラー処理: 例えば、ユーザーにエラーメッセージを表示するなど
        }
    }

    /**
     * OpenCV Mat 形式の画像からオブジェクトを検出し、その種類と数を集計します。
     *
     * @param imageMat 検出を実行する OpenCV Mat 形式の画像。
     * @return 検出されたオブジェクトの種類と数を格納する Map<String, Integer>。
     *         検出に失敗した場合は空の Map を返します。
     */
    public Result[] detectObjects(Mat imageMat) {
        Result[] detectionCounts = new Result[11];
        for (int i = 0; i < detectionCounts.length; i++) {
            detectionCounts[i] = new Result();
        }

        if (imageMat == null || imageMat.empty()) {
            Log.e(TAG, "Input image Mat is null or empty.");
            return detectionCounts;
        }

        try {
            Log.i(TAG, "image: " + imageMat.cols() + " * " + imageMat.rows() + "tensor: " + tensorWidth + "*"
                    + tensorHeight);
            // OpenCV Mat を Android Bitmap に変換
            Bitmap bitmap = Bitmap.createBitmap(imageMat.cols(), imageMat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(imageMat, bitmap);
            // Bitmap bitmap =
            // BitmapFactory.decodeStream(context.getAssets().open("sample.png")); //
            // ファイルから読み取りバージョン

            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, tensorWidth, tensorHeight, false);

            // Bitmap を TensorImage に変換
            TensorImage tensorImage = TensorImage.fromBitmap(resizedBitmap);

            Log.i(TAG, "conversion finished");

            // オブジェクト検出を実行
            TensorImage processedImage = imageProcessor.process(tensorImage);
            ByteBuffer imageBuffer = processedImage.getBuffer();

            Log.i(TAG, "image buffer setting finished");

            int[] shape = { 1, numChannel, numElements };
            TensorBuffer output = TensorBuffer.createFixedSize(shape, DataType.FLOAT32);
            Log.i(TAG, "output buffer setting finished");
            interpreter.run(imageBuffer, output.getBuffer());
            Log.i(TAG, "interpreter finished");

            float[] array = output.getFloatArray();
            // 検出結果を集計
            List<Detection> detections = new ArrayList<>();
            for (int i = 0; i < 8400; i++) {
                // クラススコアの最大値とそのインデックスを取得
                int classId = -1;
                float maxScore = -Float.MAX_VALUE;
                for (int j = 4; j < 15; j++) {
                    if (array[i + 8400 * j] > maxScore) {
                        maxScore = array[i + 8400 * j];
                        classId = j - 4;
                    }
                }

                if (maxScore >= 0.25f) {
                    float xCenter = array[i + 8400 * 0];
                    float yCenter = array[i + 8400 * 1];
                    float width = array[i + 8400 * 2];
                    float height = array[i + 8400 * 3];

                    float x1 = xCenter - width / 2;
                    float y1 = yCenter - height / 2;
                    float x2 = xCenter + width / 2;
                    float y2 = yCenter + height / 2;

                    detections.add(new Detection(classId, maxScore, x1, y1, x2, y2));
                }
            }
            Log.i(TAG, detections.size() + " objects were detected");
            for (int i = 0; i < 20 && i < detections.size(); i++) {
                Detection detected = detections.get(i);
                Log.i(TAG,
                        "detected : " + detected.confidence + ":" + labels[detected.classId] + "["
                                + detected.classId + "]" + ",(" + detected.x1 + ","
                                + detected.y1 + ")-(" + detected.x2 + "," + detected.y2 + ")");
            }
            List<Detection> finalDetections = NMSProcessor.nonMaxSuppression(detections, IOU_THRESHOLD);
            Log.i(TAG, "NMS applied");
            Log.i(TAG, finalDetections.size() + " objects were detected");
            for (int i = 0; i < 20 && i < finalDetections.size(); i++) {
                Detection detected = finalDetections.get(i);
                Log.i(TAG,
                        "detected : " + detected.confidence + ":" + labels[detected.classId] + "["
                                + detected.classId + "]" + ",(" + detected.x1 + ","
                                + detected.y1 + ")-(" + detected.x2 + "," + detected.y2 + ")");
                detectionCounts[detected.classId].n++;
                if (detected.confidence > detectionCounts[detected.classId].confidence) {
                    detectionCounts[detected.classId].confidence = detected.confidence;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during object detection: " + e);
            e.printStackTrace();
        }
        return detectionCounts;
    }
}

class NMSProcessor {

    /**
     * 非最大抑制（NMS）
     *
     * @param detections   元の検出リスト（YoloPostProcessor.Detection）
     * @param iouThreshold 除外するIoUの閾値（例: 0.5f）
     * @return NMS適用後の検出リスト
     */
    public static List<Recognize.Detection> nonMaxSuppression(
            List<Recognize.Detection> detections, float iouThreshold) {

        // 1. スコアで降順にソート
        List<Recognize.Detection> sortedDetections = new ArrayList<Recognize.Detection>(detections);
        Collections.sort(sortedDetections, new Comparator<Recognize.Detection>() {
            @Override
            public int compare(Recognize.Detection d1, Recognize.Detection d2) {
                // 降順：d2 - d1
                if (d2.confidence > d1.confidence)
                    return 1;
                else if (d2.confidence < d1.confidence)
                    return -1;
                else
                    return 0;
            }
        });

        List<Recognize.Detection> result = new ArrayList<Recognize.Detection>();

        // 2. 最も高いスコアのものを拾い、重複を除去
        while (!sortedDetections.isEmpty()) {
            // 最初の要素（最高スコア）を取り出す
            Recognize.Detection best = sortedDetections.remove(0);
            result.add(best);

            // IoUが閾値以上のものをリストから削除
            Iterator<Recognize.Detection> it = sortedDetections.iterator();
            while (it.hasNext()) {
                Recognize.Detection other = it.next();
                if (iou(best, other) > iouThreshold) {
                    it.remove();
                }
            }
        }

        return result;
    }

    /**
     * 2つのバウンディングボックス間のIntersection over Union（IoU）を計算
     */
    private static float iou(Recognize.Detection d1, Recognize.Detection d2) {
        float x1 = Math.max(d1.x1, d2.x1);
        float y1 = Math.max(d1.y1, d2.y1);
        float x2 = Math.min(d1.x2, d2.x2);
        float y2 = Math.min(d1.y2, d2.y2);

        float intersectionWidth = x2 - x1;
        float intersectionHeight = y2 - y1;
        float intersectionArea = 0;
        if (intersectionWidth > 0 && intersectionHeight > 0) {
            intersectionArea = intersectionWidth * intersectionHeight;
        }

        float area1 = (d1.x2 - d1.x1) * (d1.y2 - d1.y1);
        float area2 = (d2.x2 - d2.x1) * (d2.y2 - d2.y1);

        return intersectionArea / (area1 + area2 - intersectionArea);
    }
}
