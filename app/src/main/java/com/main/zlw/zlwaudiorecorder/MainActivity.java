package com.main.zlw.zlwaudiorecorder;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.main.zlw.zlwaudiorecorder.base.MyApp;
import com.yanzhenjie.permission.AndPermission;
import com.yanzhenjie.permission.runtime.Permission;
import com.zlw.loggerlib.Logger;
import com.zlw.main.recorderlib.RecordManager;
import com.zlw.main.recorderlib.recorder.RecordConfig;
import com.zlw.main.recorderlib.recorder.RecordHelper;
import com.zlw.main.recorderlib.recorder.listener.RecordFftDataListener;
import com.zlw.main.recorderlib.recorder.listener.RecordResultListener;
import com.zlw.main.recorderlib.recorder.listener.RecordSoundSizeListener;
import com.zlw.main.recorderlib.recorder.listener.RecordStateListener;

import java.io.File;
import java.util.Locale;


public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    Button btRecord;
    Button btStop;
    TextView tvState;
    TextView tvSoundSize;
    RadioGroup rgAudioFormat;
    RadioGroup rgSimpleRate;
    RadioGroup tbEncoding;
    RadioGroup tbNoice;
    RadioGroup tbSource;
    RadioButton rbMobileAndExt;
    RadioButton tbExt;
    AudioView audioView;
    Spinner spUpStyle;
    Spinner spDownStyle;

    private boolean isStart = false;
    private boolean isPause = false;
    final RecordManager recordManager = RecordManager.getInstance();
    private static final String[] STYLE_DATA = new String[]{"STYLE_ALL", "STYLE_NOTHING", "STYLE_WAVE", "STYLE_HOLLOW_LUMP"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        initAudioView();
        initEvent();
        initRecord();
        AndPermission.with(this)
                .runtime()
                .permission(new String[]{Permission.READ_EXTERNAL_STORAGE, Permission.WRITE_EXTERNAL_STORAGE,
                        Permission.RECORD_AUDIO})
                .start();
    }

    private void initView() {
        btRecord = findViewById(R.id.btRecord);
        btStop = findViewById(R.id.btStop);
        tvState = findViewById(R.id.tvState);
        tvSoundSize = findViewById(R.id.tvSoundSize);
        rgAudioFormat = findViewById(R.id.rgAudioFormat);
        rgSimpleRate = findViewById(R.id.rgSimpleRate);
        tbEncoding = findViewById(R.id.tbEncoding);
        tbNoice = findViewById(R.id.tbNoice);
        audioView = findViewById(R.id.audioView);
        spUpStyle = findViewById(R.id.spUpStyle);
        spDownStyle = findViewById(R.id.spDownStyle);
        tbSource = findViewById(R.id.tbSource);
        rbMobileAndExt = findViewById(R.id.rbMobileAndExt);
        tbExt = findViewById(R.id.tbExt);
    }

    @Override
    protected void onResume() {
        super.onResume();
        doStop();
        initRecordEvent();
    }

    @Override
    protected void onStop() {
        super.onStop();
        doStop();
    }

    private void initAudioView() {
        tvState.setVisibility(View.GONE);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, STYLE_DATA);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUpStyle.setAdapter(adapter);
        spDownStyle.setAdapter(adapter);
        spUpStyle.setOnItemSelectedListener(this);
        spDownStyle.setOnItemSelectedListener(this);
    }

    private void initEvent() {
        rgAudioFormat.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rbPcm:
                        recordManager.changeFormat(RecordConfig.RecordFormat.PCM);
                        break;
                    case R.id.rbMp3:
                        recordManager.changeFormat(RecordConfig.RecordFormat.MP3);
                        break;
                    case R.id.rbWav:
                        recordManager.changeFormat(RecordConfig.RecordFormat.WAV);
                        break;
                    default:
                        break;
                }
            }
        });

        rgSimpleRate.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rb8K:
                        recordManager.changeRecordConfig(recordManager.getRecordConfig().setSampleRate(8000));
                        break;
                    case R.id.rb16K:
                        recordManager.changeRecordConfig(recordManager.getRecordConfig().setSampleRate(16000));
                        break;
                    case R.id.rb44K:
                        recordManager.changeRecordConfig(recordManager.getRecordConfig().setSampleRate(44100));
                        break;
                    default:
                        break;
                }
            }
        });

        tbEncoding.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.rb8Bit:
                    recordManager.changeRecordConfig(recordManager.getRecordConfig().setEncodingConfig(AudioFormat.ENCODING_PCM_8BIT));
                    break;
                case R.id.rb16Bit:
                    recordManager.changeRecordConfig(recordManager.getRecordConfig().setEncodingConfig(AudioFormat.ENCODING_PCM_16BIT));
                    break;
                default:
                    break;
            }
        });

        tbNoice.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.rbDeNoise:
                    recordManager.setDenoise(true);
                    break;
                case R.id.rbNoDenoise:
                    recordManager.setDenoise(false);
                    break;
                default:
                    break;
            }
        });

        final boolean[] isUserChecked = {false};

        @SuppressLint("ClickableViewAccessibility") View.OnTouchListener touchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                isUserChecked[0] = true;
            }
            return false; // 返回 false，让点击事件继续传递
        };

        // 假设你的按钮是这两个
        findViewById(R.id.rbMobileAndExt).setOnTouchListener(touchListener);
        findViewById(R.id.tbExt).setOnTouchListener(touchListener);

        tbSource.setOnCheckedChangeListener((group, checkedId) -> {
            if (!isUserChecked[0]) {
                // 是代码触发，不做处理
                return;
            }
            isUserChecked[0] = false; // 重置标记位

            Log.i("yyyyyy", "✅ 用户点击触发 checkedId=" + checkedId);

            switch (checkedId) {
                case R.id.rbMobileAndExt:
                    if (recordManager.getAudioSource() == MediaRecorder.AudioSource.MIC) {
                        tbSource.check(R.id.tbExt);
                        return;
                    }
                    if (isRecording()) {
                        tbSource.check(R.id.tbExt);
                        Toast.makeText(MainActivity.this, "录音过程中无法切换音频源", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    recordManager.setAudioSource(MediaRecorder.AudioSource.MIC);
                    break;

                case R.id.tbExt:
                    if (recordManager.getAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                        tbSource.check(R.id.rbMobileAndExt);
                        return;
                    }
                    if (isRecording()) {
                        tbSource.check(R.id.rbMobileAndExt);
                        Toast.makeText(MainActivity.this, "录音过程中无法切换音频源", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    recordManager.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                    break;

                default:
                    break;
            }
        });
    }

    public boolean isRecording() {
        return recordManager.getState() == RecordHelper.RecordState.RECORDING;
    }

    private void initRecord() {
        recordManager.init(MyApp.getInstance(), BuildConfig.DEBUG);
        recordManager.changeFormat(RecordConfig.RecordFormat.WAV);
        String recordDir = String.format(Locale.getDefault(), "%s/Record/com.zlw.main/",
                Environment.getExternalStorageDirectory().getAbsolutePath());
        recordManager.changeRecordDir(recordDir);
        initRecordEvent();
    }

    private void initRecordEvent() {
        recordManager.setRecordStateListener(new RecordStateListener() {
            @Override
            public void onStateChange(RecordHelper.RecordState state) {
                Logger.i(TAG, "onStateChange %s", state.name());

                switch (state) {
                    case PAUSE:
                        tvState.setText("暂停中");
                        break;
                    case IDLE:
                        tvState.setText("空闲中");
                        break;
                    case RECORDING:
                        tvState.setText("录音中");
                        break;
                    case STOP:
                        tvState.setText("停止");
                        break;
                    case FINISH:
                        tvState.setText("录音结束");
                        tvSoundSize.setText("---");
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onError(String error) {
                Logger.i(TAG, "onError %s", error);
            }
        });
        recordManager.setRecordSoundSizeListener(new RecordSoundSizeListener() {
            @Override
            public void onSoundSize(int soundSize) {
                tvSoundSize.setText(String.format(Locale.getDefault(), "声音大小：%s db", soundSize));
            }
        });
        recordManager.setRecordResultListener(new RecordResultListener() {
            @Override
            public void onResult(File result) {
                Toast.makeText(MainActivity.this, "录音文件： " + result.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            }
        });
        recordManager.setRecordFftDataListener(new RecordFftDataListener() {
            @Override
            public void onFftData(byte[] data) {
                audioView.setWaveData(data);
            }
        });
    }

    public void onClicked(View view) {
        switch (view.getId()) {
            case R.id.btRecord:
                doPlay();
                break;
            case R.id.btStop:
                doStop();
                break;
            default:
                break;
        }
    }

    private void doStop() {
        recordManager.stop();
        btRecord.setText("开始");
        isPause = false;
        isStart = false;
    }

    private void doPlay() {
        if (isStart) {
            recordManager.pause();
            btRecord.setText("开始");
            isPause = true;
            isStart = false;
        } else {
            if (isPause) {
                recordManager.resume();
            } else {
                recordManager.start();
            }
            btRecord.setText("暂停");
            isStart = true;
        }
    }


    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.spUpStyle:
                audioView.setStyle(AudioView.ShowStyle.getStyle(STYLE_DATA[position]), audioView.getDownStyle());
                break;
            case R.id.spDownStyle:
                audioView.setStyle(audioView.getUpStyle(), AudioView.ShowStyle.getStyle(STYLE_DATA[position]));
                break;
            default:
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        //nothing
    }
}
