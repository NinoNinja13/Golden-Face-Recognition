package com.atharvakale.facerecognition;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

public class splash_screen extends AppCompatActivity {
    private ImmersiveUi goldenImmersiveUi;
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (goldenImmersiveUi != null) goldenImmersiveUi.onFocusChanged(hasFocus);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        goldenImmersiveUi = new ImmersiveUi(this);
        setContentView(R.layout.activity_splash_screen);
        Handler handler=new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent intent =new Intent(splash_screen.this,MainActivity.class);
                finish();
                startActivity(intent);
            }
        },2500);
    }
}