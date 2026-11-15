package com.spektra.android

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 取景器画布（r69 重写）。
 *
 * ## 方向：为什么这一版不会再转错
 *
 * r68 把旋转写在顶点着色器的 uv 上，结果在 ColorOS 上预览躺了 90°。原因是**两处旋转在打架**：
 * HAL 给 `SurfaceTexture` 的变换矩阵本身就带了旋转（部分机型会把预览流预旋到显示方向），
 * 我们又叠了一次自己的 90°，两者互相抵掉，于是画面看着"没转"。
 *
 * 这一版做了三件事：
 *  1. 顶点着色器只画一个铺满屏幕的矩形（`gl_VertexID` 取点，没有属性、没有 uv 插值）；
 *  2. **方向全部在片元着色器里算**：屏幕坐标 → 画面矩形内坐标 → 按角度转回图像坐标 → uv。
 *     一条公式、一处符号，不再有"顶点转一次、uv 再转一次"的叠加错误；
 *  3. 自动角度会**扣掉 HAL 已经做过的那部分**（从 `getTransformMatrix` 里反解），
 *     并把解出来的数字写进日志。万一某台机器的矩阵不是纯旋转，设置页里还有手动档
 *     （自动 / 0° / 90° / 180° / 270° + 镜像），改一次就记住。
 *
 * ## 色彩：一次烘焙 + 每帧查表
 *
 * 相机帧走 `SurfaceTexture`（外部纹理，零拷贝）进来，每帧只做"查 33³ 色表 + 加质感"。
 * 引擎不参与逐帧渲染（一帧几十到上百毫秒），它只在换胶片时烘一张表，见 [CamLut]。
 *
 * 胶片的空间相关特性（光晕、眩光）着色器里用亮部模糊近似 —— 它们在真胶片上是**光在乳剂层里
 * 横向散射**，不是逐像素的曲线，色表里表达不了。颗粒与暗角同理。所以取景器是
 * "方向、影调、色彩都对，质感是按同一套参数近似的预览"，成片仍由主引擎精修。
 */
class CamGlView(ctx: Context) : GLSurfaceView(ctx) {

    val renderer = Renderer()

    /** 预览 Surface 就绪。GL 上下文是在挂上窗口之后才建的，构造完这个 View 之后等回调即可。 */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** 相机侧拿这个 Surface 当预览目标；未就绪返回 null。 */
    fun previewSurface(): Surface? = renderer.surface

    /**
     * 相机缓冲的真实尺寸。`SurfaceTexture` 的默认缓冲尺寸必须与相机输出一致，
     * 否则驱动每次都会做一次缩放拷贝（掉一半帧率）。
     */
    fun setPreviewSize(w: Int, h: Int) = queueEvent { renderer.setBufferSize(w, h) }

    /**
     * 画面方向。
     *
     * @param deg 希望画面顺时针转多少度才是正的；-1 表示"用手动档"（[setManual]）。
     * @param mirror 是否左右镜像（前摄预览）。
     */
    fun setOrientation(deg: Int, mirror: Boolean) = queueEvent { renderer.setOrientation(deg, mirror) }

    /** 手动方向档：0/90/180/270；负数表示回到自动。 */
    fun setManual(deg: Int, mirror: Boolean) = queueEvent { renderer.setManual(deg, mirror) }

    fun setLut(lut: ByteArray?) = queueEvent { renderer.setLut(lut) }

    /** 胶片强度与三种质感（0..1）。 */
    fun setLook(mix: Float, grain: Float, halation: Float, glow: Float, vignette: Float) =
        queueEvent {
            renderer.mix = mix
            renderer.grain = grain
            renderer.halation = halation
            renderer.glow = glow
            renderer.vignette = vignette
            requestRender()
        }

