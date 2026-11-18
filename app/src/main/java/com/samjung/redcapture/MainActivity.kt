package com.samjung.redcapture

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 설정 안내 화면: 접근성 권한만 켜면 빨간 버튼이 나타난다 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "빨간버튼 캡처"
            textSize = 26f
            setTextColor(Color.parseColor("#E53935"))
        })

        status = TextView(this).apply {
            textSize = 17f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            text = """
                ■ 처음 한 번만 설정
                1. 아래 [접근성 설정 열기] → '설치된 앱' → '빨간버튼 캡처' → 켜기
                2. 스위치가 회색으로 막혀 있으면:
                   [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 1번 다시

                ■ 사용법
                · 화면 오른쪽 빨간 버튼을 톡 누르면 캡처
                · 버튼을 끌면 위치 이동 (좌/우 가장자리에 붙음)

                ■ 저장 위치·이름
                · 갤러리 > 앨범 'RedCapture' (Pictures/RedCapture)
                · 앱이름-세부카테고리-연월일시분초.png
                  예) 카카오톡-Chat-20261118213045.png
            """.trimIndent()
        })

        root.addView(Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "앱 정보 열기 (제한된 설정 허용)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        })
        root.gravity = Gravity.TOP
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (CaptureService.isRunning)
            "✅ 작동 중 — 화면 오른쪽 빨간 버튼을 누르세요."
        else
            "⚠️ 아직 꺼져 있어요. 아래 순서대로 켜 주세요."
    }
}
