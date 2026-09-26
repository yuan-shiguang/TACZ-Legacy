package com.tacz.legacy.common.resource

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.api.modifier.ParameterizedCachePair
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object TACZGunPropertyResolver {
    internal data class FireModeAdjust(
        val damage: Float = 0f,
        val rpm: Int = 0,
        val ammoSpeed: Float = 0f,
        val armorIgnore: Float = 0f,
        val headShot: Float = 0f,
        val aimInaccuracy: Float = 0f,
        val otherInaccuracy: Float = 0f,
    )

    internal data class RecoilFunctions(
        val pitch: PolynomialSplineFunction?,
        val yaw: PolynomialSplineFunction?,
    )

    private val SLUG_TAG: ResourceLocation = ResourceLocation("tacz", "intrinsic/slug")
    private val RECOIL_SPLINE_INTERPOLATOR = SplineInterpolator()

    internal fun collectAttachmentIds(stack: ItemStack, iGun: IGun): List<ResourceLocation> {
        return AttachmentType.values().mapNotNull { type ->
            if (type == AttachmentType.NONE) {
                return@mapNotNull null
            }
            val installed = iGun.getAttachmentId(stack, type)
            when {
                installed != DefaultAssets.EMPTY_ATTACHMENT_ID -> installed
                else -> iGun.getBuiltInAttachmentId(stack, type).takeIf { it != DefaultAssets.EMPTY_ATTACHMENT_ID }
            }
        }
    }

    internal fun resolveBulletAmount(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Int {
        val extendedMagId = resolveCurrentAttachmentId(stack, iGun, AttachmentType.EXTENDED_MAG)
        return if (matchesAttachmentTag(extendedMagId, SLUG_TAG)) {
            1
        } else {
            gunData.bulletData.bulletAmount.coerceAtLeast(1)
        }
    }

    /**
     * 当前枪装配的加长弹匣等级（0 = 未装，1..3 = 一至三级）。
     * 之前 TACZGunScriptAPI.getMagExtentLevel() 写死返回 0，导致 16 把使用
     * xmag_reload_logic 的枪（ak47 / m4 系 / scar 系 等）完全不识别加长弹匣：
     * 换弹时序只走基础档、装填容量也被基础弹容卡死。
     */
    internal fun resolveMagExtentLevel(stack: ItemStack, iGun: IGun): Int {
        val id = iGun.getAttachmentId(stack, AttachmentType.EXTENDED_MAG)
        val effectiveId = if (id != DefaultAssets.EMPTY_ATTACHMENT_ID) {
            id
        } else {
            iGun.getBuiltInAttachmentId(stack, AttachmentType.EXTENDED_MAG)
        }
        return GunDataAccessor.getAttachmentExtendedMagLevel(effectiveId)
    }

    /**
     * 有效弹匣容量 = 基础弹容 + 加长弹匣加成。
     * 枪包里 extended_mag_ammo_amount 是每一级的绝对容量数组，level 1 取 [0]。
     * 与 LegacyGunRefitRuntime.computeAmmoCapacity / 客户端弹容提示保持一致。
     */
    internal fun resolveMaxAmmoCount(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Int {
        val level = resolveMagExtentLevel(stack, iGun)
        if (level > 0) {
            val gunId = iGun.getGunId(stack)
            val arr = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId]?.data?.extendedMagAmmoAmount
            if (arr != null && level <= arr.size) {
                return arr[level - 1].coerceAtLeast(gunData.ammoAmount)
            }
        }
        return gunData.ammoAmount
    }

    /**
     * 有效子弹战斗数据 = 基础 bulletData 叠加所有已装配件 / 独占配件的战斗修正
     * (damage / pierce / knockback / ammo_speed / armor_ignore / head_shot)，
     * 以及当前射击模式的 fire_mode_adjust 加成。
     *
     * 之前这些修正只在 GunRefitScreen 的 UI 预览里出现，实际生成的子弹完全没吃到，
     * 是所有枪与官版 TACZ 最大的行为差距之一（装上穿甲弹/消音器/高伤枪管却毫无效果）。
     */
    internal fun resolveEffectiveBulletData(
        stack: ItemStack,
        iGun: IGun,
        gunData: GunCombatData,
    ): BulletCombatData {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val raw = snapshot.guns[gunId]?.data?.raw ?: JsonObject()
        val adjust = parseFireModeAdjust(raw, iGun.getFireMode(stack))
        val base = gunData.bulletData

        val damageMods = collectNumericModifiers(stack, iGun, gunId, "damage")
        val pierceMods = collectNumericModifiers(stack, iGun, gunId, "pierce")
        val knockbackMods = collectNumericModifiers(stack, iGun, gunId, "knockback")
        val ammoSpeedMods = collectNumericModifiers(stack, iGun, gunId, "ammo_speed")
        val armorIgnoreMods = collectNumericModifiers(stack, iGun, gunId, "armor_ignore")
        val headShotMods = collectNumericModifiers(stack, iGun, gunId, "head_shot")

        val effectiveDamage = TACZAttachmentModifierRegistry.evalNumeric(
            damageMods, (base.damage + adjust.damage).toDouble(),
        ).toFloat().coerceAtLeast(0f)
        val effectivePierce = TACZAttachmentModifierRegistry.evalNumeric(
            pierceMods, base.pierce.toDouble(),
        ).roundToInt().coerceAtLeast(1)
        val effectiveKnockback = TACZAttachmentModifierRegistry.evalNumeric(
            knockbackMods, base.knockback.toDouble(),
        ).toFloat().coerceAtLeast(0f)
        val effectiveSpeed = TACZAttachmentModifierRegistry.evalNumeric(
            ammoSpeedMods, (base.speed + adjust.ammoSpeed).toDouble(),
        ).toFloat().coerceAtLeast(0f)

        val baseArmorIgnore = base.extraDamageData?.armorIgnore ?: 0f
        val effectiveArmorIgnore = TACZAttachmentModifierRegistry.evalNumeric(
            armorIgnoreMods, (baseArmorIgnore + adjust.armorIgnore).toDouble(),
        ).toFloat().coerceIn(0f, 1f)
        val baseHeadShot = base.extraDamageData?.headShotMultiplier ?: 1f
        val effectiveHeadShot = TACZAttachmentModifierRegistry.evalNumeric(
            headShotMods, (baseHeadShot + adjust.headShot).toDouble(),
        ).toFloat().coerceAtLeast(0f)

        val effectiveExtra = BulletExtraDamageData(
            armorIgnore = effectiveArmorIgnore,
            headShotMultiplier = effectiveHeadShot,
            damageAdjust = base.extraDamageData?.damageAdjust.orEmpty(),
        )

        return BulletCombatData(
            damage = effectiveDamage,
            speed = effectiveSpeed,
            gravity = base.gravity,
            friction = base.friction,
            pierce = effectivePierce,
            lifeSecond = base.lifeSecond,
            bulletAmount = base.bulletAmount,
            knockback = effectiveKnockback,
            tracerCountInterval = base.tracerCountInterval,
            igniteEntity = base.igniteEntity,
            igniteEntityTime = base.igniteEntityTime,
            igniteBlock = base.igniteBlock,
            extraDamageData = effectiveExtra,
            explosionData = base.explosionData,
        )
    }

    /**
     * 有效射速（RPM）= 基础 rpm + 射击模式 fire_mode_adjust.rpm，
     * 叠加所有配件 / 独占配件的 rpm 修正。
     *
     * 实际射击冷却此前一直用基础 rpm（gunData.getShootIntervalMs()），
     * 配件增减射速（如枪口制退器、重型枪机）从未生效，这里补上。
     */
    internal fun resolveEffectiveRpm(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Int {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val raw = snapshot.guns[gunId]?.data?.raw ?: JsonObject()
        val adjust = parseFireModeAdjust(raw, iGun.getFireMode(stack))
        val rpmMods = collectNumericModifiers(stack, iGun, gunId, "rpm")
        return TACZAttachmentModifierRegistry.evalNumeric(
            rpmMods, (gunData.roundsPerMinute + adjust.rpm).toDouble(),
        ).roundToInt().coerceAtLeast(0)
    }

    /** 基于有效射速的射击间隔（毫秒）。RPM<=0 返回 0（无冷却）。 */
    internal fun resolveEffectiveShootIntervalMs(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Long {
        val rpm = resolveEffectiveRpm(stack, iGun, gunData)
        if (rpm <= 0) return 0L
        return (60_000L / rpm)
    }

    private fun collectNumericModifiers(
        stack: ItemStack,
        iGun: IGun,
        gunId: ResourceLocation,
        modifierKey: String,
    ): List<Modifier> {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentMods = collectAttachmentIds(stack, iGun).mapNotNull { id ->
            snapshot.attachments[id]?.data?.modifiers?.get(modifierKey)?.getValue() as? Modifier
        }
        val exclusiveMods = collectExclusiveBonus(stack, iGun, gunId, modifierKey) { value -> value as? Modifier }
        return attachmentMods + exclusiveMods
    }

    internal fun resolveInaccuracyProfile(stack: ItemStack, iGun: IGun): Map<String, Float> {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val raw = snapshot.guns[gunId]?.data?.raw ?: return DEFAULT_INACCURACY
        val adjust = parseFireModeAdjust(raw, iGun.getFireMode(stack))
        val defaults = parseInaccuracyDefaults(raw, adjust)
        val modifiers = collectAttachmentIds(stack, iGun).mapNotNull { attachmentId ->
            @Suppress("UNCHECKED_CAST")
            snapshot.attachments[attachmentId]?.data?.modifiers?.get("inaccuracy")?.getValue() as? Map<String, Modifier>
        }
        // 独占配件加成（exclusive_attachments）一并计入精度
        val exclusiveInaccuracy = collectExclusiveBonus(stack, iGun, gunId, "inaccuracy") { value ->
            @Suppress("UNCHECKED_CAST") value as? Map<String, Modifier>
        }
        return TACZAttachmentModifierRegistry.evalInaccuracy(modifiers + exclusiveInaccuracy, defaults)
    }

    private inline fun <reified T> collectExclusiveBonus(
        stack: ItemStack,
        iGun: IGun,
        gunId: ResourceLocation,
        modifierKey: String,
        cast: (Any?) -> T?,
    ): List<T> {
        val exclusive = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId]?.data?.exclusiveAttachments ?: return emptyList()
        return collectAttachmentIds(stack, iGun).mapNotNull { id ->
            cast(exclusive[id]?.get(modifierKey)?.getValue())
        }
    }

    internal fun resolveInaccuracy(
        shooter: EntityLivingBase,
        stack: ItemStack,
        iGun: IGun,
        gunData: GunCombatData,
    ): Float {
        val profile = resolveInaccuracyProfile(stack, iGun)
        val stateKey = resolveInaccuracyStateKey(shooter)
        val base = profile[stateKey] ?: profile[INACCURACY_STAND] ?: 0f
        val heatMultiplier = resolveHeatInaccuracyMultiplier(stack, iGun, gunData)
        return (base * heatMultiplier).coerceAtLeast(0f)
    }

    internal fun resolveHeatInaccuracyMultiplier(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Float {
        if (!gunData.hasHeatData || gunData.heatMax <= 0f) {
            return 1f
        }
        val heatPercentage = (iGun.getHeatAmount(stack) / gunData.heatMax).coerceIn(0f, 1f)
        return lerp(gunData.heatMinInaccuracy, gunData.heatMaxInaccuracy, heatPercentage)
    }

    internal fun resolveHeatRpmModifier(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Float {
        if (!gunData.hasHeatData || gunData.heatMax <= 0f) {
            return 1f
        }
        val heatPercentage = (iGun.getHeatAmount(stack) / gunData.heatMax).coerceIn(0f, 1f)
        return lerp(gunData.heatMinRpmModifier, gunData.heatMaxRpmModifier, heatPercentage)
    }

    internal fun resolveCameraRecoil(
        stack: ItemStack,
        iGun: IGun,
        gunData: GunCombatData,
        aimingProgress: Float,
        isCrawling: Boolean,
    ): RecoilFunctions? {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val raw = snapshot.guns[gunId]?.data?.raw ?: return null
        val recoilObject = raw.jsonObject("recoil") ?: return null
        val recoilDefaults = parseRecoilDefaults(raw)
        val recoilModifiers = collectAttachmentIds(stack, iGun).mapNotNull { attachmentId ->
            snapshot.attachments[attachmentId]?.data?.modifiers?.get("recoil")?.getValue() as? TACZRecoilModifierValue
        }
        // 独占配件加成（exclusive_attachments）一并计入后坐力
        val exclusiveRecoil = collectExclusiveBonus(stack, iGun, gunId, "recoil") { value ->
            value as? TACZRecoilModifierValue
        }
        val recoilCache = TACZAttachmentModifierRegistry.evalRecoil(recoilModifiers + exclusiveRecoil, recoilDefaults.first, recoilDefaults.second)
        val zoom = iGun.getAimingZoom(stack).coerceAtLeast(1f)
        val aimDenominator = min(sqrt(zoom), 1.5f)
        var aimingRecoilModifier = 1f - aimingProgress + aimingProgress / aimDenominator
        if (isCrawling) {
            aimingRecoilModifier *= gunData.crawlRecoilMultiplier
        }

        val pitchModifier = recoilCache.left().eval(aimingRecoilModifier.toDouble()).toFloat()
        val yawModifier = recoilCache.right().eval(aimingRecoilModifier.toDouble()).toFloat()
        val pitchSpline = buildRecoilSpline(recoilObject.getAsJsonArray("pitch"), pitchModifier)
        val yawSpline = buildRecoilSpline(recoilObject.getAsJsonArray("yaw"), yawModifier)
        if (pitchSpline == null && yawSpline == null) {
            return null
        }
        return RecoilFunctions(pitch = pitchSpline, yaw = yawSpline)
    }

    internal fun matchesAttachmentTag(
        attachmentId: ResourceLocation,
        tagId: ResourceLocation,
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): Boolean {
        if (attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return false
        }
        return matchesAttachmentEntries(snapshot, snapshot.attachmentTags[tagId] ?: return false, attachmentId, mutableSetOf())
    }

    internal fun parseRecoilDefaults(raw: JsonObject): Pair<Float, Float> {
        val recoilObject = raw.jsonObject("recoil") ?: return 0f to 0f
        return curveMagnitude(recoilObject.getAsJsonArray("pitch")) to curveMagnitude(recoilObject.getAsJsonArray("yaw"))
    }

    private fun resolveCurrentAttachmentId(stack: ItemStack, iGun: IGun, type: AttachmentType): ResourceLocation {
        val installed = iGun.getAttachmentId(stack, type)
        if (installed != DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return installed
        }
        return iGun.getBuiltInAttachmentId(stack, type)
    }

    private fun resolveInaccuracyStateKey(shooter: EntityLivingBase): String {
        val operator = IGunOperator.fromLivingEntity(shooter)
        if (operator.getSynAimingProgress() == 1.0f) {
            return INACCURACY_AIM
        }
        if (operator.getDataHolder().isCrawling) {
            return INACCURACY_LIE
        }
        if (shooter.isSneaking) {
            return INACCURACY_SNEAK
        }
        if (isMoving(shooter)) {
            return INACCURACY_MOVE
        }
        return INACCURACY_STAND
    }

    private fun isMoving(shooter: EntityLivingBase): Boolean {
        val velocity = sqrt(shooter.motionX * shooter.motionX + shooter.motionZ * shooter.motionZ)
        return velocity > 0.05
    }

    private fun matchesAttachmentEntries(
        snapshot: TACZRuntimeSnapshot,
        entries: Set<String>,
        attachmentId: ResourceLocation,
        visitedTags: MutableSet<ResourceLocation>,
    ): Boolean {
        entries.forEach { value ->
            if (value.startsWith(TAG_PREFIX)) {
                val nestedTagId = runCatching { ResourceLocation(value.substring(TAG_PREFIX.length)) }.getOrNull() ?: return@forEach
                if (!visitedTags.add(nestedTagId)) {
                    return@forEach
                }
                val nestedEntries = snapshot.attachmentTags[nestedTagId] ?: return@forEach
                if (matchesAttachmentEntries(snapshot, nestedEntries, attachmentId, visitedTags)) {
                    return true
                }
                return@forEach
            }
            val targetId = runCatching { ResourceLocation(value) }.getOrNull() ?: return@forEach
            if (targetId == attachmentId) {
                return true
            }
        }
        return false
    }

    private fun parseFireModeAdjust(raw: JsonObject, fireMode: FireMode): FireModeAdjust {
        val modeKey = when (fireMode) {
            FireMode.AUTO -> "auto"
            FireMode.SEMI -> "semi"
            FireMode.BURST -> "burst"
            FireMode.UNKNOWN -> return FireModeAdjust()
        }
        val adjustObject = raw.jsonObject("fire_mode_adjust")?.jsonObject(modeKey) ?: return FireModeAdjust()
        return FireModeAdjust(
            damage = adjustObject.floatValue("damage"),
            rpm = adjustObject.intValue("rpm"),
            ammoSpeed = adjustObject.floatValue("speed"),
            armorIgnore = adjustObject.floatValue("armor_ignore"),
            headShot = adjustObject.floatValue("head_shot_multiplier"),
            aimInaccuracy = adjustObject.floatValue("aim_inaccuracy"),
            otherInaccuracy = adjustObject.floatValue("other_inaccuracy"),
        )
    }

    private fun parseInaccuracyDefaults(raw: JsonObject, adjust: FireModeAdjust): Map<String, Float> {
        val inaccuracy = raw.jsonObject("inaccuracy")
        val standBase = inaccuracy?.floatValue(INACCURACY_STAND) ?: DEFAULT_INACCURACY[INACCURACY_STAND]!!
        return linkedMapOf(
            INACCURACY_STAND to (standBase + adjust.otherInaccuracy).coerceAtLeast(0f),
            INACCURACY_MOVE to ((inaccuracy?.floatValue(INACCURACY_MOVE) ?: standBase) + adjust.otherInaccuracy).coerceAtLeast(0f),
            INACCURACY_SNEAK to ((inaccuracy?.floatValue(INACCURACY_SNEAK) ?: standBase) + adjust.otherInaccuracy).coerceAtLeast(0f),
            INACCURACY_LIE to ((inaccuracy?.floatValue(INACCURACY_LIE) ?: standBase) + adjust.otherInaccuracy).coerceAtLeast(0f),
            INACCURACY_AIM to ((inaccuracy?.floatValue(INACCURACY_AIM) ?: DEFAULT_INACCURACY[INACCURACY_AIM]!!) + adjust.aimInaccuracy).coerceAtLeast(0f),
        )
    }

    private fun curveMagnitude(curve: JsonArray?): Float {
        val first = curve?.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return 0f
        val values = first.getAsJsonArray("value")?.mapNotNull { value -> runCatching { abs(value.asFloat) }.getOrNull() }.orEmpty()
        return values.maxOrNull() ?: 0f
    }

    private fun buildRecoilSpline(curve: JsonArray?, modifier: Float): PolynomialSplineFunction? {
        val frames = curve
            ?.mapNotNull { element ->
                val frame = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val time = frame.get("time")?.takeIf { !it.isJsonNull }?.asDouble ?: return@mapNotNull null
                val values = frame.getAsJsonArray("value")?.mapNotNull { value -> runCatching { value.asDouble }.getOrNull() }.orEmpty()
                if (values.size < 2) {
                    return@mapNotNull null
                }
                RecoilKeyFrame(time = time, min = values[0], max = values[1])
            }
            .orEmpty()
        if (frames.isEmpty()) {
            return null
        }
        val times = DoubleArray(frames.size + 1)
        val values = DoubleArray(frames.size + 1)
        times[0] = 0.0
        values[0] = 0.0
        frames.forEachIndexed { index, frame ->
            times[index + 1] = frame.time * 1000.0 + 30.0
            values[index + 1] = (frame.min + Math.random() * (frame.max - frame.min)) * modifier
        }
        return RECOIL_SPLINE_INTERPOLATOR.interpolate(times, values)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private data class RecoilKeyFrame(
        val time: Double,
        val min: Double,
        val max: Double,
    )

    private const val TAG_PREFIX: String = "#"
    private const val INACCURACY_STAND: String = "stand"
    private const val INACCURACY_MOVE: String = "move"
    private const val INACCURACY_SNEAK: String = "sneak"
    private const val INACCURACY_LIE: String = "lie"
    private const val INACCURACY_AIM: String = "aim"

    private val DEFAULT_INACCURACY: Map<String, Float> = linkedMapOf(
        INACCURACY_STAND to 5f,
        INACCURACY_MOVE to 5.75f,
        INACCURACY_SNEAK to 3.5f,
        INACCURACY_LIE to 2.5f,
        INACCURACY_AIM to 0.15f,
    )
}

private fun JsonObject.jsonObject(key: String): JsonObject? =
    get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.floatValue(key: String): Float =
    get(key)?.takeIf { !it.isJsonNull }?.asFloat ?: 0f

private fun JsonObject.intValue(key: String): Int =
    get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0