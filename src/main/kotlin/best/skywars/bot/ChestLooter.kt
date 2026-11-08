package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.inventory.ContainerChest
import net.minecraft.tileentity.TileEntityChest
import net.minecraft.util.BlockPos
import kotlin.math.atan2
import kotlin.math.sqrt

object ChestLooter {

    private val mc = Minecraft.getMinecraft()

    val lootedChests = HashSet<BlockPos>()
    var targetChestPos: BlockPos? = null

    private var openTicks = 0
    private var approachTicks = 0
    private var lastLootTick = 0L

    fun reset() {
        lootedChests.clear()
        targetChestPos = null
        openTicks = 0
        approachTicks = 0
    }

    /**
     * Nearest un-looted chest to the PLAYER (not to a frozen landing point — on
     * Hypixel SkyWars the cage floats above the island, so a landing-relative
     * height filter wrongly rejected the island chests). These are exactly the
     * chests Vape StorageESP highlights.
     */
    fun findNextChest(origin: BlockPos): BlockPos? {
        val world = mc.theWorld ?: return null
        val player = mc.thePlayer ?: return null
        var best: BlockPos? = null
        var bestD2 = 18.0 * 18.0
        for (tile in world.loadedTileEntityList) {
            if (tile is TileEntityChest) {
                val pos = tile.pos
                if (lootedChests.contains(pos)) continue
                // Ignore chests far below us (another island under the map).
                if (Math.abs(pos.y - player.posY) > 10.0) continue
                val d2 = player.getDistanceSq(pos)
                if (d2 < bestD2) { bestD2 = d2; best = pos }
            }
        }
        return best
    }

    fun tickLooting(): Boolean {
        val player = mc.thePlayer ?: return false
        val screen = mc.currentScreen
        if (screen is GuiChest) {
            openTicks++
            approachTicks = 0
            val container = screen.inventorySlots
            if (container is ContainerChest) {
                val chestInv = container.lowerChestInventory
                val now = System.currentTimeMillis()
                if (now - lastLootTick >= 70) {
                    var foundValuable = false
                    for (i in 0 until chestInv.sizeInventory) {
                        val stack = chestInv.getStackInSlot(i)
                        if (stack != null && ItemFilter.shouldLoot(stack, player)) {
                            mc.playerController.windowClick(container.windowId, i, 0, 1, player)
                            lastLootTick = now
                            foundValuable = true
                            break
                        }
                    }
                    if (!foundValuable || openTicks > 20) {
                        targetChestPos?.let { lootedChests.add(it); targetChestPos = null }
                        player.closeScreen()
                        openTicks = 0
                        return true
                    }
                }
            }
            return false
        }
        return false
    }

    /** Returns true if we've been stuck trying to reach the chest for too long. */
    fun tickApproach(): Boolean {
        approachTicks++
        if (approachTicks > 60) { // ~3s
            targetChestPos?.let { lootedChests.add(it) }
            targetChestPos = null
            approachTicks = 0
            return true
        }
        return false
    }

    /** Quick capped-step aim at the chest so the bot actually turns toward it. */
    fun lookAtBlock(pos: BlockPos) {
        val player = mc.thePlayer ?: return
        val dx = (pos.x + 0.5) - player.posX
        val dy = (pos.y + 0.5) - (player.posY + player.getEyeHeight())
        val dz = (pos.z + 0.5) - player.posZ
        val dist = sqrt(dx * dx + dz * dz)

        val targetYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val targetPitch = (-Math.toDegrees(atan2(dy, dist))).toFloat().coerceIn(-70f, 70f)

        var dyaw = targetYaw - player.rotationYaw
        dyaw %= 360f
        if (dyaw < -180f) dyaw += 360f
        if (dyaw > 180f) dyaw -= 360f
        val yStep = 24f
        if (dyaw > yStep) dyaw = yStep
        if (dyaw < -yStep) dyaw = -yStep
        player.rotationYaw += dyaw

        var dpitch = targetPitch - player.rotationPitch
        val pStep = 16f
        if (dpitch > pStep) dpitch = pStep
        if (dpitch < -pStep) dpitch = -pStep
        player.rotationPitch = (player.rotationPitch + dpitch).coerceIn(-90f, 90f)
    }
}
