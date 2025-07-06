package jp.jaxa.iss.kibo.rpc.defaultapk;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import android.util.Log;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.calib3d.Calib3d;
import org.opencv.aruco.*;

import gov.nasa.arc.astrobee.types.Point;
import gov.nasa.arc.astrobee.types.Quaternion;
import gov.nasa.arc.astrobee.Kinematics;
import jp.jaxa.iss.kibo.rpc.api.KiboRpcService;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;

/**
 * Class meant to handle commands from the Ground Data System and execute them
 * in Astrobee
 */

public class YourService extends KiboRpcService {
    private static Point PAdd(Point p1, Point p2) {
        return new Point(p1.getX() + p2.getX(), p1.getY() + p2.getY(), p1.getZ() + p2.getZ());
    }

    private static Point PInv(Point p1) {
        return new Point(-p1.getX(), -p1.getY(), -p1.getZ());
    }

    private static double PLen(Point p1) {
        return Math.sqrt(p1.getX() * p1.getX() + p1.getY() * p1.getY() + p1.getZ() * p1.getZ());
    }

    private static Point PNormalize(Point p1) {
        double len = PLen(p1);
        return new Point(p1.getX() / len, p1.getY() / len, p1.getZ() / len);
    }

    private static Point PScalarMul(double s, Point p) {
        return new Point(s * p.getX(), s * p.getY(), s * p.getZ());
    }

    public static Quaternion QMul(Quaternion q1, Quaternion q2) {
        float x1 = q1.getX(), y1 = q1.getY(), z1 = q1.getZ(), w1 = q1.getW();
        float x2 = q2.getX(), y2 = q2.getY(), z2 = q2.getZ(), w2 = q2.getW();

        float x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
        float y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
        float z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
        float w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;

        return new Quaternion(x, y, z, w);
    }

    /**
     * X+方向 = (1,0,0) を、与えられた単位ベクトル v の方向に向く回転を表すクォータニオンを返す。
     * v は Point クラスで、getX(), getY(), getZ() を持つものとする。
     */
    public static Quaternion quaternionFromXDirection(Point v) {
        final double EPS = 1e-6;

        // 入力ベクトル v を正規化
        double vx = v.getX();
        double vy = v.getY();
        double vz = v.getZ();
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < EPS) {
            // ゼロベクトルが来たらとりあえず回転なしを返す
            return new Quaternion(0, 0, 0, 1);
        }
        vx /= len;
        vy /= len;
        vz /= len;

        // X 軸（1,0,0）との内積
        double dot = 1.0 * vx + 0.0 * vy + 0.0 * vz;

        // (1,0,0) と v が反対方向にほぼ一致している場合
        if (dot < -1.0 + EPS) {
            // 180度回転：X→-X, ここでは Y 軸まわりを例に取る (0,1,0) 軸で回転
            return new Quaternion(0, 1, 0, 0);
        }

        // (1,0,0) と v がほぼ同じ方向の場合 → 回転なし
        if (dot > 1.0 - EPS) {
            return new Quaternion(0, 0, 0, 1);
        }

        // 回転軸 = X × v
        double ax = 0.0 * vz - 0.0 * vy; // = 0 - 0 = 0
        double ay = 0.0 * vx - 1.0 * vz; // = -vz
        double az = 1.0 * vy - 0.0 * vx; // = vy

        // 正規化
        double axisLen = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= axisLen;
        ay /= axisLen;
        az /= axisLen;

        // 回転角度
        double angle = Math.acos(dot);

        double half = angle / 2.0;
        double s = Math.sin(half);
        double w = Math.cos(half);
        double x = ax * s;
        double y = ay * s;
        double z = az * s;

