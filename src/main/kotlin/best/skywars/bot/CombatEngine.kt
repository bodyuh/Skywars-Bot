package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.settings.GameSettings
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.item.ItemSword
import net.minecraft.util.BlockPos
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// SkyWars engage range. Enemies farther than this (e.g. on other islands across
// the void) are IGNORED: the bot never aims at or chases them.
const val ENGAGE_RANGE: Double = 10.0

/**
 * Combat "brain". The player runs Vape with a full combat suite (AimAssist,
 * AutoClicker, HitSelect, BlockHit, JumpReset, KnockbackDelay, reach, keepsprint,
 * Scaffold, FastPlace). So this engine does NOT do its own fine aiming, clicking,
 * crit-jumping or item use — that fought Vape and froze the bot. It only:
 *   - picks the target and the best sword,
 *   - coarse-faces the enemy so Vape AimAssist can take over,
 *   - holds attack so Vape AutoClicker/HitSelect/reach land the hits,
 *   - strafes and spaces,
 *   - bridges toward an enemy across a gap by holding right-click (Vape Scaffold),
 *   - backs off when the enemy has Bulldozer strength.
 * Every key it may hold is released by [releaseCombatKeys]; the tick handler is
 * wrapped in try/catch so a thrown exception can never leave keys locked.
 */
object CombatEngine {

    private val mc = Minecraft.getMinecraft()

    val strengthMap = ConcurrentHashMap<String, Long>()

    var currentTarget: EntityPlayer? = null
    var isAvoidingStrength = false

    private var strafeDir = 1
    private var nextStrafeSwitchMs = 0L
    private var attackDown = false
    private var rmbDown = false
    private var lastJumpMs = 0L

    fun reset() {
        strengthMap.clear()
        currentTarget = null
        isAvoidingStrength = false
        releaseCombatKeys()
    }

