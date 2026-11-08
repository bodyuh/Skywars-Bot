package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.settings.KeyBinding
import net.minecraft.init.Blocks
import net.minecraft.item.ItemBlock
import net.minecraft.util.BlockPos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Mid-rush / bridge helper driving Vape's Scaffold (Legit mode, whitelist
 * blocks+wool, require-sneak OFF). Technique matches the player's rule and the
 * bedwars bridger: face the target, LOOK DOWN at the block corner (pitch ~78°),
 * hold right-click the whole time so Scaffold keeps placing, and walk forward.
 * The previous version used pitch 45 and only held RMB ~11% of the time, so no
 * blocks were placed and the bot fell into the void.
 */
object SkyBridge {

    private val mc = Minecraft.getMinecraft()

    var isBridging = false
    private var rmbDown = false
    private var lastJumpMs = 0L

    /** Bridge toward [target] with Vape Scaffold. Returns false if we have no blocks. */
    fun tickPush(target: BlockPos): Boolean {
        val player = mc.thePlayer ?: return false
        val gs = mc.gameSettings ?: return false

        val blockSlot = findHotbarBlocks()
        if (blockSlot == -1) { stopPush(); return false }
        if (player.inventory.currentItem != blockSlot) player.inventory.currentItem = blockSlot

        isBridging = true

        // Face the target, and look DOWN at the edge/corner so Scaffold can place.
        val dx = (target.x + 0.5) - player.posX
        val dz = (target.z + 0.5) - player.posZ
        player.rotationYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        player.rotationPitch = 76f

        // LEGIT Scaffold places at human speed, so we must NOT outrun it off the
        // edge. SNEAK the whole time: sneaking stops us walking off a ledge until
        // the block is actually placed. Then forward + hold right-click.
        KeyBinding.setKeyBindState(gs.keyBindSneak.keyCode, true)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
        holdRmb(gs)

        // Block-jump onto an island edge that's 1-2 blocks up/ahead (release sneak
        // briefly for the hop so we can actually climb).
        val now = System.currentTimeMillis()
        if (player.isCollidedHorizontally && player.onGround && now - lastJumpMs > 300) {
            KeyBinding.setKeyBindState(gs.keyBindSneak.keyCode, false)
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, true)
            lastJumpMs = now
            TimeHelper.setTimeout({ KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false) }, 120)
        }
        return true
    }

    fun stopPush() {
        isBridging = false
        val gs = mc.gameSettings ?: return
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSneak.keyCode, false)
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
