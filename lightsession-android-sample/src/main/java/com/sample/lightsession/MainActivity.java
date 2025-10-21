package com.sample.lightsession;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private View colorChangingView;
    private Handler colorChangeHandler = new Handler();
    private Random random = new Random();
    private Runnable colorChangeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button toggleButton = findViewById(R.id.capture_button);
        Button goToSecondButton = findViewById(R.id.go_to_second_button);
        Button identifyUserButton = findViewById(R.id.identify_user_button);
        Button trackEventButton = findViewById(R.id.track_event_button);
        Button crashButton = findViewById(R.id.crash_button);
        Button softErrorButton = findViewById(R.id.soft_error_button);

        colorChangingView = findViewById(R.id.colorChangingView);

        goToSecondButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SecondActivity.class);
            startActivity(intent);
        });

        toggleButton.setOnClickListener(v -> {

        });

        identifyUserButton.setOnClickListener(v -> {
            String distinctId = "user_12345";
            Map<String, Object> userProperties = new HashMap<>();
            userProperties.put("name", "John Doe");
            userProperties.put("email", "john.doe@example.com");
            userProperties.put("subscription_tier", "premium");
            Log.d("MainActivity", "User identified: " + distinctId);
        });

        trackEventButton.setOnClickListener(v -> {
            Map<String, Object> eventProperties = new HashMap<>();
            eventProperties.put("button_name", "Track Event Button");
            eventProperties.put("screen_name", "MainActivity");
            eventProperties.put("interaction_type", "click");
            Log.d("MainActivity", "Custom event 'Custom Button Click' tracked.");
        });

        // --- NOVO: Listener para o botão de CRASH ---
        crashButton.setOnClickListener(v -> {
            Log.e("MainActivity", "Simulating app crash...");
            // Esta linha irá causar um NullPointerException e o aplicativo irá quebrar.
            // O LightSessionExceptionHandler deverá capturar isso.
            String nullString = null;
            nullString.length(); // Isso vai gerar um NullPointerException
        });
        // --- FIM NOVO ---

        // --- NOVO: Listener para o botão de ERRO SOFT ---
        softErrorButton.setOnClickListener(v -> {
            try {
                Log.w("MainActivity", "Simulating a soft error (handled exception)...");
                // Simula uma divisão por zero que é capturada.
                int result = 10 / 0;
            } catch (ArithmeticException e) {
                // Aqui você pode criar um breadcrumb customizado para o erro
                Map<String, Object> errorProperties = new HashMap<>();
                errorProperties.put("type", "handled_error");
                errorProperties.put("message", "Attempted division by zero.");
                errorProperties.put("details", e.getMessage());
                errorProperties.put("screen", "MainActivity");
                Log.e("MainActivity", "Soft error caught: " + e.getMessage());
            }
        });
        // --- FIM NOVO ---


        // Cria um Runnable para mudar a cor periodicamente
        colorChangeRunnable = new Runnable() {
            @Override
            public void run() {
                // Gera uma cor aleatória
                int color = Color.rgb(
                        random.nextInt(256),
                        random.nextInt(256),
                        random.nextInt(256)
                );
                colorChangingView.setBackgroundColor(color);

                // Agenda a próxima mudança de cor após 1 segundo
                colorChangeHandler.postDelayed(this, 1000);
            }
        };

        // Inicia a animação de mudança de cor
        colorChangeHandler.post(colorChangeRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        colorChangeHandler.removeCallbacks(colorChangeRunnable);
    }
}