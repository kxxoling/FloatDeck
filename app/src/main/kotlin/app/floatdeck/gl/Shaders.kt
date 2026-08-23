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
        // Shatter epicenter (card-local coords -1..1) and intensity 0..1
        // (triggered by touch/long-press or idle randomness)
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

        vec2 hash22(vec2 p) {
            p = vec2(dot(p, vec2(127.1, 311.7)),
                     dot(p, vec2(269.5, 183.3)));
            return fract(sin(p) * 43758.5453123);
        }

        // iq cosine palette: rainbow
        vec3 rainbowPalette(float t) {
            return 0.5 + 0.5 * cos(6.28318530718 * (t + vec3(0.0, 0.33, 0.67)));
        }

        // Screen blend: additive without darkening the image
        vec3 screenBlend(vec3 a, vec3 b) { return 1.0 - (1.0 - a) * (1.0 - b); }

        // ---- 碎碎冰效果 ----
        // Voronoi facets: refractive dispersion + cracks + view-angle
        // glints + frost rim + shatter impulse

        // 3x3-neighborhood Voronoi returning (nearest, second-nearest)
        // distances; the cell id is passed out via the out parameter
        vec2 voronoi(vec2 x, out vec2 cellId) {
            vec2 p = floor(x);
            vec2 f = fract(x);
            float d1 = 8.0, d2 = 8.0;
            cellId = vec2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    vec2 b = vec2(float(i), float(j));
                    vec2 rb = b - f + hash22(p + b);
                    float d = dot(rb, rb);
                    if (d < d1) {
                        d2 = d1; d1 = d;
                        cellId = p + b;
                    } else if (d < d2) {
                        d2 = d;
                    }
                }
            }
            return vec2(sqrt(d1), sqrt(d2));
        }

        vec3 applyIceEffect(vec3 color, vec2 uv, vec2 localPos, float dist) {
            // ---- Shatter shockwave: propagates outward from uShatterPos,
            // pushing fragments radially apart ----
            vec2 sampleUV = uv;
            vec3 shatterGlow = vec3(0.0);
            if (uShatterAmount > 0.001) {
                vec2 dvec = localPos - uShatterPos;
                float sdist = length(dvec) + 1e-4;
                float front = uShatterAmount * 1.8;
                float inFront = 1.0 - smoothstep(max(front - 0.6, 0.0), max(front, 0.001), sdist);
                vec2 dir = dvec / sdist;
                sampleUV = uv + dir * uShatterAmount * 0.05 * inFront;
                float rays = pow(abs(sin(atan(dvec.y, dvec.x) * 7.0)), 6.0);
                float ring = exp(-pow(sdist - front, 2.0) * 22.0) * 0.8;
                shatterGlow = vec3(0.85, 0.95, 1.0) * (rays * inFront * 0.7 + ring) * uShatterAmount * 0.78;
            }

            // ---- Facet partitioning and normals ----
            vec2 cellId;
            vec2 f12 = voronoi(sampleUV * 7.0, cellId);
            float f1 = f12.x, f2 = f12.y;
            vec2 tilt = hash22(cellId + 0.37) * 2.0 - 1.0;

            // ---- Facet refraction + dispersion (offset sampling per channel) ----
            vec2 refr = tilt * 0.012;
            vec3 refrCol = vec3(
                texture(uTexture, sampleUV + refr).r,
                texture(uTexture, sampleUV).g,
                texture(uTexture, sampleUV - refr).b);

            // Cold-tinted base
            vec3 base = refrCol * vec3(0.88, 0.95, 1.06);

            // ---- Cracks (brighter near cell borders) ----
            float crack = 1.0 - smoothstep(0.0, 0.05, f2 - f1);

            // ---- Facet glints: normals vs gyroscope tilt; tilting the
            // phone lights facets up in sequence ----
            float facing = clamp(dot(normalize(vec3(tilt * 0.6, 1.0)),
                                     normalize(vec3(uViewAngle * 1.4, 1.5))), 0.0, 1.0);
            float glint = pow(facing, 24.0);

            // ---- Sparse glitter (independent phase per cell) ----
            vec2 h = hash22(cellId * 3.1);
            float sparkle = pow(max(0.0, 0.5 + 0.5 * sin(uTime * 2.2 + h.y * 40.0)), 16.0)
                          * step(0.9, h.x);

            // ---- Frost rim ----
            float rim = smoothstep(-0.18, 0.0, dist);

            // ---- Composite ----
            vec3 outCol = base;
            outCol += vec3(0.8, 0.92, 1.0) * crack * 0.5;
            outCol += vec3(0.95, 0.99, 1.0) * (glint * 0.7 + sparkle * 0.5);
            outCol = mix(outCol, vec3(0.78, 0.9, 1.0), rim * 0.4);
            outCol += shatterGlow;

            return mix(color, outCol, 0.72);
        }

        // ---- 炫彩效果 ----
        // Holographic foil: hue flows with gyroscope tilt, plus diffraction
        // grating, specular sheen and glitter
        vec3 applyHoloEffect(vec3 color, vec2 uv, vec2 localPos) {
            // Thin-film interference: view angle dominates, position
            // contributes; slow drift as a fallback so a static phone
            // still shows a faint shimmer
            float phase = dot(uViewAngle, vec2(1.6, 1.2))
                        + dot(localPos, vec2(0.5, 0.4))
                        + uTime * 0.03;
            vec3 foil = rainbowPalette(phase);

            // Diagonal diffraction grating: fine stripes whose color
            // shifts with the view angle
            float grating = 0.5 + 0.5 * sin((localPos.x + localPos.y) * 24.0 + phase * 6.0);
            foil *= 0.45 + 0.55 * smoothstep(0.25, 0.85, grating);

            // Specular sheen band: positioned by tilt, sweeping across
            // the card as the phone is tilted
            float sheenCoord = (localPos.x + localPos.y) * 0.7 - uViewAngle.x * 1.6 - uViewAngle.y * 0.9;
            float sheen = exp(-pow(sheenCoord, 2.0) * 6.0);
            vec3 sheenCol = mix(foil, vec3(1.0), 0.6) * sheen * 0.5;

            // Sparse glitter: ~1.5% of cells lit, twinkling with
            // individual phases
            vec2 g = floor(uv * vec2(90.0, 120.0));
            vec2 hg = hash22(g);
            float glitter = step(0.985, hg.x)
                          * pow(0.5 + 0.5 * sin(uTime * 6.0 + hg.y * 50.0), 3.0);

            // Composite: screen blend keeps the portrait readable while
            // the effect stays vivid
            vec3 holo = foil * 0.34 + sheenCol + rainbowPalette(phase + 0.12) * glitter * 0.6;
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
