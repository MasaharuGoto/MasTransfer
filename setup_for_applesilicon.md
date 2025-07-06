# Astrobee 開発環境構築ガイド
このガイドでは、Kiboロボットプログラミングチャレンジの開発環境をM2 Apple Siliconで構築した方法を説明します。
Kibo RPCの環境はx86_64のアーキテクチャが前提となるため、マニュアル通りでは動作しません。
ビルドに成功した事例として残しておきます。

## 事前準備
* Homebrewパッケージマネージャーがインストール済みであること
* The Unarchiverユーティリティ：macOS標準のアーカイブユーティリティでは、プロジェクトのzipファイルを正しく展開できない場合があります。

## 1\. Rosetta 2のインストール
Apple Silicon Macは、Intel (x86\_64) プロセッサ向けに作られたアプリケーションをRosetta 2を使って実行します。Android Studioやそのツールの一部で必要になる場合があります。

ターミナルを開き、以下のコマンドを実行してください。

```bash
softwareupdate --install-rosetta
```

## 2\. ARM対応Java 8のインストール
このプロジェクトではJava Development Kit (JDK) バージョン8が必要です。Homebrewの標準的な`openjdk@8`はx86\_64アーキテクチャ用のため、インストールに失敗します。ARM対応版（例：Zulu OpenJDK）をインストールする必要があります。

Homebrewを使ってZulu 8をインストールします。

```bash
brew install --cask zulu8
```

## 3\. Android Studioのインストール
プログラミングマニュアルの指定に従い、**Android Studio 3.6.3**をインストールします。
これはRosetta2で変換され実行できます。

## 4\. 環境変数の設定

コマンドラインツールが適切なソフトウェアを見つけられるように、`ANDROID_HOME`と`JAVA_HOME`環境変数を設定する必要があります。

1.  お使いのシェルの設定ファイルを開きます。デフォルトのZシェルの場合は`.zshrc`です。
2.  ファイルに以下の行を追加します。これらはHomebrewおよび標準的なAndroid Studioのセットアップでインストールした場合のパスを反映しています。
    ```bash
    # Android SDKへのパス
    export ANDROID_HOME=~/Library/Android/sdk 

    # ARM対応Zulu JDK 8へのパス
    export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
    ```
3.  現在のターミナルセッションに変更を適用します。
    ```bash
    source ~/.zshrc
    ```

## 5\. 追加のAndroid SDKコンポーネントのダウンロード

Android StudioのSDK Managerを使い、プロジェクトで要求される特定のSDKコンポーネントをインストールします。
詳細はマニュアルを参照してください。

## 6\. アプリケーションのビルド
1.  プロジェクトディレクトリ（例：`SampleApk`）に移動します。
    ```bash
    cd path/to/your/SampleApk
    ```
2.  Gradleラッパーを実行して、デバッグ用のAPKをビルドします。
    ```bash
    ./gradlew assembleDebug
    ```

ビルドが成功すると、コンパイル済みのAPKが`<YOUR_APK_PATH>/app/build/outputs/apk/debug/app-debug.apk`に出力されます 