    /** Release every key this engine can hold. Called on reset / leaving combat. */
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
        attackDown = false
        rmbDown = false
    }

    // ---- Strength (Bulldozer) tracking ----
    fun onPlayerKill(killerName: String) {
        val cleanName = killerName.trim().split(" ").last()
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

    fun findNearestEnemy(maxDistance: Double = ENGAGE_RANGE): EntityPlayer? {
        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null
        var nearest: EntityPlayer? = null
        var bestDist = maxDistance
        for (other in world.playerEntities) {
            if (other == player || other !is EntityPlayer) continue
            if (other.isDead || other.health <= 0f) continue
            if (other.isInvisibleToPlayer(player)) continue
            if (abs(other.posY - player.posY) > 6.0) continue
            val dist = player.getDistanceToEntity(other).toDouble()
            if (dist < bestDist) { bestDist = dist; nearest = other }
        }
        return nearest
    }

    /** Main melee tick. Delegates the actual PvP mechanics to Vape. */
    fun tickCombat() {
        val player = mc.thePlayer ?: return
        val gs = mc.gameSettings ?: return
        val now = System.currentTimeMillis()

        currentTarget = findNearestEnemy()
        val target = currentTarget
        if (target == null) {
            isAvoidingStrength = false
            releaseCombatKeys()
            return
        }

        val dist = player.getDistanceToEntity(target)
        val voidToTarget = isVoidTowards(target.posX - player.posX, target.posZ - player.posZ)

        // Coarse facing only — Vape AimAssist does the precise tracking.
        coarseFace(target)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, true)

        // --- Anti-strength: enemy under Bulldozer -> back off, don't trade ---
        if (hasStrength(target) && dist < 7f) {
            isAvoidingStrength = true
            setAttack(gs, false)
            setRmb(gs, false)
            selectSword(player)
            val backVoid = isVoidTowards(player.posX - target.posX, player.posZ - target.posZ)
            if (!backVoid) {
                KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
                KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, true)
            } else {
                KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
            }
            doStrafe(player, target, now)
            return
        }
        isAvoidingStrength = false
        KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)

        // --- Enemy across a gap: bridge toward them with Vape Scaffold ---
        if (dist > 3.3f && voidToTarget) {
            val blocks = SkyBridge.findHotbarBlocks()
            if (blocks != -1) {
                if (player.inventory.currentItem != blocks) player.inventory.currentItem = blocks
                setAttack(gs, false)
                clearStrafe(gs)
                if (player.rotationPitch < 30f) player.rotationPitch = 44f // help Scaffold find the edge
                KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
                setRmb(gs, true)
                predictiveJump(player, gs, now)
            } else {
                // No blocks to cross: hold ground, keep facing, no falling.
                KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
                clearStrafe(gs)
                setRmb(gs, false)
                setAttack(gs, false)
            }
            return
        }

        // --- Melee on reachable ground: let Vape do the hitting ---
        setRmb(gs, false)
        selectSword(player)
        setAttack(gs, true) // Vape AutoClicker/HitSelect/reach convert the held LMB into hits

        if (dist > 2.3f && !voidToTarget) {
            KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
        } else {
            KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
        }

        if (dist < 7f) doStrafe(player, target, now) else clearStrafe(gs)

        // only jump to climb a block we're stuck on (combat crits are Vape JumpReset)
        if (player.isCollidedHorizontally) predictiveJump(player, gs, now)
    }

    // ---- helpers ----
    private fun doStrafe(player: EntityPlayer, target: EntityPlayer, now: Long) {
        val gs = mc.gameSettings ?: return
        if (now > nextStrafeSwitchMs) {
            strafeDir = -strafeDir
            nextStrafeSwitchMs = now + (280 + Math.random() * 420).toLong()
        }
        val tx = target.posX - player.posX
        val tz = target.posZ - player.posZ
        var px = if (strafeDir > 0) tz else -tz
        var pz = if (strafeDir > 0) -tx else tx
        if (isVoidTowards(px, pz)) {
            strafeDir = -strafeDir
            px = if (strafeDir > 0) tz else -tz
            pz = if (strafeDir > 0) -tx else tx
            if (isVoidTowards(px, pz)) { clearStrafe(gs); return }
        }
        if (strafeDir > 0) {
            KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, true)
            KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, false)
        } else {
            KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, true)
            KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, false)
        }
    }

    private fun clearStrafe(gs: GameSettings) {
        KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, false)
    }

    private fun predictiveJump(player: EntityPlayer, gs: GameSettings, now: Long) {
        if (player.onGround && now - lastJumpMs > 350) {
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, true)
            lastJumpMs = now
            TimeHelper.setTimeout({ KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false) }, 110)
        }
    }

    private fun setAttack(gs: GameSettings, down: Boolean) {
        if (down != attackDown) {
            attackDown = down
            KeyBinding.setKeyBindState(gs.keyBindAttack.keyCode, down)
        }
    }

    private fun setRmb(gs: GameSettings, down: Boolean) {
        if (down != rmbDown) {
            rmbDown = down
            KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, down)
        }
    }

    private fun selectSword(player: EntityPlayer) {
        val s = findBestSwordSlot()
        if (s != -1 && player.inventory.currentItem != s) player.inventory.currentItem = s
    }

    /** Coarse aim: only correct when badly off so we don't fight Vape AimAssist. */
    fun lookAtEntity(target: Entity) = coarseFace(target)

    private fun coarseFace(target: Entity) {
        val player = mc.thePlayer ?: return
        val dx = target.posX - player.posX
        val dz = target.posZ - player.posZ
        val dy = (target.posY + target.getEyeHeight() * 0.9) - (player.posY + player.getEyeHeight())
        val distXZ = sqrt(dx * dx + dz * dz)
        val targetYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val targetPitch = (-Math.toDegrees(atan2(dy, distXZ))).toFloat().coerceIn(-90f, 90f)

        var dyaw = wrap180(targetYaw - player.rotationYaw)
        if (abs(dyaw) > 30f) {
            if (dyaw > 12f) dyaw = 12f
            if (dyaw < -12f) dyaw = -12f
            player.rotationYaw += dyaw
        }
        var dpitch = targetPitch - player.rotationPitch
        if (abs(dpitch) > 25f) {
            if (dpitch > 8f) dpitch = 8f
            if (dpitch < -8f) dpitch = -8f
            player.rotationPitch = (player.rotationPitch + dpitch).coerceIn(-90f, 90f)
        }
    }

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

    private fun findBestSwordSlot(): Int {
        val player = mc.thePlayer ?: return -1
        var bestSlot = -1
        var bestScore = 0f
        for (i in 0..8) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack != null && stack.item is ItemSword) {
                val score = ItemFilter.getSwordScore(stack)
                if (score > bestScore) { bestScore = score; bestSlot = i }
            }
        }
        return bestSlot
    }
}
