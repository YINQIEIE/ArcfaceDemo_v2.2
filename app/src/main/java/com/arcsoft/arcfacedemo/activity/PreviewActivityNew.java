package com.arcsoft.arcfacedemo.activity;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

import com.arcsoft.arcfacedemo.R;
import com.arcsoft.arcfacedemo.fragment.PreviewFragement;

public class PreviewActivityNew extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview_new);
        getSupportFragmentManager().beginTransaction().add(R.id.flContainer, new PreviewFragement()).commit();
    }
}