    inner class Renderer : GLSurfaceView.Renderer {

        @Volatile var surface: Surface? = null
            private set

        private var texId = 0
        private var tex: SurfaceTexture? = null
        private var prog = 0
        private var lutTex = 0
        private var lutReady = false
        private var pendingLut: ByteArray? = null

        private var bufW = 1440
        private var bufH = 1080
        private var viewW = 1
        private var viewH = 1

        /** 希望顺时针转的角度（自动算出来的，未扣 HAL 部分）。 */
        @Volatile private var wantDeg = 0
        @Volatile private var manualDeg = -1
        @Volatile private var mirrorFlag = false

        /** HAL 在变换矩阵里已经转过的角度（采样旋转，顺时针为正）。 */
        @Volatile private var halDeg = 0
        @Volatile private var halDet = 1f

        /** 画面矩形在屏幕上的半宽半高（0..1）。界面点按对焦要用它做坐标换算。 */
        @Volatile private var halfX = 1f
        @Volatile private var halfY = 1f

        fun imageHalf(): Pair<Float, Float> = halfX to halfY

        var mix = 1f
        var grain = 0.022f
        var halation = 0.35f
        var glow = 0.18f
        var vignette = 0.16f

        private var uSrc = 0
        private var uLut = 0
        private var uTexM = 0
        private var uRot = 0
        private var uHalf = 0
        private var uView = 0
        private var uMirror = 0
        private var uMix = 0
        private var uGrain = 0
        private var uHal = 0
        private var uGlow = 0
        private var uVig = 0
        private var uSeed = 0
        private var uInv = 0

        private val texMatrix = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            prog = buildProgram(VERT, FRAG)
            uSrc = GLES30.glGetUniformLocation(prog, "uSrc")
            uLut = GLES30.glGetUniformLocation(prog, "uLut")
            uTexM = GLES30.glGetUniformLocation(prog, "uTexM")
            uRot = GLES30.glGetUniformLocation(prog, "uRot")
            uHalf = GLES30.glGetUniformLocation(prog, "uHalf")
            uView = GLES30.glGetUniformLocation(prog, "uView")
            uMirror = GLES30.glGetUniformLocation(prog, "uMirror")
            uMix = GLES30.glGetUniformLocation(prog, "uMix")
            uGrain = GLES30.glGetUniformLocation(prog, "uGrain")
            uHal = GLES30.glGetUniformLocation(prog, "uHalation")
            uGlow = GLES30.glGetUniformLocation(prog, "uGlow")
            uVig = GLES30.glGetUniformLocation(prog, "uVignette")
            uSeed = GLES30.glGetUniformLocation(prog, "uSeed")
            uInv = GLES30.glGetUniformLocation(prog, "uInv")

            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(bufW, bufH)
            st.setOnFrameAvailableListener { requestRender() }
            tex = st
            surface = Surface(st)
            onSurfaceReady?.invoke(surface!!)

            // context 丢了要重建：已经上传过的表重来一遍，否则取景器会突然变回原色
            lutReady = false
            pendingLut?.let { uploadLut(it) }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            viewW = w.coerceAtLeast(1)
            viewH = h.coerceAtLeast(1)
            GLES30.glViewport(0, 0, viewW, viewH)
            requestRender()
        }

        fun setBufferSize(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            bufW = w
            bufH = h
            tex?.setDefaultBufferSize(w, h)
            requestRender()
        }

        fun setOrientation(deg: Int, mir: Boolean) {
            wantDeg = ((deg % 360) + 360) % 360
            mirrorFlag = mir
            logState("auto")
            requestRender()
        }

        fun setManual(deg: Int, mir: Boolean) {
            manualDeg = deg
            mirrorFlag = mir
            logState(if (deg < 0) "auto" else "manual=$deg")
            requestRender()
        }

        private fun logState(tag: String) {
            Diagnostics.log(
                "cam",
                "取景方向 $tag want=$wantDeg hal=$halDeg det=$halDet → applied=${appliedDeg()} " +
                    "mirror=$mirrorFlag buf=${bufW}x$bufH view=${viewW}x$viewH"
            )
        }

        /** 真正施加的角度：手动档优先，否则扣掉 HAL 已经做过的那部分。 */
        fun appliedDeg(): Int =
            if (manualDeg >= 0) manualDeg else ((wantDeg - halDeg) % 360 + 360) % 360

        fun setLut(lut: ByteArray?) {
            pendingLut = lut
            if (lut == null) lutReady = false else uploadLut(lut)
            requestRender()
        }

        private fun uploadLut(lut: ByteArray) {
            if (lutTex == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                lutTex = ids[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            // 三线性 + CLAMP：表边沿是最黑/最白，夹住才不会在超范围输入上采样到对侧
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            val n = CamLut.N
            val buf = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder())
            buf.put(lut).position(0)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, n, n, n, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
            )
            lutReady = true
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val st = tex ?: return
            if (prog == 0) return

            // 必须在 GL 线程上取帧；否则会拿到一张过期纹理或者直接崩
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            } catch (t: Throwable) {
                return
            }
            // HAL 已经把画面转过多少，就从我们要转的里扣掉 —— 每帧重读一次，
            // 因为切前后摄/切镜头时 HAL 的矩阵会变（见 logState 的 hal=）。
            readHalRotation()

