package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.settings.KeyBinding
import net.minecraft.init.Blocks
import net.minecraft.item.ItemBlock
import net.minecraft.util.BlockPos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Push / mid-rush helper. We do NOT hand-place blocks any more: the player uses
 * Vape's Scaffold, which auto-bridges when you hold right-click at the edge of a
 * block. So pushing = walk toward the target with blocks selected, and hold
 * right-click whenever we're at/over an edge so Scaffold kicks in.
 */
object SkyBridge {

    private val mc = Minecraft.getMinecraft()

    var isBridging = false
    private var rmbDown = false
    private var lastJumpMs = 0L

    /** Walk toward [target] and let Vape Scaffold bridge the gaps. Returns false if no blocks. */
    fun tickPush(target: BlockPos): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false
        val gs = mc.gameSettings ?: return false

        val blockSlot = findHotbarBlocks()
        if (blockSlot == -1) { stopPush(); return false }
        if (player.inventory.currentItem != blockSlot) player.inventory.currentItem = blockSlot

        isBridging = true

        // Face toward the target (yaw only — leave pitch to Vape Scaffold's own aim).
        val dx = (target.x + 0.5) - player.posX
        val dz = (target.z + 0.5) - player.posZ
        val targetYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        var dyaw = targetYaw - player.rotationYaw
        dyaw %= 360f
        if (dyaw < -180f) dyaw += 360f
        if (dyaw > 180f) dyaw -= 360f
        val step = 12f
        if (dyaw > step) dyaw = step
        if (dyaw < -step) dyaw = -step
        player.rotationYaw += dyaw
        // slight downward tilt helps Scaffold target the edge block
        if (player.rotationPitch < 30f) player.rotationPitch = 45f

        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, true)

        // Are we at/over an edge? If so hold right-click so Scaffold places; else release
        // so we don't drop blocks onto solid ground.
        val overVoid = isAir(world, player.posX, player.posY - 0.3, player.posZ) &&
            isAir(world, player.posX, player.posY - 1.3, player.posZ)
        val aheadVoid = voidAhead(player, dx, dz)
        if (overVoid || aheadVoid) holdRmb(gs) else releaseRmb(gs)

        // climb onto higher island edges
        val now = System.currentTimeMillis()
        if (player.isCollidedHorizontally && player.onGround && now - lastJumpMs > 350) {
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, true)
            lastJumpMs = now
            TimeHelper.setTimeout({ KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false) }, 110)
        }
        return true
    }

    fun stopPush() {
        isBridging = false
        val gs = mc.gameSettings ?: return
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false)
        releaseRmb(gs)
    }

    /** Kept for compatibility with older callers. */
    fun stopBridge() = stopPush()

    private fun holdRmb(gs: net.minecraft.client.settings.GameSettings) {
        if (!rmbDown) {
            rmbDown = true
            KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, true)
        }
    }

    private fun releaseRmb(gs: net.minecraft.client.settings.GameSettings) {
        if (rmbDown) {
            rmbDown = false
            KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, false)
        }
    }

    private fun isAir(world: net.minecraft.world.World, x: Double, y: Double, z: Double): Boolean {
        val b = world.getBlockState(BlockPos(x, y, z)).block
        return b == Blocks.air || !b.isCollidable
    }

    private fun voidAhead(player: net.minecraft.entity.player.EntityPlayer, dx: Double, dz: Double): Boolean {
        val world = mc.theWorld ?: return false
        val len = sqrt(dx * dx + dz * dz)
        if (len < 0.01) return false
        val nx = dx / len; val nz = dz / len
        val cx = player.posX + nx * 1.1; val cz = player.posZ + nz * 1.1
        return isAir(world, cx, player.posY - 0.3, cz) && isAir(world, cx, player.posY - 1.3, cz)
    }

    fun findHotbarBlocks(): Int {
        val player = mc.thePlayer ?: return -1
        for (i in 0..8) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack != null && stack.item is ItemBlock) {
                val block = (stack.item as ItemBlock).block
                if (block.isCollidable && block != Blocks.air) return i
            }
        }
        return -1
    }
}
