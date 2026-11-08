package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.item.ItemAppleGold
import net.minecraft.item.ItemFishingRod
import net.minecraft.item.ItemSword
import net.minecraft.util.BlockPos
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.sqrt

// SkyWars engage range. Enemies farther than this (e.g. on other islands across
// the void) are IGNORED: the bot never aims at or chases them.
const val ENGAGE_RANGE: Double = 10.0

/**
 * Melee engine rewritten after duck-dueller-v2 technique and analysis of the
 * player's (imnumbagain) won replays:
 *  - NO fishing rod: the human used the rod 0% of the time across 3 wins.
 *  - Heavy A/D strafing (~20-35% each) with mostly-forward pressure.
 *  - Smooth, capped, distance-scaled aim (not instant GCD snapping).
 *  - Click autoclicker 9-13 CPS, firing only on a real raycast hit.
 *  - Combo-based crit jumps.
 */
object CombatEngine {

    private val mc = Minecraft.getMinecraft()

    // Tracks players with active Strength perk: PlayerName -> Expiry timestamp (ms)
    val strengthMap = ConcurrentHashMap<String, Long>()

    var currentTarget: EntityPlayer? = null
    var isAvoidingStrength = false

    // ---- combat state ----
    private var combo = 0
    private var lastHitMs = 0L
    private var lastClickMs = 0L
    private var nextCps = 95L

    private var strafeDir = 1              // +1 = right (D), -1 = left (A)
    private var nextStrafeSwitchMs = 0L

    private var forwardBlockedUntilMs = 0L // W-tap window
    private var jumpReleaseAtMs = 0L

    private var eatingUntilMs = 0L
    private var lastEatMs = 0L
    private var savedSlotBeforeEat = -1

    fun reset() {
        strengthMap.clear()
        currentTarget = null
        isAvoidingStrength = false
        combo = 0
        eatingUntilMs = 0L
        releaseCombatKeys()
    }