        return new Quaternion((float) x, (float) y, (float) z, (float) w);
    }

    public static Point applyQuaternion(Quaternion q, Point v) {
        double x = q.getX();
        double y = q.getY();
        double z = q.getZ();
        double w = q.getW();

        double vx = v.getX();
        double vy = v.getY();
        double vz = v.getZ();

        // q * vq (v をクォータニオン (vx,vy,vz,0) として掛け合わせ)
        double qv_x = w * vx + y * vz - z * vy;
        double qv_y = w * vy + z * vx - x * vz;
        double qv_z = w * vz + x * vy - y * vx;
        double qv_w = -x * vx - y * vy - z * vz;

        // (q * vq) * q_conj
        // q_conj = (-x, -y, -z, w)
        double rx = qv_w * (-x) + qv_x * w + qv_y * (-z) - qv_z * (-y);
        double ry = qv_w * (-y) + qv_y * w + qv_z * (-x) - qv_x * (-z);
        double rz = qv_w * (-z) + qv_z * w + qv_x * (-y) - qv_y * (-x);

        return new Point(rx, ry, rz);
    }

    public static Quaternion inverseQuaternion(Quaternion q) {
        return new Quaternion(-q.getX(), -q.getY(), -q.getZ(), q.getW());
    }

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

    private Recognize recognize;
    private Mat cameraMatrix;
    private Mat distCoeffs;

    @Override
    protected void runPlan1() {
        AreaInfo[] areas = new AreaInfo[4];
        Point[] point = { new Point(10.95, -9.58, 5.195), new Point(10.925, -8.4, 5.1), // area2 と area3 は動かない
                new Point(10.925, -8.4, 5.1), new Point(10.866984, -6.8525, 4.945) };
        Point[] comebackPoint = { new Point(10.95, -9.98, 5.195), new Point(10.925, -8.875, 4.36203),
                new Point(10.925, -7.925, 4.36203), new Point(10.466984, -6.8525, 4.945) };
        Quaternion[] quaternion = { new Quaternion(0, 0, -0.7071f, 0.7071f), new Quaternion(-0.707f, 0, 0.707f, 0),
                new Quaternion(-0.707f, 0, 0.707f, 0), new Quaternion(0, 0, 1, 0) };
        int[] ids = { 101, 102, 103, 104 };
        recognize = new Recognize(this, "hitotei_second_metadata.tflite");

        // カメラの歪み補正パラメータ
        double[][] matrix = api.getNavCamIntrinsics();
        cameraMatrix = new Mat(3, 3, CvType.CV_64F);
        cameraMatrix.put(0, 0, matrix[0][0], matrix[0][1], matrix[0][2], matrix[0][3], matrix[0][4], matrix[0][5],
                matrix[0][6], matrix[0][7], matrix[0][8]);
        distCoeffs = new Mat(1, 5, CvType.CV_64F);
        distCoeffs.put(0, 0, matrix[1][0], matrix[1][1], matrix[1][2], matrix[1][3], matrix[1][4]);

        api.startMission();

        try {
            for (int i = 0; i < 4; i++) {
                api.moveTo(point[i], quaternion[i], false);
                Kinematics kinematics = api.getRobotKinematics();
                areas[i] = recognizeArea(api.getMatNavCam(), ids[i]);

                point[i] = PAdd(kinematics.getPosition(),
                        applyQuaternion(kinematics.getOrientation(),
                                PAdd(areas[i].getAreaPoint(), new Point(-0.6, 0, 0)))); // ここに戻る時のため
                quaternion[i] = kinematics.getOrientation();

                api.setAreaInfo(i + 1, areas[i].landmarkItem.itemName, areas[i].landmarkItem.itemNumber);
                api.saveMatImage(api.getMatNavCam(), "area" + (i + 1) + ".png");
            }

            api.moveTo(new Point(11.143, -6.75, 4.9654), new Quaternion(0, 0, 0.707f, 0.707f), false);
            api.reportRoundingCompletion();

            for (int i = 0; i < 100; i++) { // max 10 秒
                PreprocessReturnType result = preprocess(api.getMatNavCam(), 100);
                if (result.isSuccess()) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            ItemInfo targetItem = recognizeArea(api.getMatNavCam(), 100).treasureItem;

            int targetArea = 3;
            for (int i = 0; i < 4; i++) {
                if (areas[i].checkTreasureItem(targetItem)) {
                    targetArea = i;
                    break;
                }
            }
            api.notifyRecognitionItem();

            this.moveInKIZ(point[targetArea], quaternion[targetArea], false);

            double angle = 0;
            do {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                // 画角にターゲットが入っているか確認
                PreprocessReturnType result = preprocess(api.getMatNavCam(), ids[targetArea], "comeback");
                final double EPS = 1e-6;

                // 入力ベクトル v を正規化
                double vx = result.getPosition().getX();
                double vy = result.getPosition().getY();
                double vz = result.getPosition().getZ();
                double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (len >= EPS) {
                    vx /= len;
                    vy /= len;
                    vz /= len;

                    // X 軸（1,0,0）との内積
                    double dot = 1.0 * vx + 0.0 * vy + 0.0 * vz;

                    if (dot < -1.0 + EPS) {
                        // (1,0,0) と v が反対方向にほぼ一致している場合
                        angle = Math.PI;
                    } else if (dot > 1.0 - EPS) {
                        // (1,0,0) と v がほぼ同じ方向の場合 → 回転なし
                        angle = 0;
                    } else {
                        // 回転角度
                        angle = Math.acos(dot);
                    }
                }
                angle = Math.toDegrees(angle);
                Log.i("runPlan1", "last degree" + angle);

                if (angle >= 15) {
                    Kinematics kinematics = api.getRobotKinematics();
                    this.moveInKIZ(kinematics.getPosition(),
                            quaternionFromXDirection(
                                    applyQuaternion(kinematics.getOrientation(), result.getPosition())),
                            false);
                    Log.i("runPlan1", "adjust orientation");
                }
            } while (angle >= 15);

            api.saveMatImage(api.getMatNavCam(), "last.png");
        } catch (Exception e) {
            Log.e("runPlan1", "Error during object detection: " + e);
            e.printStackTrace();
        }
        api.takeTargetItemSnapshot();
    }

    @Override
    protected void runPlan2() {
        // write your plan 2 here
    }

    @Override
    protected void runPlan3() {
        // write your plan 3 here
    }

    private void moveInKIZ(Point point, Quaternion quaternion, boolean log) {
        Point min = new Point(10.3, -10.2, 4.32);
        Point max = new Point(11.55, -6.0, 5.57);

        double x = point.getX(), y = point.getY(), z = point.getZ();
        if (x < min.getX()) {
            x = min.getX();
        } else if (x > max.getX()) {
            x = max.getX();
        }
        if (y < min.getY()) {
            y = min.getY();
        } else if (y > max.getY()) {
            y = max.getY();
        }
        if (z < min.getZ()) {
            z = min.getZ();
        } else if (z > max.getZ()) {
            z = max.getZ();
        }

        api.moveTo(new Point(x, y, z), quaternion, log);
    }

    private static class AreaInfo {
        private ItemInfo landmarkItem;
        private ItemInfo treasureItem;
        private Point areaPoint;

        public AreaInfo() {
            areaPoint = new Point(0, 0, 0);
        }

        public AreaInfo(ItemInfo landmark, ItemInfo treasure, Point point) {
            this.landmarkItem = landmark;
            this.treasureItem = treasure;
            this.areaPoint = point;
        }

        public boolean checkTreasureItem(ItemInfo targetItem) {
            return treasureItem.checkTreasureItem(targetItem);
        }

        public Point getAreaPoint() {
            return areaPoint;
        }
    }

    private static class ItemInfo {
        private String itemName;
        private int itemNumber;

        public ItemInfo() {
            itemName = "nothing";
            itemNumber = 0;
        }

        public ItemInfo(String name, int number) {
            this.itemName = name;
            this.itemNumber = number;
        }

        public String getItemName() {
            return itemName;
        }

        public int getItemNumber() {
            return itemNumber;
        }

        public boolean checkTreasureItem(ItemInfo targetItem) {
            if (this.itemName == "nothing") {
                return false;
            }
            return this.itemName == targetItem.itemName;
        }
    }

    private static class PreprocessReturnType {
        private Mat image;
        private Point position;
        private boolean success;

        public PreprocessReturnType(Mat image, Point position, boolean success) {
            this.image = image;
            this.position = position;
            this.success = success;
        }

        Mat getImage() {
            return image;
        }

        Point getPosition() {
            return position;
        }

        boolean isSuccess() {
            return success;
        }
    }

    /**
     * エリア認識処理
     */
    private AreaInfo recognizeArea(Mat img, int id) {
        PreprocessReturnType tmp = preprocess(img, id);
        Point point = tmp.getPosition();

        Mat pre = tmp.getImage();
        Recognize.Result[] result = recognize.detectObjects(pre);

        ItemInfo landmark = new ItemInfo(), treasure = new ItemInfo();

        double maxLandmarkConfidence = 0;
        double maxTreasureConfidence = 0;
        for (int i = 0; i < result.length; i++) {
            if (result[i].n > 0) {
                if (i == 3 || i == 4 || i == 5) { // crystal, diamond, emerald
                    if (result[i].confidence > maxTreasureConfidence) {
                        treasure = new ItemInfo(labels[i], result[i].n);
                        maxTreasureConfidence = result[i].confidence;
                    }
                } else { // coin, compass, coral, fossil, key, letter, shell, treasure_box
                    if (result[i].confidence > maxLandmarkConfidence) {
                        landmark = new ItemInfo(labels[i], result[i].n);
                        maxLandmarkConfidence = result[i].confidence;
                    }
                }
            }
        }
        return new AreaInfo(landmark, treasure, point);
    }

    /**
     * 前処理
     * 切り抜き・変形と2値化を行う
     * Areaの正確な位置もここで検出する
     */
    private PreprocessReturnType preprocess(Mat image, int id) {
        return preprocess(image, id, "");
    }

    private PreprocessReturnType preprocess(Mat image, int id, String file_tag) {
        final String TAG = "Preprocess";
        Log.i(TAG, "preprocess " + id + file_tag);
        Mat completed = image;
        Point point = new Point();

        Dictionary dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_5X5_1000);
        DetectorParameters parameters = DetectorParameters.create();

        Mat undistorted = new Mat();
        Calib3d.undistort(image, undistorted, cameraMatrix, distCoeffs);
        // Log.i(TAG, "undistort");

        // グレースケール変換 は不要
        Mat gray = undistorted;
        // Imgproc.cvtColor(undistorted, gray, Imgproc.COLOR_BGR2GRAY);

        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        List<Mat> rejected = new ArrayList<>();
        Aruco.detectMarkers(gray, dictionary, corners, ids, parameters, rejected);
        // kLog.i(TAG, "detect marker");

        Mat image1 = undistorted.clone();
        if (!ids.empty()) {
            Aruco.drawDetectedMarkers(image1, corners, ids, new Scalar(0, 255, 0));
        }
        api.saveMatImage(image1, "marker_detect" + id + file_tag + ".png");
        // Log.i(TAG, "save image of detected marker");

        boolean success = false;

        if (!ids.empty()) {
            for (int i = 0; i < ids.rows(); i++) {
                int[] data = new int[1];
                ids.get(i, 0, data);
                // Log.i(TAG, "Marker " + data[0]);
                if (data[0] != id) {
                    continue;
                }

                Mat rvecs = new Mat();
                Mat tvecs = new Mat();
                // Aruco.estimatePoseSingleMarkers(corners, 0.05f, cameraMatrix, distCoeffs,
                // rvecs, tvecs);

                Mat cornerMat = corners.get(i);
                // Log.i(TAG, "cornerMat" + cornerMat);

                // double[] tvec = tvecs.get(i, 0);
                // point = new Point(tvec[2], tvec[0], tvec[1]); // astrobee は x が前
                // Log.i(TAG, "point " + point);

                float[] cornerData = new float[(int) (cornerMat.total() * cornerMat.channels())];
                cornerMat.get(0, 0, cornerData);

                org.opencv.core.Point lt = new org.opencv.core.Point(cornerData[0], cornerData[1]);
                org.opencv.core.Point rt = new org.opencv.core.Point(cornerData[2], cornerData[3]);
                org.opencv.core.Point rb = new org.opencv.core.Point(cornerData[4], cornerData[5]);
                org.opencv.core.Point lb = new org.opencv.core.Point(cornerData[6], cornerData[7]);

                org.opencv.core.Point[] ptsSrcArray = new org.opencv.core.Point[4];
                ptsSrcArray[0] = new org.opencv.core.Point(lt.x - (rt.x - lt.x) * 4.25 - (lb.x - lt.x) * 0.8,
                        lt.y - (rt.y - lt.y) * 4.25 - (lb.y - lt.y) * 0.8);
                ptsSrcArray[1] = new org.opencv.core.Point(lt.x - (rt.x - lt.x) * 0.25 - (lb.x - lt.x) * 0.8,
                        lt.y - (rt.y - lt.y) * 0.25 - (lb.y - lt.y) * 0.8);
                ptsSrcArray[2] = new org.opencv.core.Point(lb.x - (rb.x - lb.x) * 0.25 + (lb.x - lt.x) * 2.2,
                        lb.y - (rb.y - lb.y) * 0.25 + (lb.y - lt.y) * 2.2);
                ptsSrcArray[3] = new org.opencv.core.Point(lb.x - (rb.x - lb.x) * 4.25 + (lb.x - lt.x) * 2.2,
                        lb.y - (rb.y - lb.y) * 4.25 + (lb.y - lt.y) * 2.2);

                MatOfPoint2f ptsSrc = new MatOfPoint2f();
                ptsSrc.fromArray(ptsSrcArray);

                Log.i(TAG, "calculate area");

                int size = 640;
                MatOfPoint2f ptsDst = new MatOfPoint2f(
                        new org.opencv.core.Point(0, 0),
                        new org.opencv.core.Point(size - 1, 0),
                        new org.opencv.core.Point(size - 1, size - 1),
                        new org.opencv.core.Point(0, size - 1));

                Mat M = Imgproc.getPerspectiveTransform(ptsSrc, ptsDst);
                Mat warped = new Mat();
                Imgproc.warpPerspective(undistorted, warped, M, new Size(size, size));

                // 二値化
                Mat binary = new Mat();
                Imgproc.threshold(warped, binary, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
                completed = binary;

                MatOfDouble matZero = new MatOfDouble(0, 0, 0, 0, 0);
                MatOfPoint2f imagePoints = new MatOfPoint2f(
                        new org.opencv.core.Point((ptsSrcArray[0].x + ptsSrcArray[2].x) / 2,
                                (ptsSrcArray[0].y + ptsSrcArray[2].y) / 2),
                        new org.opencv.core.Point(ptsSrcArray[0].x, ptsSrcArray[0].y),
                        new org.opencv.core.Point(ptsSrcArray[1].x, ptsSrcArray[1].y),
                        new org.opencv.core.Point(ptsSrcArray[2].x, ptsSrcArray[2].y),
                        new org.opencv.core.Point(ptsSrcArray[3].x, ptsSrcArray[3].y));
                MatOfPoint3f realPoints = new MatOfPoint3f(
                        new org.opencv.core.Point3(0, 0, 0),
                        new org.opencv.core.Point3(-0.1, -0.1, 0),
                        new org.opencv.core.Point3(0.1, -0.1, 0),
                        new org.opencv.core.Point3(0.1, 0.1, 0),
                        new org.opencv.core.Point3(-0.1, 0.1, 0));
                Calib3d.solvePnP(realPoints, imagePoints,
                        cameraMatrix, matZero, rvecs, tvecs);
                double[] tvec = new double[3];
                tvecs.get(0, 0, tvec);
                point = new Point(tvec[2], tvec[0], tvec[1]); // astrobee は x が前

                success = true;
                break;
            }
        }

        api.saveMatImage(completed, "preprocess" + id + file_tag + ".png");
        return new PreprocessReturnType(completed, point, success);
    }
}
