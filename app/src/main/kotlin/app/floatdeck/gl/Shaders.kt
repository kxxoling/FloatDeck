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

        in vec2 vUV;
        in vec2 vLocalPos;

        out vec4 fragColor;

        // 圆角矩形 SDF（有符号距离场）
        float roundedBoxSDF(vec2 centerPos, vec2 size, float radius) {
            vec2 q = abs(centerPos) - size + radius;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
        }

        // ---- 碎碎冰效果 ----
        // 基于网格的冰晶纹理，边缘发光，淡蓝色覆盖
        vec3 applyIceEffect(vec3 color, vec2 uv, vec2 localPos, float dist) {
            // 冰晶网格：用多重 sin 叠加模拟冰裂纹
            float ice1 = sin(uv.x * 25.0 + sin(uv.y * 18.0) * 2.0);
            float ice2 = sin(uv.y * 22.0 + sin(uv.x * 15.0) * 2.5);
            float ice3 = sin((uv.x + uv.y) * 20.0 + sin(uv.x * 12.0 - uv.y * 16.0));

            // 冰裂纹图案（锐利的线条）
            float crack = max(
                max(1.0 - smoothstep(0.0, 0.06, abs(ice1)),
                    1.0 - smoothstep(0.0, 0.06, abs(ice2))),
                1.0 - smoothstep(0.0, 0.05, abs(ice3))
            );

            // 边缘冰霜（靠近卡片边缘更明显）
            float edgeDist = 1.0 - abs(localPos.x);  // 0~1, 0=边缘
            float edgeFrost = smoothstep(0.75, 0.95, 1.0 - edgeDist);

            // 冰晶闪烁
            float sparkle = sin(uTime * 2.0 + uv.x * 40.0) * sin(uTime * 3.0 + uv.y * 35.0);
            sparkle = smoothstep(0.85, 1.0, sparkle) * 0.3;

            // 淡蓝色冰霜叠加
            vec3 iceColor = vec3(0.7, 0.85, 1.0);
            float iceIntensity = (crack * 0.4 + edgeFrost * 0.3 + sparkle);

            // 整体微冷色调
            vec3 coldTint = mix(color, color * vec3(0.9, 0.95, 1.1), 0.3);
            return mix(coldTint, iceColor, iceIntensity);
        }

        // ---- 闪卡效果 ----
        // 全息镭射卡：彩虹干涉 + 可切换的光斑形态 + 扫光 + 闪粉，screen 混合保证立绘可读

        // ===== 闪卡调试开关（改这里的常量后重新构建即可切换/对比）=====
        const int   HOLO_PATTERN        = 0;    // 光斑形态：0=斜向光栅 1=径向光芒 2=圆锥彩虹 3=扇面
        const float HOLO_LIGHT_GAIN     = 1.2;  // 光源随陀螺仪倾斜的位移强度
        const float HOLO_FOIL           = 0.55; // 整体镭射箔强度
        const bool  HOLO_ENABLE_SWEEP   = true; // 扫光带
        const bool  HOLO_ENABLE_GLITTER = true; // 闪粉
        const int   HOLO_RAYS_COUNT     = 12;   // 模式 1/3 的光芒条数
        // ==============================================================

        vec2 hash22(vec2 p) {
            p = vec2(dot(p, vec2(127.1, 311.7)),
                     dot(p, vec2(269.5, 183.3)));
            return fract(sin(p) * 43758.5453123);
        }

        // iq 余弦调色板：a + b*cos(2*pi*(c*t+d))，比 HSL->RGB 更便宜更平滑
        vec3 palette(float t) {
            vec3 a = vec3(0.5);
            vec3 b = vec3(0.5);
            vec3 c = vec3(1.0);
            vec3 d = vec3(0.00, 0.33, 0.67);  // RGB 相位偏移 -> 彩虹
            return a + b * cos(6.28318530718 * (c * t + d));
        }

        // 视角（陀螺仪）主导的虹彩相位，叠加空间变化与缓慢漂移
        float iridT(vec2 localPos) {
            float view = dot(uViewAngle, vec2(2.2, 1.7));
            float pos  = dot(localPos, vec2(0.55, 0.45));
            return fract(view * 0.6 + pos + uTime * 0.08);
        }

        // 光源位置：随陀螺仪倾斜位移（“反射光”跟着倾角走）
        vec2 holoLight() { return uViewAngle * HOLO_LIGHT_GAIN; }

        // 按 HOLO_PATTERN 切换的镭射箔形态，返回该点箔色
        vec3 holoPattern(vec2 localPos) {
            vec2 lightXY = holoLight();

            if (HOLO_PATTERN == 0) {
                // 斜向干涉光栅
                float coord = (localPos.x + localPos.y) * 22.0;
                float s = sin(coord + uTime * 1.2);
                float gr = smoothstep(0.1, 0.9, s * 0.5 + 0.5);
                return palette(iridT(localPos)) * (0.35 + 0.65 * gr);
            }

            vec2 d = localPos - lightXY;
            float ang = atan(d.y, d.x);
            float r = length(d);

            if (HOLO_PATTERN == 1) {
                // 径向光芒：从光源辐射的太阳光线
                float rays = pow(abs(sin(ang * float(HOLO_RAYS_COUNT) * 0.5)), 8.0);
                float hue = ang / 6.28318530718 * 0.5 + uTime * 0.05;
                return palette(hue) * (0.35 + 0.65 * rays);
            }
            if (HOLO_PATTERN == 2) {
                // 圆锥彩虹：角度直接映射色相
                float hue = ang / 6.28318530718 + 0.5 + uTime * 0.05;
                float falloff = clamp(r * 0.6, 0.0, 1.0);
                return palette(hue) * (0.4 + 0.6 * falloff);
            }
            // HOLO_PATTERN == 3：扇面，径向环 + 角度射线组合
            float rings = abs(sin(r * 9.0 - uTime * 1.5));
            float rays = pow(abs(sin(ang * float(HOLO_RAYS_COUNT) * 0.5)), 6.0);
            float hue = ang / 6.28318530718 * 0.5 + r * 0.3 + uTime * 0.05;
            return palette(hue) * (0.35 + 0.4 * rays + 0.3 * rings);
        }

        // 沿对角线扫过的高光带（约 5.5s 一次）
        float sweepFlare(vec2 localPos, float speed) {
            float c = (localPos.x + localPos.y) * 0.5 - fract(uTime * speed) * 2.0 + 0.5;
            c = abs(fract(c) - 0.5);
            // 注意：smoothstep 需 edge0 < edge1，用 1 - smoothstep 保证良定义
            return 1.0 - smoothstep(0.0, 0.18, c);
        }

        // 稀疏闪粉（约 1.5% 格子点亮，各自相位闪烁）
        float holoGlitter(vec2 uv) {
            vec2 g = floor(uv * vec2(90.0, 120.0));
            vec2 h = hash22(g);
            float on = step(0.985, h.x);
            float tw = 0.5 + 0.5 * sin(uTime * 6.0 + h.y * 50.0);
            return on * pow(tw, 3.0);
        }

        // screen 混合：1 - (1-a)(1-b)，不会让画面比原图更暗
        vec3 screenBlend(vec3 a, vec3 b) { return 1.0 - (1.0 - a) * (1.0 - b); }

        vec3 applyHoloEffect(vec3 color, vec2 uv, vec2 localPos) {
            // 1) 镭射箔（按调试开关切换形态）
            vec3 foil = holoPattern(localPos) * HOLO_FOIL;

            // 2) 扫光（加色，轻微 RGB 偏移）
            vec3 flare = vec3(0.0);
            if (HOLO_ENABLE_SWEEP) {
                float sw = sweepFlare(localPos, 0.18);
                flare = vec3(sw, sw * 0.95, sw * 0.85) * 0.45;
            }

            // 3) 闪粉
            vec3 glCol = vec3(0.0);
            if (HOLO_ENABLE_GLITTER) {
                float gl = holoGlitter(uv);
                glCol = palette(iridT(localPos) + 0.1) * gl * 0.7;
            }

            // 4) 边缘微泛光，营造卡片“吃光”
            float r = 1.0 - length(localPos);
            float bloom = smoothstep(-0.15, 0.35, r) * 0.06;

            // 5) 合成：screen 混合，保证立绘清晰可读
            vec3 holo = foil + flare + glCol + bloom;
            return screenBlend(color, holo);
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
