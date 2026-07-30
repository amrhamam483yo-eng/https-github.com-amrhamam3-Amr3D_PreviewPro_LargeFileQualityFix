package com.amr3d.preview.pro

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class STLRenderer : GLSurfaceView.Renderer {

    // --- Shaders مع دعم اتجاه الإضاءة + ألوان الرؤوس (Vertex Colors) لملفات GLB ---
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uNormalMatrix;
        uniform mat4 uModelMatrix;
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        attribute vec4 aColor;
        varying vec3 fNormal;
        varying highp vec3 fPosition;
        varying highp vec3 fWorldPos;
        varying vec4 fVertexColor;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fNormal    = normalize((uNormalMatrix * vec4(vNormal, 0.0)).xyz);
            fPosition  = vPosition.xyz;
            fWorldPos  = (uModelMatrix * vPosition).xyz;
            fVertexColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec3 fNormal;
        varying highp vec3 fPosition;
        varying highp vec3 fWorldPos;
        varying vec4 fVertexColor;
        uniform vec4 uColor;
        uniform vec3 uLightDir;
        uniform int  uMaterial;
        uniform float uLightAngleDeg;
        // بيتحكم في شفافية الرسمة كلها — 1.0 عادي، وبتتقلل لحد ~0.15 وقت رسم
        // انعكاس الموديل (reflection) تحته، بدل ما نكرر كود الشيدر من الأول
        uniform float uOpacityMultiplier;
        // بيتحسب من نصف قطر الموديل الفعلي (1/modelRadius) بدل رقم ثابت (كان 0.015)
        // عشان حبيبات الخشب وعروق الرخام تبان بنفس النسبة والوضوح بغض النظر عن حجم
        // الموديل الحقيقي (مم صغيرة أو أمتار) — قبل كده كانت بتتلخبط (تتكدّس أو تختفي)
        // لأي موديل مش قريب من الحجم اللي الرقم الثابت كان متظبّط عليه.
        uniform float uPatternScale;
        uniform int uUseVertexColor;

        // ═══ Hash & Noise (لسه محتاجينها لشبكة الأرضية وتأثيرات تانية) ═══
        float hash(highp vec3 p) {
            p = fract(p * vec3(443.897, 397.297, 491.187));
            p += dot(p.zxy, p.yxz + 19.19);
            return fract(p.x * p.y * p.z);
        }

        void main() {
            vec3 N = normalize(fNormal);
            vec3 L = normalize(uLightDir);
            vec3 V = normalize(vec3(0.0, 0.0, 1.0) - fPosition * 0.008);

            // ═══ إضاءة "شبه واقعية" (البند 2.1) — قاعدة مطفية زي Clay Shading
            // القديمة (إضاءة ثلاثية: رئيسية + تعبئة + حافة) بس بإضافة لمعة ناعمة
            // (Soft Specular، Blinn-Phong بمدى واسع مش حاد) فوقها — مش PBR كامل،
            // بس بتقرّب الإحساس من مرجع "غلاية بإضاءة ناعمة واقعية": توازن نور/ظل
            // أوضح وحواف بتلمع بهدوء بدل ما تبقى مطفية 100%. ═══
            vec3 col;
            float baseAlpha;
            if (uUseVertexColor == 1) {
                col = fVertexColor.rgb;
                baseAlpha = fVertexColor.a;
            } else {
                col = uColor.rgb;
                baseAlpha = uColor.a;
            }

            float NdotL  = max(dot(N, L), 0.0);
            // ضوء تعبئة من الاتجاه المعاكس تقريبًا — بيوضّح التفاصيل في المناطق
            // البعيدة عن الضوء الرئيسي من غير ما تبقى سودة تمامًا
            float NdotL2 = max(dot(N, normalize(vec3(-0.35, -0.55, 0.4))), 0.0);
            // ضوء حافة خفيف من فوق — بيدي تمايز بسيط للحواف العلوية
            float NdotL3 = max(dot(N, normalize(vec3(0.0, 1.0, 0.15))), 0.0);

            // إحساس بسيط بالـ Ambient Occlusion: المناطق اللي مش واخدة ضوء كفاية من
            // أي مصدر من التلاتة (يعني تجاويف/تفاصيل غايرة) بتتظلل شوية أكتر —
            // من غير أي حسابات ضوضاء إضافية تقيلة على الأداء
            float lightSum = NdotL + NdotL2 * 0.5 + NdotL3 * 0.3;
            float occlusion = clamp(0.55 + lightSum * 0.5, 0.55, 1.0);

            vec3 ambient  = col * 0.42 * occlusion;
            vec3 diffuse  = col * NdotL  * 0.62;
            vec3 fill     = col * NdotL2 * 0.16;
            vec3 rimLight = col * NdotL3 * 0.10;

            // حافة خفيفة جدًا (fresnel) بس عشان الحواف الخارجية تبان، من غير أي لمعان حقيقي
            float NdotV   = max(dot(N, V), 0.0);
            float fresnel = pow(1.0 - NdotV, 5.0) * 0.06;
            vec3 rim      = col * fresnel;

            // لمعة ناعمة (Soft Specular) من الضوء الرئيسي بس — Blinn-Phong بأس منخفض
            // (10) يدي هالة واسعة ناعمة (زي دهان/سيراميك) مش نقطة حادة لامعة زي
            // المعدن. شدتها معتدلة (0.16) عشان تفضل موزونة مع الـ diffuse ومتطغاش عليه.
            vec3 Hn = normalize(L + V);
            float NdotH = max(dot(N, Hn), 0.0);
            float specular = pow(NdotH, 10.0) * 0.16 * NdotL;
            vec3 specCol = vec3(1.0, 0.98, 0.94) * specular; // أبيض دافئ خفيف، مش لون الموديل نفسه

            vec3 result = ambient + diffuse + fill + rimLight + rim + specCol;
            result = result / (result + vec3(0.55));  // tone mapping بسيط
            result = pow(result, vec3(0.9));           // gamma تقريبي
            gl_FragColor = vec4(result, baseAlpha * uOpacityMultiplier);
        }
    """.trimIndent()

    private val lineVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute float vPointSize;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = vPointSize;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    // ═══ ظل أرضي بسيط تحت الموديل (contact shadow) — quad مسطّح بتدرّج دائري
    // شفاف بيدّي إحساس إن الموديل "واقف على حاجة" مش طاير في الفضاء ═══
    private val shadowVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec3 vPosition;
        attribute vec2 vUV;
        varying vec2 fUV;
        void main() {
            gl_Position = uMVPMatrix * vec4(vPosition, 1.0);
            fUV = vUV;
        }
    """.trimIndent()

    private val shadowFragmentShaderCode = """
        precision mediump float;
        varying vec2 fUV;
        uniform vec3 uGlowColor;
        uniform float uGlowAlpha;
        void main() {
            float d = length(fUV);
            float alpha = smoothstep(1.0, 0.1, d) * uGlowAlpha;
            gl_FragColor = vec4(uGlowColor, alpha);
        }
    """.trimIndent()

    private var meshProgram = 0
    private var lineProgram = 0
    private var shadowProgram = 0

    // CPU-side buffers (nulled after upload to GPU)
    private var vertexBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var wireframeBuffer: FloatBuffer? = null
    private var wireframeVertexCount = 0
    private var vertexCountToDraw = 0

    // VBO handles — data lives on GPU after upload
    private val vboIds = IntArray(4) // [0]=vertex [1]=normal [2]=wireframe [3]=color
    private var vboReady = false
    private var pendingModel: STLModel? = null
    @Volatile private var useVertexColors = false
    private var pendingMaterials: List<GLBResolvedMaterial>? = null
    private var pendingMaterialIndices: IntArray? = null

    // جودة العرض من الإعدادات: 0=منخفضة 1=متوسطة 2=عالية
    @Volatile var qualityLevel: Int = 2

    @Volatile var wireframeMode = false

    /** بيتفعّل بس أثناء تدوير الموديل بإصبع واحد — بيوريه للمستخدم مركز الدوران (pivot)
     * اللي الموديل بيلف حواليه، عشان يعرف يتحكم في الاتجاه بشكل مقصود بدل ما يحس إنه
     * بيلف "من غير مرجعية". بيختفي تاني لما الإصبع يترفع. */
    @Volatile var showPivotIndicator = false
    /** true أثناء أي تفاعل لمس فعلي (تدوير/تحريك/تكبير) — بيوقف حركة "التنفس" الخفيفة
     * للموديل عشان مايتعارضش مع سحب المستخدم اليدوي */
    @Volatile var isUserInteracting = false
    /** true وقت ما وضع القياس مفعّل — بيوقف حركة "التنفس" عشان متعارضش مع دقة اختيار نقطتين القياس */
    @Volatile var suppressIdleFloat = false
    private var floatPhase = 0f

    private val mvpMatrix = FloatArray(16)
    /** بتتقلل مؤقتًا وقت رسم انعكاس الموديل (reflection) تحته، وترجع 1.0 بعد كده */
    private var currentOpacityMultiplier = 1f
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    @Volatile var rotationX = 25f
    @Volatile var rotationY = 35f
    @Volatile var scaleFactor = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    /** لما تبقى true، الموديل بيلف لوحده حوالين محور Y كل فريم — بيتوقف أوتوماتيك
     * أول ما المستخدم يلمس الشاشة عشان يدوّر يدويًا. */
    @Volatile var autoRotate = false

    /**
     * بيطبّق تغيير زووم (Pinch) حوالين نقطة معيّنة على الشاشة (نقطة تلاقي
     * الأصابع)، بدل ما الزووم يتم دايمًا حوالين مركز الموديل الثابت (البند 3.1).
     *
     * ليه ده كان بج: الإسقاط Orthographic بتاعنا متماثل (Symmetric) حوالين
     * منتصف الشاشة دايمًا — يعني تغيير scaleFactor لوحده (من غير أي لمس لـ
     * panX/panY) بيقرّب/يبعّد كل حاجة نحو مركز الشاشة بالظبط، مش نحو مكان
     * إصبعين المستخدم. النتيجة: أي تفصيلة المستخدم بيكبّرها بعيدة عن مركز
     * الشاشة كانت "بتفلت" وتتحرك بعيد عن مكان لمسه فعليًا وقت الزووم — وده
     * كان بيبان أوضح كل ما الزووم يزيد لأن أي إزاحة صغيرة عن المركز بتتكبّر
     * بصريًا مع كل مرة يزوم فيها.
     *
     * الحل: نحسب رياضيًا مقدار تعديل panX/panY المطلوب عشان النقطة اللي تحت
     * إصبعي المستخدم (نقطة التلاقي) تفضل ثابتة بصريًا في نفس مكانها بالظبط بعد
     * تغيير الزووم — مش بس النقطة الجديدة، لازم كمان نعوّض أي Pan سابق موجود
     * أصلاً (عشان كده المعادلة فيها panX*actualRatio مش panX ثابت).
     *
     * ملحوظة دقة: في حالة panX=panY=0 (يعني المستخدم لسه ما عملش Pan خالص)،
     * المعادلة دي بترجع بالظبط نفس نتيجة الطريقة القديمة (زووم حوالين المركز).
     * لكن لو فيه Pan موجود بالفعل، الطريقتين بيختلفوا عمدًا: القديمة كانت بتثبّت
     * panX زي ما هو (وده بالظبط سبب الـ"فلتان" — بيرجّع الزووم لمركز الموديل
     * الأصلي بدل مركز الشاشة الحالي)، والجديدة بتثبّت مركز الشاشة الحالي نفسه
     * (أو نقطة اللمس لو مش في النص) — وده الصح.
     *
     * @param focalScreenX/Y إحداثيات نقطة تلاقي الإصبعين بالبيكسل (من MotionEvent مباشرة)
     * @param spanRatio نسبة تغيّر المسافة بين الإصبعين (curSpan/previousSpan)
     */
    fun applyPinchZoom(focalScreenX: Float, focalScreenY: Float, spanRatio: Float) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val oldScale = scaleFactor
        val newScale = (oldScale * spanRatio).coerceIn(0.1f, 12f)
        if (newScale == oldScale) return
        // النسبة الفعلية بعد الـ coerceIn ممكن تختلف شوية عن spanRatio الأصلي
        // لو وصلنا لحد أقصى/أدنى الزووم — لازم نستخدمها هي بالظبط في المعادلة
        val actualRatio = newScale / oldScale

        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        // تحويل نقطة اللمس من بكسلات الشاشة لإحداثيات NDC (-1..1) — إحداثي Y في
        // اندرويد بينزل لتحت، وفي NDC بيطلع لفوق، فلازم نعكسه
        val ndcX = (focalScreenX / surfaceWidth) * 2f - 1f
        val ndcY = 1f - (focalScreenY / surfaceHeight) * 2f

        panX = panX * actualRatio + ndcX * ratio * (1f - actualRatio)
        panY = panY * actualRatio + ndcY * (1f - actualRatio)
        scaleFactor = newScale
    }

    // اتجاه الإضاءة - قابل للتغيير من أداة الإضاءة (LightDialOverlayView، حلقة 360°)
    @Volatile
    var lightAngle = 45f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
        } // زاوية الإضاءة من 0 إلى 360

    private var modelCenter = floatArrayOf(0f, 0f, 0f)
    private var modelRadius = 1f
    /** لو مش null، بيستخدم بدل modelCenter كمركز دوران — بيتحدد من نقطة اللمس الأولى
     * على سطح الموديل (raycast)، فيدّي إحساس تحكم أدق من الدوران حوالين مركز الصندوق
     * المحيط اللي ممكن يكون بعيد عن شكل الموديل الفعلي في الأشكال الغير منتظمة */
    @Volatile var pivotOverride: FloatArray? = null

    // CopyOnWriteArrayList بدل ArrayList - thread-safe
    private val measurementPoints = CopyOnWriteArrayList<FloatArray>()
    // وقت تثبيت كل نقطة (بالتوازي مع measurementPoints، بنفس الترتيب والطول دايمًا) —
    // مستخدم في أنيميشن "تكبر وقت التثبيت وبعدين تصغر" (drawMeasurementOverlay)
    private val measurementPointTimes = CopyOnWriteArrayList<Long>()
    @Volatile private var previewPoint: FloatArray? = null

    /** بتتحدث لحظياً أثناء سحب الإصبع بعد اختيار النقطة الأولى — عشان الخط والمسافة يتحركوا مع الإصبع */
    fun setPreviewMeasurementPoint(point: FloatArray?) {
        previewPoint = point
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    var modelColor = floatArrayOf(0.45f, 0.75f, 0.95f, 1.0f)

    fun setModelColor(r: Float, g: Float, b: Float) { modelColor = floatArrayOf(r, g, b, 1.0f) }

    // نظام المواد
    /** كل الخامات دلوقتي بنفس أسلوب الـ Clay المطفي (زي 3ds Max) — الفرق بينهم اللون بس،
     * مفيش لمعان ولا نقوش procedural خالص. */
    enum class Material(val id: Int, val nameAr: String, val defaultColor: FloatArray) {
        CLAY_GRAY  (0, "كلاي رمادي",   floatArrayOf(0.62f, 0.62f, 0.64f)),
        CLAY_WHITE (1, "كلاي أبيض",    floatArrayOf(0.88f, 0.87f, 0.84f)),
        CLAY_BLUE  (2, "كلاي أزرق",    floatArrayOf(0.30f, 0.48f, 0.72f)),
        CLAY_BROWN (3, "كلاي بني",     floatArrayOf(0.50f, 0.34f, 0.22f)),
        CLAY_ORANGE(4, "كلاي برتقالي", floatArrayOf(0.85f, 0.48f, 0.18f)),
        CLAY_BLACK (5, "كلاي أسود",    floatArrayOf(0.16f, 0.16f, 0.18f)),
        CLAY_YELLOW(6, "كلاي أصفر",    floatArrayOf(0.90f, 0.76f, 0.20f)),
        CLAY_RED   (7, "كلاي أحمر",    floatArrayOf(0.75f, 0.22f, 0.20f))
    }

    @Volatile var currentMaterial = Material.CLAY_GRAY
    /** أغلب ملفاتك بتيجي من 3ds Max (Z-up) — فده الافتراضي الجديد. لو حد احتاج ملف
     * من مصدر تاني بيستخدم Y-up أصلاً (زي Blender)، يقفلها من هنا */
    @Volatile var zUpMode = true
    /** أدنى نقطة حقيقية في الموديل (من الـ vertices الفعلية، مش تقريب من نصف القطر) —
     * بتتحسب في setModel() وبتتستخدم لمكان الظل/الـ Glow عشان يبقوا مظبوطين تحت
     * الموديل بالظبط مهما كان شكله غير منتظم */
    @Volatile var modelBottomY = 0f
    /** أقل/أقصى إحداثيات حقيقية للموديل (بعد تصحيح المحور) — مستخدمة في رسم صندوق
     * الأبعاد (Bounding Box) */
    private var modelMinBounds = floatArrayOf(0f, 0f, 0f)
    private var modelMaxBounds = floatArrayOf(0f, 0f, 0f)
    @Volatile var showBoundingBox = false
    /** هايلايت بسيط للحواف المفتوحة (نتيجة MeshIntegrityChecker) — الهدف مجرد
     * إشارة بصرية "في أماكن مفتوحة هنا"، مش تقرير دقيق (عدد الحواف مش مهم). */
    @Volatile var showOpenEdgesHighlight = false
    @Volatile var openEdgeHighlightVertices: FloatArray? = null
    /** true = ارسم انعكاس الموديل تحته (زي المرايا)، false = ارسم شبكة (Grid) بدله.
     * قابل للتحكم من شاشة الإعدادات. */
    @Volatile var showReflection = false // افتراضي مقفول (Grid تظهر بدله) — طلب Amr

    // ── أنيميشن الدخول: الموديل "بيوصل من بعيد" بعد كل تحميل ناجح (البند الجديد) ──
    // بيشتغل بـ Perspective مؤقتًا (المشهد العادي دايمًا Orthographic زي ما كان) عشان
    // إحساس "الاقتراب" يبان فعليًا (في Ortho البعد عن الكاميرا مبيغيّرش حجم الموديل
    // المرئي خالص، فأي أنيميشن "جاي من بعيد" هيبقى بلا معنى بصريًا من غيرها)
    private var introActive = false
    private var introStartMs = 0L
    private val introDurationMs = 1100L

    // ── حجم نقاط القياس: كبيرة وقت التثبيت (سهل تشوفها بدقة)، وبعدين تصغر لحجمها
    // المستقر بسرعة عشان متبقاش مشتتة (اقتراح المستخدم) ──
    private val POINT_SIZE_LARGE = 34f
    private val POINT_SIZE_RESTING = 15f
    private val POINT_SHRINK_DURATION_MS = 320L

    fun setMaterial(material: Material) {
        currentMaterial = material
        setModelColor(material.defaultColor[0], material.defaultColor[1], material.defaultColor[2])
    }
    fun getCurrentModelMatrix(): FloatArray = modelMatrix.copyOf()
    fun getCurrentViewMatrix(): FloatArray = viewMatrix.copyOf()
    fun getCurrentProjectionMatrix(): FloatArray = projectionMatrix.copyOf()
    fun getSurfaceWidth(): Int = surfaceWidth
    fun getSurfaceHeight(): Int = surfaceHeight

    private var currentModel: STLModel? = null
    fun getModel(): STLModel? = currentModel

    /** بتطبّق تبديل Y/Z لو zUpMode مفعّل، وترجع نفس الموديل من غير تغيير لو لأ.
     * دالة بيانات خالصة (Pure) — آمنة تتنادى من أي Thread (مش لازم GL thread)، عشان
     * أي جزء تاني في التطبيق (زي أدوات القياس في الـ Fragment) يقدر يزامن نسخته
     * من بيانات الموديل مع نفس البيانات اللي فعليًا بترتسم على الشاشة. */
    fun applyAxisConvention(model: STLModel): STLModel = if (zUpMode) swapYZ(model) else model

    /** ملحوظة: بيفترض إن الموديل الممرّر هنا اتطبّق عليه applyAxisConvention() بالفعل
     * من المستدعي — مش بيعمل التبديل تاني هنا عشان نتجنب تبديل مزدوج (اللي هيرجّع
     * الاتجاه الغلط تاني!) لما setModel بتتنفذ على GL thread عن طريق queueEvent. */
    /** بتحرر الموديل الحالي من الذاكرة (المصفوفات الضخمة + نقاط القياس) — لازم تتنادى
     * قبل أي تحميل جديد (STL أو حتى قبل التبديل لعرض DXF)، مش بس عند تبديل الوضع، عشان
     * الموديل السابق ميفضلش قاعد في الذاكرة "لحد ما الـ GC يقرر" وهو ده اللي كان بيخلي
     * حتى تحميل ملف واحد كبير لوحده يقرب من حد الذاكرة بسرعة. بيشيل بيانات الـ VBOs من
     * كارت الشاشة كمان (نفس المقابض بترجع لحجم صفر بدل ما تفضل شايلة آخر موديل اترفع). */
    fun clearModel() {
        currentModel = null
        pendingModel = null
        vertexBuffer = null; normalBuffer = null; wireframeBuffer = null
        vertexCountToDraw = 0
        wireframeVertexCount = 0
        vboReady = false
        useVertexColors = false
        pendingMaterials = null
        pendingMaterialIndices = null
        measurementPoints.clear()
        measurementPointTimes.clear()
        previewPoint = null
        if (vboIds[0] != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[3])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    fun setModel(model: STLModel) {
        currentModel = model
        pendingModel = model   // يُرفع على GL thread في onDrawFrame أو onSurfaceCreated
        useVertexColors = false
        pendingMaterials = null
        pendingMaterialIndices = null

        modelCenter = floatArrayOf(
            (model.minBounds[0] + model.maxBounds[0]) / 2f,
            (model.minBounds[1] + model.maxBounds[1]) / 2f,
            (model.minBounds[2] + model.maxBounds[2]) / 2f
        )
        val dx = model.maxBounds[0] - model.minBounds[0]
        val dy = model.maxBounds[1] - model.minBounds[1]
        val dz = model.maxBounds[2] - model.minBounds[2]
        modelRadius = (maxOf(dx, dy, dz) / 2f).let { if (it <= 0f) 1f else it }
        // أدنى نقطة Y حقيقية — دي هتستخدم لمكان الظل/الـ Glow بدل التقريب القديم
        // (نصف القطر)، فهيبقوا مظبوطين تحت الموديل بالظبط
        modelBottomY = model.minBounds[1]
        modelMinBounds = model.minBounds.copyOf()
        modelMaxBounds = model.maxBounds.copyOf()

        // زاوية افتراضية توازنية (Three-quarter view) تفضّل تُظهر السطح العلوي +
        // الوجه الأمامي + جزء من الجانب مع بعض، بدل زاوية شبه جانبية كانت بتطلع
        // "مفرودة" أفقيًا لموديلات طويلة/رفيعة (زي شكل قناة أو نصل).
        // ⚠️ نفس زاوية زرار "Reset" بالظبط (resetCamera في ViewerFragment) — الفرق
        // الوحيد إن الدخول بيبقى بزووم أقل شوية (0.85 بدل 1) عشان الموديل يـ"فيت"
        // مع الشاشة بهامش مريح أول ما يتفتح، وبعدين أي Reset بعد كده بيرجّع
        // للزووم الطبيعي (1) زي المتوقع من "إعادة ضبط".
        rotationX = 25f; rotationY = 35f; scaleFactor = 0.85f; panX = 0f; panY = 0f
        pivotOverride = null
        measurementPoints.clear()
        introActive = true
        introStartMs = android.os.SystemClock.uptimeMillis()
        updateProjection()
    }

    fun setModel(model: STLModel, materials: List<GLBResolvedMaterial>?, materialIndices: IntArray?) {
        currentModel = model
        pendingModel = model   // يُرفع على GL thread في onDrawFrame أو onSurfaceCreated
        if (materials != null && materialIndices != null) {
            pendingMaterials = materials
            pendingMaterialIndices = materialIndices
            useVertexColors = true
        } else {
            useVertexColors = false
            pendingMaterials = null
            pendingMaterialIndices = null
        }

        modelCenter = floatArrayOf(
            (model.minBounds[0] + model.maxBounds[0]) / 2f,
            (model.minBounds[1] + model.maxBounds[1]) / 2f,
            (model.minBounds[2] + model.maxBounds[2]) / 2f
        )
        val dx = model.maxBounds[0] - model.minBounds[0]
        val dy = model.maxBounds[1] - model.minBounds[1]
        val dz = model.maxBounds[2] - model.minBounds[2]
        modelRadius = (maxOf(dx, dy, dz) / 2f).let { if (it <= 0f) 1f else it }
        // أدنى نقطة Y حقيقية — دي هتستخدم لمكان الظل/الـ Glow بدل التقريب القديم
        // (نصف القطر)، فهيبقوا مظبوطين تحت الموديل بالظبط
        modelBottomY = model.minBounds[1]
        modelMinBounds = model.minBounds.copyOf()
        modelMaxBounds = model.maxBounds.copyOf()

        // زاوية افتراضية توازنية (Three-quarter view) تفضّل تُظهر السطح العلوي +
        // الوجه الأمامي + جزء من الجانب مع بعض، بدل زاوية شبه جانبية كانت بتطلع
        // "مفرودة" أفقيًا لموديلات طويلة/رفيعة (زي شكل قناة أو نصل).
        // ⚠️ نفس زاوية زرار "Reset" بالظبط (resetCamera في ViewerFragment) — الفرق
        // الوحيد إن الدخول بيبقى بزووم أقل شوية (0.85 بدل 1) عشان الموديل يـ"فيت"
        // مع الشاشة بهامش مريح أول ما يتفتح، وبعدين أي Reset بعد كده بيرجّع
        // للزووم الطبيعي (1) زي المتوقع من "إعادة ضبط".
        rotationX = 25f; rotationY = 35f; scaleFactor = 0.85f; panX = 0f; panY = 0f
        pivotOverride = null
        measurementPoints.clear()
        introActive = true
        introStartMs = android.os.SystemClock.uptimeMillis()
        updateProjection()
    }

    /** بتحوّل بيانات الموديل من نظام Z-up (زي 3ds Max) لنظام Y-up (اللي الرندر مبني
     * عليه)، عن طريق دوران حقيقي 90° حول محور X: (x, y, z) -> (x, z, -y).
     *
     * ⚠️ السبب الأصلي لعيب الـ Mirror (البند 0): النسخة القديمة كانت بتعمل مجرد
     * تبديل (swap) بسيط بين Y و Z من غير أي إشارة سالبة: (x, y, z) -> (x, z, y).
     * رياضيًا، تبديل محورين من غير قلب إشارة أي واحد فيهم هو "انعكاس" (reflection,
     * determinant = -1) مش دوران (rotation, determinant = +1) — يعني بيقلب
     * "يدوية" (chirality) الموديل بالكامل، فيظهر الموديل مقلوب زي المراية حتى في
     * أول رسمة له، والانعكاس تحت الموديل (drawReflection) كان بيورّث نفس العيب
     * لأنه بيرسم نسخة من نفس بيانات الموديل المقلوبة أصلًا.
     *
     * الإصلاح: نستخدم دوران حقيقي حول محور X (x, y, z) -> (x, z, -y) بدل التبديل
     * المباشر. ده بيحافظ على نفس تأثير "رفع" محور Z القديم ليبقى Y (الارتفاع)
     * لكن من غير قلب اليدوية، فالموديل بيترسم صح من غير Mirror. ولأنه دوران حقيقي
     * (مش انعكاس)، ترتيب رؤوس المثلثات (winding order) بيفضل صحيح زي ما هو من
     * غير أي حاجة تانية محتاجة تتغيّر. */
    /** ⚠️ بتعدّل مصفوفات الموديل في مكانها (in-place) بدل ما تستنسخ نسخة جديدة
     * كاملة منها — عمدًا، عشان نلغي ذروة استهلاك ذاكرة مؤقتة كانت بتحصل هنا (وقت
     * ما النسخة القديمة والجديدة يبقوا موجودين في الذاكرة مع بعض للحظة). كل رأس
     * (x,y,z) بيتحول بشكل مستقل عن باقي الرؤوس فـ التبديل الآمن في مكانه من غير
     * أي تأثير على قيم تانية (مفيش Aliasing بين الرؤوس المختلفة). */
    private fun swapYZ(model: STLModel): STLModel {
        val v = model.vertices
        var i = 0
        while (i < v.size) {
            val oldY = v[i + 1]
            val oldZ = v[i + 2]
            v[i + 1] = oldZ
            v[i + 2] = -oldY
            i += 3
        }
        val n = model.normals
        i = 0
        while (i < n.size) {
            val oldY = n[i + 1]
            val oldZ = n[i + 2]
            n[i + 1] = oldZ
            n[i + 2] = -oldY
            i += 3
        }
        val minB = floatArrayOf(model.minBounds[0], model.minBounds[2], -model.maxBounds[1])
        val maxB = floatArrayOf(model.maxBounds[0], model.maxBounds[2], -model.minBounds[1])
        return model.copy(minBounds = minB, maxBounds = maxB)
    }

    private fun buildVertexColors(model: STLModel, materials: List<GLBResolvedMaterial>, materialIndices: IntArray): FloatArray {
        val triangleCount = model.triangleCount
        val colors = FloatArray(triangleCount * 3 * 4)
        for (t in 0 until triangleCount) {
            val matIdx = materialIndices.getOrElse(t) { -1 }
            val mat = materials.getOrNull(matIdx)
            val r = mat?.baseColorFactor?.getOrNull(0) ?: 1f
            val g = mat?.baseColorFactor?.getOrNull(1) ?: 1f
            val b = mat?.baseColorFactor?.getOrNull(2) ?: 1f
            val a = mat?.baseColorFactor?.getOrNull(3) ?: 1f
            val base = t * 3 * 4
            for (v in 0..2) {
                val offset = base + v * 4
                colors[offset] = r
                colors[offset + 1] = g
                colors[offset + 2] = b
                colors[offset + 3] = a
            }
        }
        return colors
    }

    /** Uploads model geometry to GPU VBOs using chunked approach to avoid OOM. Called on GL thread. */
    private fun uploadModelToGPU(model: STLModel) {
        val verts = model.vertices
        val norms = model.normals
        vertexCountToDraw = verts.size / 3

        try {
            // رفع vertices مباشرة chunk بـ chunk لتجنب OOM
            val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            vb.asFloatBuffer().put(verts); vb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES20.GL_STATIC_DRAW)
            // ⚠️ ملحوظة: هنا كان فيه استدعاء System.gc() يدوي اتشال. System.gc() مش
            // بيضمن تحرير فوري للذاكرة (مجرد "اقتراح" للـ GC)، لكنه بيوقف التطبيق فعليًا
            // للحظات محاول ينفذ — وده بالظبط سبب إحساس "الهنج" أثناء تحميل موديل كبير،
            // مش حل له. حذفه وحده بيشيل مصدر تهنيج حقيقي.

            val nb = ByteBuffer.allocateDirect(norms.size * 4).order(ByteOrder.nativeOrder())
            nb.asFloatBuffer().put(norms); nb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, norms.size * 4, nb, GLES20.GL_STATIC_DRAW)

            // Wireframe: LOD مع حد أقصى 50K مثلث للـ wireframe
            val triCount = vertexCountToDraw / 3
            val qualityMultiplier = when (qualityLevel) {
                0 -> 4    // من
