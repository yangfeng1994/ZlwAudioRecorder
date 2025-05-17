package com.zlw.main.recorderlib.recorder;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import com.blankj.utilcode.util.ArrayUtils;
import com.blankj.utilcode.util.FileIOUtils;
import com.hank.voice.AutomaticGainControl;
import com.hank.voice.NoiseSuppressor;
import com.zlw.main.recorderlib.recorder.listener.RecordDataListener;
import com.zlw.main.recorderlib.recorder.listener.RecordFftDataListener;
import com.zlw.main.recorderlib.recorder.listener.RecordResultListener;
import com.zlw.main.recorderlib.recorder.listener.RecordSoundSizeListener;
import com.zlw.main.recorderlib.recorder.listener.RecordStateListener;
import com.zlw.main.recorderlib.recorder.mp3.Mp3EncodeThread;
import com.zlw.main.recorderlib.recorder.wav.WavUtils;
import com.zlw.main.recorderlib.utils.ByteUtils;
import com.zlw.main.recorderlib.utils.FileUtils;
import com.zlw.main.recorderlib.utils.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import fftlib.FftFactory;

/**
 * @author zhaolewei on 2018/7/10.
 */
public class RecordHelper {
    private static final String TAG = RecordHelper.class.getSimpleName();
    private volatile static RecordHelper instance;
    private volatile RecordState state = RecordState.IDLE;
    private static final int RECORD_AUDIO_BUFFER_TIMES = 1;

    private RecordStateListener recordStateListener;
    private RecordDataListener recordDataListener;
    private RecordSoundSizeListener recordSoundSizeListener;
    private RecordResultListener recordResultListener;
    private RecordFftDataListener recordFftDataListener;
    private RecordConfig currentConfig;
    private AudioRecordThread audioRecordThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private File resultFile = null;
    private File tmpFile = null;
    private List<File> files = new ArrayList<>();
    private Mp3EncodeThread mp3EncodeThread;
    private NoiseSuppressor nsUtils = new NoiseSuppressor();
    private AutomaticGainControl agcUtils = new AutomaticGainControl();

    private RecordHelper() {
    }

    static RecordHelper getInstance() {
        if (instance == null) {
            synchronized (RecordHelper.class) {
                if (instance == null) {
                    instance = new RecordHelper();
                }
            }
        }
        return instance;
    }

    RecordState getState() {
        return state;
    }

    void setRecordStateListener(RecordStateListener recordStateListener) {
        this.recordStateListener = recordStateListener;
    }

    void setRecordDataListener(RecordDataListener recordDataListener) {
        this.recordDataListener = recordDataListener;
    }

    void setRecordSoundSizeListener(RecordSoundSizeListener recordSoundSizeListener) {
        this.recordSoundSizeListener = recordSoundSizeListener;
    }

    void setRecordResultListener(RecordResultListener recordResultListener) {
        this.recordResultListener = recordResultListener;
    }

    public void setRecordFftDataListener(RecordFftDataListener recordFftDataListener) {
        this.recordFftDataListener = recordFftDataListener;
    }

    public void start(String filePath, RecordConfig config) {
        this.currentConfig = config;
        if (state != RecordState.IDLE && state != RecordState.STOP) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        resultFile = new File(filePath);
        String tempFilePath = getTempFilePath();

        Logger.d(TAG, "----------------开始录制 %s------------------------", currentConfig.getFormat().name());
        Logger.d(TAG, "参数： %s", currentConfig.toString());
        Logger.i(TAG, "pcm缓存 tmpFile: %s", tempFilePath);
        Logger.i(TAG, "录音文件 resultFile: %s", filePath);


        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    public void stop() {
        if (state == RecordState.IDLE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }

        if (state == RecordState.PAUSE) {
            makeFile();
            state = RecordState.IDLE;
            notifyState();
            stopMp3Encoded();
        } else {
            state = RecordState.STOP;
            notifyState();
        }
    }

    void pause() {
        if (state != RecordState.RECORDING) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        state = RecordState.PAUSE;
        notifyState();
    }

    void resume() {
        if (state != RecordState.PAUSE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        String tempFilePath = getTempFilePath();
        Logger.i(TAG, "tmpPCM File: %s", tempFilePath);
        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    private void notifyState() {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onStateChange(state);
            }
        });

        if (state == RecordState.STOP || state == RecordState.PAUSE) {
            if (recordSoundSizeListener != null) {
                recordSoundSizeListener.onSoundSize(0);
            }
        }
    }

