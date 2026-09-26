package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.event.GunMeleeEvent
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageMelee
import com.tacz.legacy.common.resource.AttachmentMeleeCombatData
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.GunDefaultMeleeCombatData
import com.tacz.legacy.common.resource.GunMeleeCombatData
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraft.util.DamageSource
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.Vec3d
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.relauncher.Side
import kotlin.math.sqrt

/**
 * 服务端近战逻辑。与上游 TACZ LivingEntityMelee 行为一致。
 */
public class LivingEntityMelee(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
) {
    /**
     * 发起近战攻击。
     */
    public fun melee() {
        val supplier = data.currentGunItem ?: return
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return

        val meleeData = gunData.meleeData ?: return

        // 冷却检查
        val meleeCoolDown = getMeleeCoolDown()
        if (meleeCoolDown > 0) return
        // 切枪中不可近战
        if (draw.getDrawCoolDown() != 0L) return
        // 换弹中不可近战
        if (data.reloadStateType.isReloading()) return

        // 发布服务端 Forge 事件
        val meleeEvent = GunMeleeEvent(shooter, currentGunItem, Side.SERVER)
        if (MinecraftForge.EVENT_BUS.post(meleeEvent)) return

        data.meleeTimestamp = System.currentTimeMillis()
        data.meleePrepTickCount = 0

        // 广播近战开始
        TACZNetworkHandler.sendToTrackingEntity(ServerMessageMelee(shooter.entityId, currentGunItem), shooter)

        // 如果有 prep time，延迟伤害；否则立即执行
        val defaultMeleeData = meleeData.defaultMeleeData
        if (defaultMeleeData != null && defaultMeleeData.prepTime <= 0f) {
            val effective = resolveEffectiveMeleeData(currentGunItem, iGun, meleeData) ?: return
            executeDefaultMeleeDamage(effective)
        }
    }

    /**
     * 把枪上已安装配件的近战数据(如刺刀 / 枪托)叠加到枪械默认近战上。
     *
     * 上游 TACZ 会通过 GunDataAccessor.getAttachmentMeleeData 参与近战结算，
     * 这里此前完全没有接入，导致刺刀等配件只改模型、不改伤害。
     * 伤害 / 距离 / 角度 / 击退取"枪与配件中的更优值"，避免多配件叠乘出异常数值。
     */
    private fun resolveEffectiveMeleeData(
        gunItem: ItemStack,
        iGun: IGun,
        meleeData: GunMeleeCombatData,
    ): GunDefaultMeleeCombatData? {
        val base = meleeData.defaultMeleeData ?: return null
        var damage = base.damage
        // 枪包注释的语义：枪械的 distance 是"延升"量，要和 default/配件的 distance 加和。
        // 只取 default.distance 会让攻击距离少掉一截，近战几乎打不到人。
        var distance = base.distance + meleeData.distance
        var rangeAngle = base.rangeAngle
        var knockback = base.knockback

        for (type in AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue
            val attachmentId = iGun.getAttachmentId(gunItem, type)
            val builtInId = iGun.getBuiltInAttachmentId(gunItem, type)
            val melee = resolveAttachmentMelee(attachmentId) ?: resolveAttachmentMelee(builtInId) ?: continue
            if (melee.damage > 0f) damage = maxOf(damage, melee.damage)
            // 配件的 distance 替换 default 的一截，再与枪械的延升值相加
            distance = (distance - base.distance) + maxOf(base.distance, melee.distance)
            if (melee.rangeAngle > 0f) rangeAngle = maxOf(rangeAngle, melee.rangeAngle)
            if (melee.knockback > 0f) knockback = maxOf(knockback, melee.knockback)
        }

        val changed = damage != base.damage ||
            distance != base.distance ||
            rangeAngle != base.rangeAngle ||
            knockback != base.knockback
        if (!changed) {
            return base
        }
        return GunDefaultMeleeCombatData(
            animationType = base.animationType,
            prepTime = base.prepTime,
            cooldown = base.cooldown,
            damage = damage,
            distance = distance,
            rangeAngle = rangeAngle,
            knockback = knockback,
        )
    }

    private fun resolveAttachmentMelee(attachmentId: net.minecraft.util.ResourceLocation?): AttachmentMeleeCombatData? {
        if (attachmentId == null || attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return null
        }
        return GunDataAccessor.getAttachmentMeleeData(attachmentId)
    }

    /**
     * 每 tick 检查近战 prep 阶段是否需要执行伤害。
     */
    public fun tickMelee() {
        if (data.meleePrepTickCount < 0) return
        val supplier = data.currentGunItem ?: return
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return

        val meleeData = gunData.meleeData ?: return
        val defaultMeleeData = meleeData.defaultMeleeData ?: return

        if (defaultMeleeData.prepTime > 0f) {
            data.meleePrepTickCount++
            val elapsed = data.meleePrepTickCount * 50L // 50ms/tick
            if (elapsed >= (defaultMeleeData.prepTime * 1000).toLong()) {
                val effective = resolveEffectiveMeleeData(currentGunItem, iGun, meleeData)
                if (effective != null) {
                    executeDefaultMeleeDamage(effective)
                }
                data.meleePrepTickCount = -1
            }
        }
    }

    public fun getMeleeCoolDown(): Long {
        val supplier = data.currentGunItem ?: return 0
        val currentGunItem = supplier.get()
        val iGun = currentGunItem.item as? IGun ?: return 0
        val gunId = iGun.getGunId(currentGunItem)
        val gunData = GunDataAccessor.getGunData(gunId) ?: return -1

        val meleeData = gunData.meleeData ?: return 0
        if (data.meleeTimestamp < 0) return 0

        val elapsed = System.currentTimeMillis() - data.meleeTimestamp
        // 枪包语义：枪械 cooldown 与 default 的 cooldown 做加和
        val totalCooldown = meleeData.cooldown + (meleeData.defaultMeleeData?.cooldown ?: 0f)
        val coolDown = (totalCooldown * 1000).toLong() - elapsed
        return if (coolDown < 0) 0 else coolDown
    }

    private fun executeDefaultMeleeDamage(mData: GunDefaultMeleeCombatData) {
        val look = shooter.lookVec
        val eyePos = Vec3d(shooter.posX, shooter.posY + shooter.eyeHeight, shooter.posZ)

        // 以眼睛为中心做立方体搜索，而不是 eyePos→end 的线段盒。
        // 线段盒会让"搜索范围"随视线方向漂移，垂直上下看时甚至退化成一个点。
        val reach = mData.distance.toDouble().coerceAtLeast(1.0)
        val searchBox = AxisAlignedBB(
            eyePos.x - reach, eyePos.y - reach, eyePos.z - reach,
            eyePos.x + reach, eyePos.y + reach, eyePos.z + reach,
        )
        val candidates = shooter.world.getEntitiesWithinAABBExcludingEntity(shooter, searchBox)
        // 保持原有语义：rangeAngle 直接作为半角参与判定，避免行为回归。
        val halfAngleRad = Math.toRadians(mData.rangeAngle.toDouble().coerceIn(1.0, 180.0))
        // 视线的水平分量，用于水平夹角判定
        val lookHorizontal = sqrt(look.x * look.x + look.z * look.z)

        for (entity in candidates) {
            if (entity !is EntityLivingBase) continue
            if (!entity.isEntityAlive) continue

            // 用包围盒上离眼睛最近的点，而不是实体中心。
            // 中心点对大体积目标或贴身目标会明显跑偏(明明贴着却判不中)。
            val closest = closestPointOnBox(entity.entityBoundingBox, eyePos)
            val dx = closest.x - eyePos.x
            val dy = closest.y - eyePos.y
            val dz = closest.z - eyePos.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > mData.distance) continue

            // 只判定水平夹角，垂直方向交给上面的距离限制。
            // 若用 3D 夹角，玩家平视时打脚下的矮怪会因垂直分量而夹角超标打不中，
            // 这是"判定别扭"的主要来源。
            val horizontal = sqrt(dx * dx + dz * dz)
            if (horizontal > 1.0E-6 && lookHorizontal > 1.0E-6) {
                val cos = ((dx * look.x + dz * look.z) / (horizontal * lookHorizontal)).coerceIn(-1.0, 1.0)
                if (Math.acos(cos) > halfAngleRad) continue
            }

            // 关键：必须清空目标的无敌帧。
            // 否则刚被子弹命中过的目标仍处于 hurtResistantTime 中，
            // 而近战伤害通常远小于子弹伤害，会被 attackEntityFrom 直接吞掉(返回 false)，
            // 表现为"近战完全打不出伤害"。
            entity.hurtResistantTime = 0
            val applied = entity.attackEntityFrom(createMeleeDamageSource(), mData.damage)
            if (applied) {
                // 击退取水平方向，避免把目标垂直弹飞
                val knockDir = if (horizontal > 1.0E-6) {
                    Vec3d(dx / horizontal, 0.0, dz / horizontal)
                } else {
                    Vec3d(look.x, 0.0, look.z).normalize()
                }
                applyMeleeKnockback(entity, mData.knockback, knockDir)
            }
        }
    }

    /** 包围盒上距离给定点最近的坐标（点在盒内时返回该点本身）。 */
    private fun closestPointOnBox(box: AxisAlignedBB, point: Vec3d): Vec3d {
        return Vec3d(
            point.x.coerceIn(box.minX, box.maxX),
            point.y.coerceIn(box.minY, box.maxY),
            point.z.coerceIn(box.minZ, box.maxZ),
        )
    }

    /**
     * 近战伤害源。玩家用 player 类型；非玩家(如持枪生物)退回 mob 类型，
     * 避免旧实现里 `shooter as? EntityPlayer ?: continue` 把非玩家 shooter 的近战整个作废。
     */
    private fun createMeleeDamageSource(): DamageSource {
        return if (shooter is net.minecraft.entity.player.EntityPlayer) {
            DamageSource.causePlayerDamage(shooter)
        } else {
            net.minecraft.util.EntityDamageSource("mob", shooter)
        }
    }

    private fun applyMeleeKnockback(target: EntityLivingBase, knockback: Float, direction: Vec3d) {
        if (knockback <= 0.0f) return
        target.addVelocity(direction.x * knockback * 0.6, 0.1, direction.z * knockback * 0.6)
        target.velocityChanged = true
    }
}
