package app.floatdeck.gl

/** GLSL 着色器源码：肖像卡片（portrait）和背景各一组顶点/片段着色器。 */
object Shaders {
    /** 肖像卡片顶点着色器：处理旋转、缩放、偏移和视差效果。 */
    val portraitVertex =
        """
        #version 300 es
        precision highp float;

        uniform mat4 uMVP;
        uniform vec2 uOffset;
        uniform float uRotation;
        uniform vec2 uScale;
        uniform vec2 uParallax;

        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aUV;

        out vec2 vUV;
        out vec2 vLocalPos;

        void main() {
            // 应用旋转
            float c = cos(uRotation);
            float s = sin(uRotation);
            vec2 rotated = vec2(
                aPosition.x * c - aPosition.y * s,
                aPosition.x * s + aPosition.y * c
            );

            // 应用缩放
            vec2 scaled = rotated * uScale;

            // 应用偏移 + 视差
            vec2 pos = scaled + uOffset + uParallax;

            gl_Position = uMVP * vec4(pos, 0.0, 1.0);
            vUV = aUV;
            vLocalPos = aPosition;
        }
        """.trimIndent()

    /** 肖像卡片片段着色器：圆角矩形裁切 + 阴影 + 纹理采样 + 立绘特效。 */
    val portraitFragment =
        """
        #version 300 es
        precision highp float;

        uniform sampler2D uTexture;
        uniform float uAlpha;
        uniform vec4 uShadowColor;
        uniform vec2 uShadowOffset;
        uniform float uRadius;
        // 立绘特效：0=无, 1=碎碎冰, 2=炫彩
        uniform int uEffect;
        uniform float uTime;
        // 炫彩视角偏移（来自陀螺仪）
        uniform vec2 uViewAngle;
        // 碎冰破碎中心（卡片本地坐标 -1..1）与强度 0..1（由触摸/长按或随机触发）
        uniform vec2 uShatterPos;
        uniform float uShatterAmount;

        in vec2 vUV;
        in vec2 vLocalPos;

        out vec4 fragColor;

        // 圆角矩形 SDF（有符号距离场）
        float roundedBoxSDF(vec2 centerPos, vec2 size, float radius) {
            vec2 q = abs(centerPos) - size + radius;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
        }

    // ---- 碎碎冰效果 ----
    // 基于 Voronoi 多边形的晶体碎冰：晶面折射色散 + 裂纹 + 闪烁 + 边缘冰霜

    vec2 hash22(vec2 p) {
        p = vec2(dot(p, vec2(127.1, 311.7)),
                 dot(p, vec2(269.5, 183.3)));
        return fract(sin(p) * 43758.5453123);
    }

    struct Voronoi {
        float f1;        // 到最近特征点的距离
        float f2;        // 到次近特征点的距离
        vec2  nearest;   // 到最近特征点的向量（格内）
        vec2  cellId;    // 最近晶格的整数 id
    };

    // 3x3 邻域搜索，循环用常量边界（ES3.0 友好），循环内用平方距离省 sqrt
    Voronoi voronoi2D(vec2 x) {
        vec2 p = floor(x);
        vec2 f = fract(x);
        Voronoi r;
        float d1 = 8.0, d2 = 8.0;
        vec2 n1 = vec2(0.0), nearestCell = vec2(0.0);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 b  = vec2(float(i), float(j));
                vec2 o  = hash22(p + b);
                vec2 rb = b - f + o;
                float d = dot(rb, rb);
                if (d < d1) {
                    d2 = d1; n1 = rb;
                    d1 = d; nearestCell = p + b;
                } else if (d < d2) {
                    d2 = d;
                }
            }
        }
        r.f1      = sqrt(d1);
        r.f2      = sqrt(d2);
        r.nearest = n1;
        r.cellId  = nearestCell;
        return r;
    }

    // 每个晶面的平面法线（带轻微随机倾斜），用于折射与高光
    vec3 facetNormal(vec2 cellId) {
        vec2 h = hash22(cellId + 0.37) * 2.0 - 1.0;
        return normalize(vec3(h * 0.6, 1.0));
    }

    // 三通道折射采样 -> 色散（chromatic dispersion）
    vec3 refractedSample(sampler2D tex, vec2 uv, vec2 normalXY, float strength) {
        vec2 d = normalXY * strength;
        return vec3(
            texture(tex, uv + d *  1.0).r,
            texture(tex, uv + d *  0.0).g,
            texture(tex, uv + d * -1.0).b
        );
    }

    // F2 - F1 越小越靠近晶界 -> 裂纹越亮
    float crackMask(float f1, float f2, float w) {
        return 1.0 - smoothstep(0.0, w, f2 - f1);
    }

    // 闪烁：多频 sin 锐化成稀疏点
    float sparkle(vec2 drv) {
        float s = sin(drv.x * 1.3) * sin(drv.y * 1.7);
        return pow(max(0.0, 0.5 + 0.5 * s), 24.0);
    }

    // 圆角边缘冰霜强度（靠近卡片边缘越强）
    float frostRim(float dist) {
        return smoothstep(-0.18, 0.0, dist);
    }

    // ===== 碎冰破碎调试开关（改这里的常量后重新构建即可）=====
    const int   ICE_SHATTER_RAYS   = 14;    // 径向裂纹条数
    const float ICE_SHATTER_SPREAD = 1.8;   // 破碎传播半径系数
    const float ICE_SHATTER_PUSH   = 0.06;  // 碎片外推强度（UV 位移）
    const float ICE_SHATTER_BLEND  = 0.78;  // 破碎发光叠加强度
    // ==============================================================

    struct Shatter {
        vec2 uv;     // 经破碎位移后的采样 UV
        vec3 glow;   // 径向裂纹 + 前沿亮环的增量发光
    };

    // 计算破碎：以 uShatterPos 为中心向外传播；amt=0 时原样返回
    Shatter shatterEffect(vec2 uv, vec2 localPos) {
        Shatter s;
        s.uv = uv;
        s.glow = vec3(0.0);
        float amt = uShatterAmount;
        if (amt <= 0.001) return s;

        vec2 dvec = localPos - uShatterPos;
        float sdist = length(dvec) + 1e-4;
        float front = amt * ICE_SHATTER_SPREAD;
        // 前沿内（已破碎）区域：sdist 远小于 front 时为 1，大于 front 时为 0
        // 用 1 - smoothstep 保证 edge0 < edge1（良定义）
        float inFront = 1.0 - smoothstep(max(front - 0.6, 0.0), max(front, 0.001), sdist);

        // 碎片外推：UV 沿径向向外位移 -> 碎片像飞散开
        vec2 dir = dvec / sdist;
        s.uv = uv + dir * amt * ICE_SHATTER_PUSH * inFront;

        // 径向裂纹 + 前沿亮环
        float ang = atan(dvec.y, dvec.x);
        float rays = pow(abs(sin(ang * float(ICE_SHATTER_RAYS) * 0.5)), 6.0);
        float radialCracks = rays * inFront;
        float ring = exp(-pow(sdist - front, 2.0) * 22.0) * 0.8;
        s.glow = vec3(0.9, 0.97, 1.0) * (radialCracks * 0.7 + ring) * amt * ICE_SHATTER_BLEND;
        return s;
    }

    vec3 applyIceEffect(vec3 color, vec2 uv, vec2 localPos, float dist) {
        // ---- 破碎位移（碎片整体外推，UV 与 Voronoi 都跟随）----
        Shatter sh = shatterEffect(uv, localPos);
        vec2 sampleUV = sh.uv;

        // ---- 晶面划分 ----
        float scale = 7.0;
        Voronoi v = voronoi2D(sampleUV * scale);

        // ---- 晶面折射 + 色散 ----
        vec3  n       = facetNormal(v.cellId);
        float refrStr = 0.012;
        vec3  refrCol = refractedSample(uTexture, sampleUV, n.xy, refrStr);

        // 冷色调，并轻微提亮中间调
        vec3 coldVec = vec3(0.86, 0.95, 1.08);
        vec3 base    = refrCol * coldVec;
        base = mix(base, vec3(dot(base, vec3(0.333))), -0.06);

        // ---- 裂纹 ----
        float crack    = crackMask(v.f1, v.f2, 0.045);
        vec3  crackCol = vec3(0.85, 0.96, 1.0) * 1.6;

        // ---- 闪烁（每格独立相位，仅约 3% 的格子点亮）----
        float spk = sparkle(v.cellId * 7.3 + uTime * 1.7);
        spk *= step(0.97, hash22(v.cellId).x);

        // ---- 边缘冰霜 ----
        float rim = frostRim(dist);

        // ---- 合成 ----
        vec3 outCol = base;
        outCol += crackCol * crack * 0.55;              // 叠加裂纹
        outCol += vec3(0.9, 0.98, 1.0) * spk * 0.6;     // 闪烁
        outCol = mix(outCol, vec3(0.8, 0.92, 1.0), rim * 0.45);  // 边缘结霜
        outCol += sh.glow;                               // 破碎发光

        // 保留立绘可读性：原图与冰效果混合
        return mix(color, outCol, 0.72);
    }

        // ---- 炫彩效果 ----
        // 基于视角的彩虹渐变 + 光泽闪烁
        vec3 applyHoloEffect(vec3 color, vec2 uv, vec2 localPos) {
            // 视角相关偏移
            float viewShift = localPos.x * 2.0 + uViewAngle.x * 0.8
                            + localPos.y * 1.5 + uViewAngle.y * 0.6;

            // 彩虹色：基于视角的色相旋转
            float hue = fract(viewShift * 0.5 + uTime * 0.1);

            // HSL -> RGB (饱和度 0.3, 亮度 0.7)
            float h = hue * 6.0;
            float c = 0.21;  // 饱和度 * 亮度
            float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
            float m = 0.595;  // 亮度 - c/2
            vec3 rainbow;
            if (h < 1.0)      rainbow = vec3(c, x, 0.0);
            else if (h < 2.0) rainbow = vec3(x, c, 0.0);
            else if (h < 3.0) rainbow = vec3(0.0, c, x);
            else if (h < 4.0) rainbow = vec3(0.0, x, c);
            else if (h < 5.0) rainbow = vec3(x, 0.0, c);
            else              rainbow = vec3(c, 0.0, x);
            rainbow += m;

            // 光泽条纹
            float stripe = sin(localPos.y * 15.0 + viewShift * 3.0 + uTime * 1.5);
            stripe = smoothstep(0.6, 1.0, stripe) * 0.12;

            // 边缘高光
            float edgeGlow = 1.0 - length(localPos);
            edgeGlow = smoothstep(0.3, 0.8, edgeGlow) * 0.08;

            // 混合：炫彩覆盖约 8%
            return mix(color, rainbow + stripe + edgeGlow, 0.08);
        }

        void main() {
            // 主卡片遮罩（圆角矩形边缘柔化）
            float dist = roundedBoxSDF(vLocalPos, vec2(1.0), uRadius);
            float edgeSoftness = 0.02;
            float mask = 1.0 - smoothstep(-edgeSoftness, edgeSoftness, dist);

            vec4 texColor = texture(uTexture, vUV);

            // 应用立绘特效
            vec3 finalColor = texColor.rgb;
            if (uEffect == 1) {
                finalColor = applyIceEffect(finalColor, vUV, vLocalPos, dist);
            } else if (uEffect == 2) {
                finalColor = applyHoloEffect(finalColor, vUV, vLocalPos);
            }

            // 阴影层：偏移后再次计算圆角矩形 SDF
            vec2 shadowLocalPos = vLocalPos - uShadowOffset;
            float shadowDist = roundedBoxSDF(shadowLocalPos, vec2(1.0), uRadius);
            float shadowMask = 1.0 - smoothstep(-0.05, 0.1, shadowDist);

            vec3 shadow = uShadowColor.rgb * shadowMask * uShadowColor.a;
            vec3 color = mix(shadow, finalColor, mask);

            // 最终输出：卡片遮罩 + 阴影遮罩，乘以整体透明度
            fragColor = vec4(color, (mask + shadowMask * uShadowColor.a) * uAlpha);
        }
        """.trimIndent()

    /** 背景顶点着色器：全屏四边形，不做变换。 */
    val backgroundVertex =
        """
        #version 300 es
        precision highp float;

        uniform mat4 uMVP;
        uniform vec2 uParallax;

        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aUV;

        out vec2 vUV;

        void main() {
            gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
            vUV = aUV;
        }
        """.trimIndent()

    /** 背景片段着色器：简单纹理采样 + 整体透明度。 */
    val backgroundFragment =
        """
        #version 300 es
        precision highp float;

        uniform sampler2D uTexture;
        uniform float uAlpha;

        in vec2 vUV;
        out vec4 fragColor;

        void main() {
            vec4 texColor = texture(uTexture, vUV);
            fragColor = vec4(texColor.rgb, texColor.a * uAlpha);
        }
        """.trimIndent()
}