            val deg = appliedDeg()
            val odd = (deg % 180) != 0
            // 画面在屏幕上的矩形：按"转过之后的宽高"做 contain，不裁不拉
            val rotW = if (odd) bufH else bufW
            val rotH = if (odd) bufW else bufH
            val s = minOf(viewW.toFloat() / rotW, viewH.toFloat() / rotH)
            halfX = rotW * s / viewW
            halfY = rotH * s / viewH

            GLES30.glUseProgram(prog)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES30.glUniform1i(uSrc, 0)
            GLES30.glUniformMatrix4fv(uTexM, 1, false, texMatrix, 0)

            val rad = Math.toRadians(deg.toDouble())
            val cs = Math.cos(rad).toFloat()
            val sn = Math.sin(rad).toFloat()
            // 屏幕坐标 → 图像坐标：把画面顺时针转过的角度转回去
            GLES30.glUniformMatrix2fv(uRot, 1, false, floatArrayOf(cs, sn, -sn, cs), 0)
            GLES30.glUniform2f(uHalf, halfX, halfY)
            GLES30.glUniform2f(uView, viewW.toFloat(), viewH.toFloat())
            GLES30.glUniform2f(uInv, 1f / bufW, 1f / bufH)
            GLES30.glUniform1f(uMirror, if (mirrorFlag) 1f else 0f)
            // 表还没上传时 mix 必须归零：否则 texture(uLut,…) 会去采单元 0（那是相机纹理），
            // 结果是整屏变成相机原色的"色块"，比不上色更难看。
            GLES30.glUniform1f(uMix, if (lutReady) mix.coerceIn(0f, 1f) else 0f)
            GLES30.glUniform1f(uGrain, grain.coerceIn(0f, 0.2f))
            GLES30.glUniform1f(uHal, halation.coerceIn(0f, 1f))
            GLES30.glUniform1f(uGlow, glow.coerceIn(0f, 1f))
            GLES30.glUniform1f(uVig, vignette.coerceIn(0f, 1f))
            GLES30.glUniform1f(uSeed, (System.nanoTime() % 100000L).toFloat())

            if (lutReady && lutTex != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
                GLES30.glUniform1i(uLut, 1)
            }

