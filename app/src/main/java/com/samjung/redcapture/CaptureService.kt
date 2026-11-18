package com.samjung.redcapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 접근성 서비스 하나로 세 가지를 처리한다.
 *  1) 화면 오른쪽에 빨간 버튼 띄우기 (다른 앱 위에 표시)
 *  2) 지금 화면에 떠 있는 앱과 세부 화면(액티비티) 알아내기
 *  3) 버튼을 누르면 화면 캡처 → 사진첩(Pictures/RedCapture)에 저장
 */
class CaptureService : AccessibilityService() {

    companion object {
        const val SAVE_FOLDER = "RedCapture"   // 사진첩 > Pictures/RedCapture
        var isRunning = false
    }

    private lateinit var wm: WindowManager
    private var button: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())

    // 마지막으로 화면에 뜬 앱/액티비티
    private var lastPkg: String? = null
    private var lastClass: String? = null
    private var busy = false

    override fun onServiceConnected() {
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addButton()
    }

    override fun onDestroy() {
        isRunning = false
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val cls = e.className?.toString() ?: return
        // 액티비티(화면)가 바뀐 경우에만 세부 카테고리로 기억 (팝업·레이아웃 이벤트는 무시)
        if (isActivity(pkg, cls)) {
            lastPkg = pkg
            lastClass = cls
        } else if (pkg != lastPkg) {
            lastPkg = pkg
            lastClass = null
        }
    }

    // ───────────────────── 빨간 버튼 ─────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = dp(56)
        val metrics = resources.displayMetrics
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - size - dp(6)   // 화면 오른쪽 끝
            y = (metrics.heightPixels * 0.45).toInt()
        }

        val v = View(this).apply {
            setBackgroundResource(R.drawable.red_button)
            alpha = 0.85f
            elevation = dp(6).toFloat()
            contentDescription = "화면 캡처"
        }

        // 짧게 누르면 캡처, 끌면 위치 이동
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y; moved = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.85f
                    if (moved) snapToEdge(v) else if (ev.action == MotionEvent.ACTION_UP) capture()
                }
            }
            true
        }
        wm.addView(v, params)
        button = v
    }

    /** 드래그 후 가까운 좌/우 가장자리에 붙인다 */
    private fun snapToEdge(v: View) {
        val w = resources.displayMetrics.widthPixels
        val size = dp(56)
        params.x = if (params.x + size / 2 > w / 2) w - size - dp(6) else dp(6)
        wm.updateViewLayout(v, params)
    }

    // ───────────────────── 캡처 ─────────────────────

    private fun capture() {
        if (busy) return
        busy = true

        // 캡처 순간의 앱 정보를 먼저 확보
        val (appName, category) = currentAppAndCategory()

        // 버튼이 사진에 찍히지 않도록 잠깐 숨김
        button?.visibility = View.INVISIBLE
        main.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        button?.visibility = View.VISIBLE
                        val hw = result.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (bmp == null) { done("캡처 실패"); return }
                        Thread {
                            val name = save(bmp, appName, category)
                            bmp.recycle()
                            main.post { done(if (name != null) "저장됨: $name" else "저장 실패") }
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        button?.visibility = View.VISIBLE
                        done(
                            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                                "너무 빨리 눌렀어요. 1초 뒤 다시 눌러 주세요."
                            else "캡처 실패 (코드 $errorCode)"
                        )
                    }
                })
        }, 150)
    }

    private fun done(msg: String) {
        busy = false
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 지금 화면에 보이는 앱 이름과 세부 카테고리(화면 이름) */
    private fun currentAppAndCategory(): Pair<String, String> {
        // 1순위: 현재 활성화된 앱 창
        val appWindow = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .let { list -> list.firstOrNull { it.isActive || it.isFocused } ?: list.firstOrNull() }
        }.getOrNull()

        var pkg = runCatching { appWindow?.root?.packageName?.toString() }.getOrNull()
        if (pkg == null || pkg == packageName) pkg = lastPkg
        if (pkg == null) return "알수없음" to "기본"

        val appName = appLabel(pkg)

        // 세부 카테고리: 액티비티 라벨 → 액티비티 클래스 이름 → 창 제목 → "기본"
        var category: String? = null
        val cls = if (pkg == lastPkg) lastClass else null
        if (cls != null) {
            val actLabel = runCatching {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                    .loadLabel(packageManager).toString()
            }.getOrNull()
            category = if (!actLabel.isNullOrBlank() && actLabel != appName) actLabel
                       else shortClassName(cls)
        }
        if (category.isNullOrBlank()) {
            val title = runCatching { appWindow?.title?.toString() }.getOrNull()
            if (!title.isNullOrBlank() && title != appName) category = title
        }
        return appName to (category?.takeIf { it.isNotBlank() } ?: "기본")
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun isActivity(pkg: String, cls: String): Boolean = runCatching {
        packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true
    }.getOrDefault(false)

    /** com.example.app.MainSettingsActivity → MainSettings */
    private fun shortClassName(cls: String): String {
        val s = cls.substringAfterLast('.').substringAfterLast('$')
        val trimmed = s.removeSuffix("Activity")
        return trimmed.ifBlank { s }
    }

    // ───────────────────── 저장 ─────────────────────

    /** 파일 이름 형식: 앱이름-세부카테고리-yyyyMMddHHmmss.png */
    private fun save(bmp: Bitmap, appName: String, category: String): String? {
        val time = SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA).format(Date())
        val fileName = "${clean(appName)}-${clean(category)}-$time.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            fileName
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** 파일 이름에 쓸 수 없는 문자와 구분자(-)를 정리 */
    private fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "")
            .replace("-", "_")
            .trim()
            .take(40)
            .ifBlank { "기본" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