    private void notifyFinish() {
        Logger.d(TAG, "录音结束 file: %s", resultFile.getAbsolutePath());

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordStateListener != null) {
                    recordStateListener.onStateChange(RecordState.FINISH);
                }
                if (recordResultListener != null) {
                    recordResultListener.onResult(resultFile);
                }
            }
        });
    }

    private void notifyError(final String error) {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onError(error);
            }
        });
    }

    private FftFactory fftFactory = new FftFactory(FftFactory.Level.Original);

    private void notifyData(final byte[] data) {
        if (recordDataListener == null && recordSoundSizeListener == null && recordFftDataListener == null) {
            return;
        }
        mainHandler.post(() -> {
            if (recordDataListener != null) {
                recordDataListener.onData(data);
            }
            if (recordFftDataListener != null || recordSoundSizeListener != null) {
                byte[] fftData = fftFactory.makeFftData(data);
                if (fftData != null) {
                    if (recordSoundSizeListener != null) {
                        recordSoundSizeListener.onSoundSize(getDb(fftData));
                    }
                    if (recordFftDataListener != null) {
                        recordFftDataListener.onFftData(fftData);
                    }
                }
            }
        });
    }

    private int getDb(byte[] data) {
        double sum = 0;
        double ave;
        int length = Math.min(data.length, 128);
        int offsetStart = 0;
        for (int i = offsetStart; i < length; i++) {
            sum += data[i] * data[i];
        }
        ave = sum / (length - offsetStart);
        return (int) (Math.log10(ave) * 20);
    }

    private void initMp3EncoderThread(int bufferSize) {
        try {
            mp3EncodeThread = new Mp3EncodeThread(resultFile, bufferSize);
            mp3EncodeThread.start();
        } catch (Exception e) {
            Logger.e(e, TAG, e.getMessage());
        }
    }

    private class AudioRecordThread extends Thread {
        private AudioRecord audioRecord;
        private int bufferSize;

        byte[] noiceBuffer;

        AudioRecordThread() {
            bufferSize = AudioRecord.getMinBufferSize(currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig()) * RECORD_AUDIO_BUFFER_TIMES;
            Logger.d(TAG, "record buffer size = %s", bufferSize);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig(), bufferSize);
            noiceBuffer = new byte[bufferSize];
            if (currentConfig.getFormat() == RecordConfig.RecordFormat.MP3) {
                if (mp3EncodeThread == null) {
                    initMp3EncoderThread(bufferSize);
                } else {
                    Logger.e(TAG, "mp3EncodeThread != null, 请检查代码");
                }
            }
        }

        @Override
        public void run() {
            super.run();

            switch (currentConfig.getFormat()) {
                case MP3:
                    startMp3Recorder();
                    break;
                default:
                    startPcmRecorder();
                    break;
            }
        }

        // 类成员变量复用缓冲区（非线程安全）
        private final short[] mProcessInput = new short[160];  // 降噪输入缓冲
        private final short[] mNsOutput = new short[160];     // 降噪输出
        private final short[] mAgcOutput = new short[160];    // 增益输出
        // 类成员变量复用缓冲区（线程不安全，需确保单线程访问）
        private final short[] mInputShorts = new short[160];  // 输入缓冲
        private final byte[] mProcessBuffer = new byte[320]; // 字节转换缓冲

        private void startMp3Recorder() {
            state = RecordState.RECORDING;
            notifyState();
            Pair<Long, Long> result = getInitNoise();
            Long nsxId = result.first;
            Long agcId = result.second;
            try {
                audioRecord.startRecording();
                short[] rawBuffer = new short[bufferSize];  // 确保bufferSize是160的整数倍

                while (state == RecordState.RECORDING) {
                    int readCount = audioRecord.read(rawBuffer, 0, rawBuffer.length);
                    if (readCount > 0) {
                        notifyData(ByteUtils.toBytes(rawBuffer));
                        // 关键优化：原地降噪处理
                        processForMp3(rawBuffer, readCount, nsxId, agcId);

                        // 传递给MP3编码器
                        if (mp3EncodeThread != null) {
                            mp3EncodeThread.addChangeBuffer(
                                    new Mp3EncodeThread.ChangeBuffer(rawBuffer, readCount)
                            );
                        }
                    }
                }
                audioRecord.stop();
            } catch (Exception e) {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            } finally {
                // 释放资源
                nsUtils.nsxFree(nsxId);
                agcUtils.agcFree(agcId);
                if (state != RecordState.PAUSE) {
                    state = RecordState.IDLE;
                    notifyState();
                    stopMp3Encoded();
                } else {
                    Logger.d(TAG, "暂停");
                }
            }
        }

        /**
         * 执行实时降噪处理（原地修改原始缓冲区）
         */
        private void processForMp3(short[] buffer, int validLength, long nsxId, long agcId) {
            int processed = 0;
            while (processed < validLength) {
                int blockSize = Math.min(160, validLength - processed);

                // 1. 填充处理缓冲区（自动补零）
                System.arraycopy(buffer, processed, mProcessInput, 0, blockSize);
                if (blockSize < 160) {
                    Arrays.fill(mProcessInput, blockSize, 160, (short) 0);
                }

                // 2. 执行降噪和增益
                nsUtils.nsxProcess(nsxId, mProcessInput, 1, mNsOutput);
                agcUtils.agcProcess(agcId, mNsOutput, 1, 160, mAgcOutput, 0, 0, 0, false);
                // 3. 写回原始缓冲区（实现零拷贝）
                System.arraycopy(mAgcOutput, 0, buffer, processed, blockSize);
                processed += blockSize;
            }
        }

        private void startPcmRecorder() {
            state = RecordState.RECORDING;
            notifyState();
            Logger.d(TAG, "开始录制 Pcm");

            // 使用 BufferedOutputStream 提升写入性能（缓冲区大小8KB）
            try (BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(tmpFile), 8192)) {
                audioRecord.startRecording();
                Pair<Long, Long> result = getInitNoise();
                Long nsxId = result.first;
                Long agcId = result.second;
                byte[] byteBuffer = new byte[bufferSize]; // 主录音缓冲区
                while (state == RecordState.RECORDING) {
                    int readSize = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    notifyData(byteBuffer);
                    if (readSize > 0) {
                        processAudioData(byteBuffer, readSize, nsxId, agcId, bos);
                    }
                }

                audioRecord.stop();
                files.add(tmpFile);
                if (state == RecordState.STOP) {
                    makeFile();
                } else {
                    Logger.i(TAG, "暂停！");
                }
            } catch (Exception e) {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            } finally {
                // 状态重置与资源释放
                if (state != RecordState.PAUSE) {
                    state = RecordState.IDLE;
                    notifyState();
                    Logger.d(TAG, "录音结束");
                }
            }
        }

        private Pair<Long, Long> getInitNoise() {
            // 初始化音频处理器
            long nsxId = nsUtils.nsxCreate();
            nsUtils.nsxInit(nsxId, 16000);
            nsUtils.nsxSetPolicy(nsxId, 2);
            long agcId = agcUtils.agcCreate();
            agcUtils.agcInit(agcId, 0, 255, 3, 16000);
            agcUtils.agcSetConfig(agcId, (short) 9, (short) 9, true);
            return new Pair(nsxId, agcId);
        }

        /**
         * 处理音频数据块（支持非完整块）
         */
        private void processAudioData(byte[] buffer, int validLength,
                                      long nsxId, long agcId,
                                      BufferedOutputStream outputStream) throws IOException {
            int processed = 0;
            while (processed < validLength) {
                // 1. 计算当前块大小（支持末尾不完整块）
                int blockSize = Math.min(320, validLength - processed);
                int shortsToFill = blockSize / 2;

                // 2. 填充输入缓冲（自动补零）
                ByteBuffer.wrap(buffer, processed, blockSize)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(mInputShorts, 0, shortsToFill);
                if (shortsToFill < 160) {
                    Arrays.fill(mInputShorts, shortsToFill, 160, (short) 0);
                }

                // 3. 执行降噪和增益处理
                nsUtils.nsxProcess(nsxId, mInputShorts, 1, mNsOutput);
                agcUtils.agcProcess(agcId, mNsOutput, 1, 160, mAgcOutput, 0, 0, 0, false);

                // 4. 转换为字节（优化点：使用 ByteBuffer 替代手动转换）
                ByteBuffer.wrap(mProcessBuffer)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(mAgcOutput);

                // 5. 写入文件（只写入有效数据长度）
                outputStream.write(mProcessBuffer, 0, blockSize);
                processed += blockSize;
            }
        }
    }

    private void stopMp3Encoded() {
        if (mp3EncodeThread != null) {
            mp3EncodeThread.stopSafe(new Mp3EncodeThread.EncordFinishListener() {
                @Override
                public void onFinish() {
                    notifyFinish();
                    mp3EncodeThread = null;
                }
            });
        } else {
            Logger.e(TAG, "mp3EncodeThread is null, 代码业务流程有误，请检查！！ ");
        }
    }

    private void makeFile() {
        switch (currentConfig.getFormat()) {
            case MP3:
                return;
            case WAV:
                mergePcmFile();
                makeWav();
                break;
            case PCM:
                mergePcmFile();
                break;
            default:
                break;
        }
        notifyFinish();
        Logger.i(TAG, "录音完成！ path: %s ； 大小：%s", resultFile.getAbsoluteFile(), resultFile.length());
    }

    /**
     * 添加Wav头文件
     */
    private void makeWav() {
        if (!FileUtils.isFile(resultFile) || resultFile.length() == 0) {
            return;
        }
        byte[] header = WavUtils.generateWavFileHeader((int) resultFile.length(), currentConfig.getSampleRate(), currentConfig.getChannelCount(), currentConfig.getEncoding());
        WavUtils.writeHeader(resultFile, header);
    }

    /**
     * 合并文件
     */
    private void mergePcmFile() {
        boolean mergeSuccess = mergePcmFiles(resultFile, files);
        if (!mergeSuccess) {
            notifyError("合并失败");
        }
    }

    /**
     * 合并Pcm文件
     *
     * @param recordFile 输出文件
     * @param files      多个文件源
     * @return 是否成功
     */
    private boolean mergePcmFiles(File recordFile, List<File> files) {
        if (recordFile == null || files == null || files.size() <= 0) {
            return false;
        }

        FileOutputStream fos = null;
        BufferedOutputStream outputStream = null;
        byte[] buffer = new byte[1024];
        try {
            fos = new FileOutputStream(recordFile);
            outputStream = new BufferedOutputStream(fos);

            for (int i = 0; i < files.size(); i++) {
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(files.get(i)));
                int readCount;
                while ((readCount = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, readCount);
                }
                inputStream.close();
            }
        } catch (Exception e) {
            Logger.e(e, TAG, e.getMessage());
            return false;
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i).delete();
        }
        files.clear();
        return true;
    }

    /**
     * 根据当前的时间生成相应的文件名
     * 实例 record_20160101_13_15_12
     */
    private String getTempFilePath() {
        String fileDir = currentConfig.getRecordDir();
        if (!FileUtils.createOrExistsDir(fileDir)) {
            Logger.e(TAG, "文件夹创建失败：%s", fileDir);
        }
        String fileName = String.format(Locale.getDefault(), "record_tmp_%s", FileUtils.getNowString(new SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.SIMPLIFIED_CHINESE)));
        return String.format(Locale.getDefault(), "%s%s.pcm", fileDir, fileName);
    }

    /**
     * 表示当前状态
     */
    public enum RecordState {
        /**
         * 空闲状态
         */
        IDLE,
        /**
         * 录音中
         */
        RECORDING,
        /**
         * 暂停中
         */
        PAUSE,
        /**
         * 正在停止
         */
        STOP,
        /**
         * 录音流程结束（转换结束）
         */
        FINISH
    }

}
