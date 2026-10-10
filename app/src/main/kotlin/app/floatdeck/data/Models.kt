package app.floatdeck.data

import app.floatdeck.R

/** 立绘特效类型。 */
enum class PortraitEffect(
    val key: String,
    val labelResId: Int,
) {
    NONE("none", R.string.effect_none),
    ICE("ice", R.string.effect_ice),
    HOLO("holo", R.string.effect_holo),
    ;

    companion object {
        fun fromKey(key: String): PortraitEffect = entries.find { it.key == key } ?: NONE
    }
}

/**
 * 肖像卡片位置参数（所有坐标为屏幕比例 0~1）。
 *
 * @param xRatio 水平位置比例
 * @param yRatio 垂直位置比例
 * @param rotation 旋转角度（度）
 * @param scale 缩放倍数
 * @param tiltOffset 视差灵敏度系数（越大跟随倾斜越明显，1 为默认）
 */
data class PortraitPosition(
    val xRatio: Float,
    val yRatio: Float,
    val rotation: Float = 0f,
    val scale: Float = 1f,
    val tiltOffset: Float = 1f,
)

/**
 * 单张肖像卡片配置：资源路径 + 可选的锁屏/解锁显式位置。
 * 位置为 null 时渲染器使用内置布局算法。
 */
data class PortraitConfig(
    val id: String,
    val assetPath: String,
    val label: String,
    val lockedPosition: PortraitPosition? = null,
    val unlockedPosition: PortraitPosition? = null,
    val isRemote: Boolean = false,
)

/** 模板配置：包含背景图和一组肖像卡片。 */
data class TemplateConfig(
    val id: String,
    val name: String,
    val wallpaperAsset: String?,
    val portraits: List<PortraitConfig>,
    val isRemote: Boolean = false,
    val effect: PortraitEffect = PortraitEffect.NONE,
)
