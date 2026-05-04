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