    /** Release every movement/attack key this engine may hold. */
    fun releaseCombatKeys() {
        val gs = mc.gameSettings ?: return
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindAttack.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, false)
    }

    // ---- Strength (Bulldozer) tracking ----
    fun onPlayerKill(killerName: String) {
        val cleanName = killerName.trim().split(" ").last() // strips rank like [VIP+]
        strengthMap[cleanName] = System.currentTimeMillis() + 5000L
    }

    fun hasStrength(player: EntityPlayer): Boolean {
        val exp = strengthMap[player.name] ?: return false
        return System.currentTimeMillis() < exp
    }

    fun getStrengthRemainingSec(player: EntityPlayer): Float {
        val exp = strengthMap[player.name] ?: return 0f
        val rem = exp - System.currentTimeMillis()
        return if (rem > 0) rem / 1000f else 0f
    }

    /**
     * Find nearest alive enemy player WITHIN engage range (default 10 blocks).
     * Anything beyond this is treated as "on another island" and ignored, so the
     * bot never snap-aims across the void at players it cannot reach.
     */
    fun findNearestEnemy(maxDistance: Double = ENGAGE_RANGE): EntityPlayer? {
        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null

        var nearest: EntityPlayer? = null
        var bestDist = maxDistance

        for (other in world.playerEntities) {
            if (other == player || other !is EntityPlayer) continue
            if (other.isDead || other.health <= 0f) continue
            if (other.isInvisibleToPlayer(player)) continue
            if (Math.abs(other.posY - player.posY) > 6.0) continue

            val dist = player.getDistanceToEntity(other).toDouble()
            if (dist < bestDist) {
                bestDist = dist
                nearest = other
            }
        }
        return nearest
    }

    /**
     * Main melee tick. Handles aim, autoclick, strafe, w-tap, crit jumps,
     * gapple heal and anti-strength backpedal. No fishing rod.
     */
    fun tickCombat() {
        val player = mc.thePlayer ?: return
        val gs = mc.gameSettings ?: return
        val now = System.currentTimeMillis()

        currentTarget = findNearestEnemy()
        val target = currentTarget
        if (target == null) {
            isAvoidingStrength = false
            combo = 0
            releaseCombatKeys()
            return
        }

        val dist = player.getDistanceToEntity(target)

        // Decay combo if we've not landed a hit recently or drifted far.
        if (now - lastHitMs > 900 || dist > 5f) combo = 0

        // Always track the target smoothly.
        aimAt(target)

        // Equip best sword (unless mid-eat).
        if (now >= eatingUntilMs) {
            val swordSlot = findBestSwordSlot()
            if (swordSlot != -1 && player.inventory.currentItem != swordSlot) {
                player.inventory.currentItem = swordSlot
            }
        }

        // --- Gapple heal: low HP, have a gapple, not already eating, cooldown ok ---
        if (now >= eatingUntilMs && player.health <= 6f && now - lastEatMs > 6000) {
            val gapSlot = findHotbarGapple()
            if (gapSlot != -1) {
                savedSlotBeforeEat = player.inventory.currentItem
                player.inventory.currentItem = gapSlot
                KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, true)
                eatingUntilMs = now + 1800
                lastEatMs = now
            }
        }
        if (now < eatingUntilMs) {
            // While eating: stand still-ish, keep use held, no attacking.
            KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, true)
            KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
            KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
            return
        } else if (savedSlotBeforeEat != -1) {
            KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, false)
            val swordSlot = findBestSwordSlot()
            player.inventory.currentItem = if (swordSlot != -1) swordSlot else savedSlotBeforeEat
            savedSlotBeforeEat = -1
        }

        // --- Anti-Strength: enemy under Bulldozer strength -> keep distance, no pushing ---
        if (hasStrength(target)) {
            isAvoidingStrength = true
            KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, true)
            if (dist < 7f) {
                // back off, but not off the island
                if (!isVoidTowards(player.posX - target.posX, player.posZ - target.posZ)) {
                    KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
                    KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, true)
                } else {
                    KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
                }
                doStrafe(player, target, now)
            } else {
                KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
                clearStrafe(gs)
            }
            // still trade hits if they walk into reach
            tryAttack(player, gs, target, dist, now)
            return
        }
        isAvoidingStrength = false

        // --- Normal melee ---
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, true)

        // Forward / W-tap spacing (mirrors duck-dueller Combo).
        val wantForward = !(dist < 1.5f || (dist < 2.4f && combo >= 1))
        if (now < forwardBlockedUntilMs) {
            KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
            KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
        } else if (wantForward && !isVoidTowards(target.posX - player.posX, target.posZ - player.posZ)) {
            KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
            KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
        } else {
            KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
            KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
        }

        // Strafe when in a fighting pocket.
        if (dist < 7f) doStrafe(player, target, now) else clearStrafe(gs)

        // Crit jumps on a built combo.
        if (combo >= 3 && dist >= 3.2f && player.onGround && now > jumpReleaseAtMs + 200) {
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, true)
            jumpReleaseAtMs = now + 120
        }
        if (now >= jumpReleaseAtMs) {
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false)
        }

        tryAttack(player, gs, target, dist, now)
    }

    private fun tryAttack(player: EntityPlayer, gs: net.minecraft.client.settings.GameSettings,
                          target: EntityPlayer, dist: Float, now: Long) {
        val over = mc.objectMouseOver
        val aimedAtTarget = over != null && over.entityHit === target
        if ((aimedAtTarget || dist <= 3.0f) && now - lastClickMs >= nextCps) {
            // native swing + direct attack so Vape handles reach/raycast legitimately
            if (!player.isUsingItem) {
                player.swingItem()
                if (aimedAtTarget) {
                    mc.playerController.attackEntity(player, target)
                }
                KeyBinding.setKeyBindState(gs.keyBindAttack.keyCode, true)
                TimeHelper.setTimeout({
                    KeyBinding.setKeyBindState(gs.keyBindAttack.keyCode, false)
                }, (25 + Math.random() * 20).toLong())

                lastClickMs = now
                nextCps = (1000L / (9 + (Math.random() * 5).toInt()))  // ~9-13 CPS
                if (aimedAtTarget) {
                    combo++
                    lastHitMs = now
                    // W-tap: drop forward momentum briefly for extra knockback
                    forwardBlockedUntilMs = now + (60 + Math.random() * 50).toLong()
                }
            }
        }
    }

    private fun doStrafe(player: EntityPlayer, target: EntityPlayer, now: Long) {
        val gs = mc.gameSettings ?: return
        if (now > nextStrafeSwitchMs) {
            // When closing a combo, bias strafe toward the enemy's back.
            if (combo > 2) {
                val dyaw = wrap180(angleToYaw(target.posX - player.posX, target.posZ - player.posZ) - player.rotationYaw)
                strafeDir = if (dyaw < 0) -1 else 1
            } else {
                strafeDir = -strafeDir
            }
            nextStrafeSwitchMs = now + (280 + Math.random() * 420).toLong()
        }
        // perpendicular-to-target direction for the current strafe side
        val tx = target.posX - player.posX
        val tz = target.posZ - player.posZ
        // right vector relative to facing the target: (dz, -dx) ; left is negation
        val px = if (strafeDir > 0) tz else -tz
        val pz = if (strafeDir > 0) -tx else tx
        if (isVoidTowards(px, pz)) {
            // edge on that side: flip once; if both bad, don't strafe
            strafeDir = -strafeDir
            val px2 = if (strafeDir > 0) tz else -tz
            val pz2 = if (strafeDir > 0) -tx else tx
            if (isVoidTowards(px2, pz2)) { clearStrafe(gs); return }
        }
        if (strafeDir > 0) {
            KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, true)
            KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, false)
        } else {
            KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, true)
            KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, false)
        }
    }

    private fun clearStrafe(gs: net.minecraft.client.settings.GameSettings) {
        KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, false)
    }

    // ---- aim ----
    fun lookAtEntity(target: Entity) = aimAt(target)

    private fun aimAt(target: Entity) {
        val player = mc.thePlayer ?: return
        val dx = target.posX - player.posX
        val dz = target.posZ - player.posZ
        val dy = (target.posY + target.getEyeHeight() * 0.9) - (player.posY + player.getEyeHeight())
        val distXZ = sqrt(dx * dx + dz * dz)

        val targetYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val targetPitch = (-Math.toDegrees(atan2(dy, distXZ))).toFloat().coerceIn(-90f, 90f)

        val dist3 = player.getDistanceToEntity(target)
        val factor = when {
            dist3 <= 10f -> 1.0f
            dist3 <= 20f -> 0.6f
            else -> 0.4f
        }
        val maxYaw = 9f * factor
        val maxPitch = 5f * factor

        var dyaw = wrap180(targetYaw - player.rotationYaw) + (Math.random() * 2 - 1).toFloat()
        var dpitch = (targetPitch - player.rotationPitch) + (Math.random() * 1.2 - 0.6).toFloat()

        if (dyaw > maxYaw) dyaw = maxYaw
        if (dyaw < -maxYaw) dyaw = -maxYaw
        if (dpitch > maxPitch) dpitch = maxPitch
        if (dpitch < -maxPitch) dpitch = -maxPitch

        player.rotationYaw += dyaw
        player.rotationPitch = (player.rotationPitch + dpitch).coerceIn(-90f, 90f)
    }

    private fun angleToYaw(dx: Double, dz: Double): Float =
        (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()

    private fun wrap180(ang: Float): Float {
        var a = ang % 360f
        if (a < -180f) a += 360f
        if (a > 180f) a -= 360f
        return a
    }

    /** True if stepping ~1 block along (dx,dz) would leave solid ground (void). */
    private fun isVoidTowards(dx: Double, dz: Double): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false
        val len = sqrt(dx * dx + dz * dz)
        if (len < 0.01) return false
        val nx = dx / len
        val nz = dz / len
        val checkX = player.posX + nx * 1.1
        val checkZ = player.posZ + nz * 1.1
        val feet = BlockPos(checkX, player.posY - 0.2, checkZ)
        for (d in 0..1) {
            val p = BlockPos(feet.x, feet.y - d, feet.z)
            val b = world.getBlockState(p).block
            if (b != Blocks.air && b.isCollidable) return false
        }
        return true
    }

    // ---- inventory helpers ----
    private fun findHotbarGapple(): Int {
        val player = mc.thePlayer ?: return -1
        for (i in 0..8) {
            val s = player.inventory.getStackInSlot(i)
            if (s != null && s.item is ItemAppleGold) return i
        }
        return -1
    }

    @Suppress("unused")
    private fun findHotbarRod(): Int {
        val player = mc.thePlayer ?: return -1
        for (i in 0..8) {
            val s = player.inventory.getStackInSlot(i)
            if (s != null && s.item is ItemFishingRod) return i
        }
        return -1
    }

    private fun findBestSwordSlot(): Int {
        val player = mc.thePlayer ?: return -1
        var bestSlot = -1
        var bestScore = 0f
        for (i in 0..8) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack != null && stack.item is ItemSword) {
                val score = ItemFilter.getSwordScore(stack)
                if (score > bestScore) {
                    bestScore = score
                    bestSlot = i
                }
            }
        }
        return bestSlot
    }
}
