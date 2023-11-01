/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenrecord;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.NONE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;
import android.content.Intent;

import com.android.systemui.R;
import com.android.systemui.settings.CurrentUserContextTracker;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import android.content.SharedPreferences;
import android.util.Slog;



/**
 * Activity to select screen recording options
 */
public class ScreenRecordDialog extends Activity {
    private static final long DELAY_MS = 3000;
    private static final long INTERVAL_MS = 1000;
    private static final String TAG = "ScreenRecordDialog";

    private final RecordingController mController;
    private final CurrentUserContextTracker mCurrentUserContextTracker;
    private Switch mTapsSwitch;
    private Switch mAudioSwitch;
    private Spinner mOptions;
    private List<ScreenRecordingAudioSource> mModes;
    private SharedPreferences mSharedPreferences = null;

    @Inject
    public ScreenRecordDialog(RecordingController controller,
            CurrentUserContextTracker currentUserContextTracker) {
        mController = controller;
        mCurrentUserContextTracker = currentUserContextTracker;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        // Inflate the decor view, so the attributes below are not overwritten by the theme.
        window.getDecorView();
        window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
        window.setGravity(Gravity.CENTER);
        setTitle(R.string.screenrecord_name);

        setContentView(R.layout.screen_record_dialog);

        try{
            mSharedPreferences = getSharedPreferences("ScreenRecordPrefs",Context.MODE_PRIVATE);
        }catch(Exception e){
            Slog.w(TAG,"getSharedPreferences error: " + e);
        }

        Button cancelBtn = findViewById(R.id.button_cancel);
        cancelBtn.setOnClickListener(v -> {
            finish();
        });

        Button startBtn = findViewById(R.id.button_start);
        LinearLayout optionsLayout = findViewById(R.id.screen_recording_options_parent);
        TextView recordDescription = findViewById(R.id.screen_record_description);
        TextView recordState = findViewById(R.id.screen_recording_state);

        if (mController.isRecording()) {
            optionsLayout.setVisibility(View.GONE);
            //recordDescription.setVisibility(View.GONE);
            recordState.setText(R.string.screenrecord_ongoing);
            startBtn.setText(R.string.screenrecord_stop);
        }else{
            optionsLayout.setVisibility(View.VISIBLE);
            //recordDescription.setVisibility(View.VISIBLE);
            recordState.setText(R.string.screenrecord_start_label);
            startBtn.setText(R.string.screenrecord_start);
        }

        startBtn.setOnClickListener(v -> {
            if (mController.isRecording()) {
                mController.stopRecording();
            }else{
                requestScreenCapture();
            }
            finish();
        });

        mModes = new ArrayList<>();
        mModes.add(MIC);
        mModes.add(INTERNAL);
        mModes.add(MIC_AND_INTERNAL);

        mAudioSwitch = findViewById(R.id.screenrecord_audio_switch);
        mTapsSwitch = findViewById(R.id.screenrecord_taps_switch);
        mOptions = findViewById(R.id.screen_recording_options);
        ArrayAdapter a = new ScreenRecordingAdapter(getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                mModes);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mOptions.setAdapter(a);
        mOptions.setOnItemClickListenerInt((parent, view, position, id) -> {
            mAudioSwitch.setChecked(true);
            if(mSharedPreferences != null){
                SharedPreferences.Editor editor = mSharedPreferences.edit();
                editor.putBoolean("audio_switch", true);
                editor.putInt("options", position);
                editor.apply();
                Slog.w(TAG,"audio_switch seted true");
                Slog.w(TAG,"options seted " + position);
            }
        });
        mOptions.setSelection(1);
        mAudioSwitch.setChecked(true);
        mAudioSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isChecked = mAudioSwitch.isChecked();
                if(mSharedPreferences != null){
                    SharedPreferences.Editor editor = mSharedPreferences.edit();
                    editor.putBoolean("audio_switch", isChecked);
                    editor.apply();
                    Slog.w(TAG,"audio_switch seted " + isChecked);
                }
            }
        });

        if(mSharedPreferences != null){
            boolean isChecked = mSharedPreferences.getBoolean("audio_switch",true);
            mAudioSwitch.setChecked(isChecked);
            int option = mSharedPreferences.getInt("options",1);
            mOptions.setSelection(option);
            Slog.w(TAG,"get audio_switch value: " + isChecked);
            Slog.w(TAG,"get option value: " + option);
        }

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if(bundle != null){
            String sender = bundle.getString("sender");
            if(sender != null && sender.equals("PhoneWindowManager")){
                if (mController.isRecording()) {
                    mController.stopRecording();
                }else{
                    requestScreenCapture();
                }
                finish();
            }
        }
    }

    private void requestScreenCapture() {
        Context userContext = mCurrentUserContextTracker.getCurrentUserContext();
        boolean showTaps = mTapsSwitch.isChecked();
        ScreenRecordingAudioSource audioMode = mAudioSwitch.isChecked()
                ? (ScreenRecordingAudioSource) mOptions.getSelectedItem()
                : NONE;
        PendingIntent startIntent = PendingIntent.getForegroundService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                        userContext, RESULT_OK,
                        audioMode.ordinal(), showTaps),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mController.startCountdown(DELAY_MS, INTERVAL_MS, startIntent, stopIntent);
    }
}
