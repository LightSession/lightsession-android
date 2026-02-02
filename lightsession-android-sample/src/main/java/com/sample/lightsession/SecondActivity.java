package com.sample.lightsession;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

public class SecondActivity extends AppCompatActivity {
    private View colorChangingView;
    private Handler colorChangeHandler = new Handler();
    private Random random = new Random();
    private Runnable colorChangeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_segunda);

        colorChangingView = findViewById(R.id.colorChangingViewSegunda);
        Button backButton = findViewById(R.id.backButton);
        Button goToComposeButton = findViewById(R.id.goToComposeButton);

        backButton.setOnClickListener(v -> {
            finish();

            // --- NOVO: Exemplo de rastreamento de evento ao voltar para a tela anterior ---
            // Map<String, Object> eventProperties = new HashMap<>();
            // eventProperties.put("action", "back_button_pressed");
            // eventProperties.put("from_screen", "SecondActivity");
            // LightSession.getInstance().trackEvent("Navigation Back", eventProperties);
            // --- FIM NOVO ---
        });

        // Botão para navegar para a tela em Jetpack Compose
        goToComposeButton.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(SecondActivity.this, ComposeWithNavigationActivity.class);
            startActivity(intent);
        });

        // Cria um Runnable para mudar a cor periodicamente (com cores diferentes da primeira tela)
        colorChangeRunnable = new Runnable() {
            @Override
            public void run() {
                // Gera uma cor aleatória com foco em tons mais claros
                int color = Color.rgb(
                        150 + random.nextInt(106),  // 150-255
                        150 + random.nextInt(106),
                        150 + random.nextInt(106)
                );
                colorChangingView.setBackgroundColor(color);

                // Agenda a próxima mudança de cor após 500ms (mais rápido que a primeira tela)
                colorChangeHandler.postDelayed(this, 500);
            }
        };

        // Inicia a animação de mudança de cor
        colorChangeHandler.post(colorChangeRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove callbacks para evitar vazamentos de memória
        colorChangeHandler.removeCallbacks(colorChangeRunnable);
    }
}