            // 顶点不用属性：顶点着色器按 gl_VertexID 取四个角
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        /**
         * 从 `getTransformMatrix` 的左上 2×2 反解 HAL 的旋转。
         *
         * 相机 HAL 给的矩阵绝大多数是恒等或垂直翻转（后者是 SurfaceTexture 的坐标习惯），
         * 但也有机型会把预览流**预旋到显示方向**再交出来 —— 那时矩阵里就带一个 90°/270°
         * 的旋转。不扣掉它，两处旋转会互相抵消，预览就是躺着的（r68 踩过的坑）。
         */
        private fun readHalRotation() {
            val a = texMatrix[0]
            val c = texMatrix[1]
            val b = texMatrix[4]
            val d = texMatrix[5]
            halDet = a * d - b * c
            val deg = Math.toDegrees(kotlin.math.atan2(c.toDouble(), a.toDouble()))
            // 吸附到 90° 的整数倍：HAL 只可能整格旋转，斜着的一定是我读错了
            val snapped = Math.round(deg / 90.0).toInt() * 90
            halDeg = ((snapped % 360) + 360) % 360
        }
    }

    private companion object {

        const val VERT = """#version 300 es
void main() {
    // 全屏矩形四角（TRIANGLE_STRIP）：不传属性，省掉插值与 stride 出错的可能
    vec2 p[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));
    gl_Position = vec4(p[gl_VertexID], 0.0, 1.0);
}
"""

        /**
         * 片元里一次性完成：定位画面矩形 → 旋转回图像坐标 → 光晕/眩光 → 查表 → 颗粒/暗角。
         *
         * 为什么要光晕/眩光在查表**之前**：真胶片上这两个是光在乳剂层里横向散射，
         * 多出来的光**也参与显影**，所以应该加在密度曲线之前（即查表之前）；
         * 加在之后就成了"给成品刷一层雾"，颜色会往灰里跑。
         */
        const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uSrc;
uniform sampler3D uLut;
uniform mat4 uTexM;
uniform mat2 uRot;
uniform vec2 uHalf;
uniform vec2 uView;
uniform vec2 uInv;
uniform float uMirror;
uniform float uMix;
uniform float uGrain;
uniform float uHalation;
uniform float uGlow;
uniform float uVignette;
uniform float uSeed;

out vec4 fragColor;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// 把归一化画面坐标换成相机纹理坐标（含 HAL 变换矩阵与镜像）
vec2 toTex(vec2 q) {
    vec2 uv = vec2(q.x * 0.5 + 0.5, 0.5 - q.y * 0.5);
    uv = (uTexM * vec4(uv, 0.0, 1.0)).xy;
    uv.x = mix(uv.x, 1.0 - uv.x, uMirror);
    return uv;
}

void main() {
    // 屏幕坐标（-1..1，y 向上）
    vec2 s = gl_FragCoord.xy / uView * 2.0 - 1.0;
    // 画面矩形内坐标（超出就是黑边）
    vec2 p = s / uHalf;
    if (abs(p.x) > 1.0 || abs(p.y) > 1.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    // 转回图像坐标（uRot 里已经是"画面顺时针转了 θ"的逆）
    vec2 q = uRot * p;
    vec2 uv = toTex(q);

    vec3 base = texture(uSrc, uv).rgb;

    // ---- 光晕 / 眩光：亮部向外扩散 ----
    vec3 spill = vec3(0.0);
    if (uHalation > 0.001 || uGlow > 0.001) {
        vec2 r1 = uInv * 7.0;
        vec2 r2 = uInv * 26.0;
        vec3 near = vec3(0.0);
        vec3 far  = vec3(0.0);
        // 8 向 + 4 向两组采样：近的那圈是光晕（红），远的那圈是镜头眩光（暖白）
        // 只取四个轴向点：光晕是低频的，多加采样只会吃掉帧率，看不出差别
        near += texture(uSrc, uv + vec2( r1.x,  0.0)).rgb;
        near += texture(uSrc, uv + vec2(-r1.x,  0.0)).rgb;
        near += texture(uSrc, uv + vec2( 0.0,  r1.y)).rgb;
        near += texture(uSrc, uv + vec2( 0.0, -r1.y)).rgb;
        near *= 0.25;
        far += texture(uSrc, uv + vec2( r2.x,  0.0)).rgb;
        far += texture(uSrc, uv + vec2(-r2.x,  0.0)).rgb;
        far += texture(uSrc, uv + vec2( 0.0,  r2.y)).rgb;
        far += texture(uSrc, uv + vec2( 0.0, -r2.y)).rgb;
        far /= 4.0;

        // 亮部才溢光：暗部扩散出去的是雾，不是光晕
        float hn = smoothstep(0.55, 1.0, luma(near));
        float hf = smoothstep(0.72, 1.05, luma(far));
        spill += near * hn * uHalation * vec3(1.0, 0.42, 0.22);   // 红偏：卤化银层间散射
        spill += far  * hf * uGlow * vec3(1.0, 0.82, 0.60);       // 暖白：镜筒内反射
    }
    vec3 lit = base + spill;

    vec3 c = lit;
    if (uMix > 0.001) {
        vec3 graded = texture(uLut, clamp(lit, 0.0, 1.0)).rgb;
        c = mix(lit, graded, uMix);
    }

    // ---- 颗粒：两级噪声（粗的当银盐团簇，细的当扫描噪声） ----
    if (uGrain > 0.0005) {
        vec2 g = gl_FragCoord.xy + uSeed;
        float n = hash(g) * 0.65 + hash(floor(g * 2.0) + 7.0) * 0.35;
        // 中间调颗粒最明显，纯黑纯白几乎没有 —— 与真实银盐一致
        float amp = 4.0 * luma(c) * (1.0 - luma(c));
        c += (n - 0.5) * uGrain * (0.35 + amp);
    }

    // ---- 暗角 ----
    if (uVignette > 0.0005) {
        vec2 d = p;
        c *= 1.0 - uVignette * dot(d, d) * 0.42;
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

        fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) Diagnostics.log("cam", "取景器着色器链接失败：${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return p
        }

        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Diagnostics.log("cam", "取景器着色器编译失败：${GLES30.glGetShaderInfoLog(s)}")
            return s
        }
    }
}
