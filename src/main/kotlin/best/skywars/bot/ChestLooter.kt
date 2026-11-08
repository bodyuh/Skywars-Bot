package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.init.Blocks
import net.minecraft.inventory.ContainerChest
import net.minecraft.tileentity.TileEntityChest
import net.minecraft.util.BlockPos
import kotlin.math.abs
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
     * Find next chest strictly on the surface platform
     * IGNORES all chests below the floor level (cave/sub-island chests)!
     */
    fun findNextChest(landingPos: BlockPos): BlockPos? {
        val world = mc.theWorld ?: return null
        val player = mc.thePlayer ?: return null

        val islandChests = mutableListOf<TileEntityChest>()
        for (tile in world.loadedTileEntityList) {
            if (tile is TileEntityChest) {
                val pos = tile.pos
                if (lootedChests.contains(pos)) continue

                // Check horizontal distance from island landing point
                val dx = (pos.x - landingPos.x).toDouble()
                val dz = (pos.z - landingPos.z).toDouble()
                val horizontalDistSq = dx * dx + dz * dz
                if (horizontalDistSq > 22.0 * 22.0) continue // Too far from spawn island

                // Height check relative to where the player landed on the island.
                // Wide downward range so a landing on top of a tree can still see the
                // ground chests below (the A* pathfinder walks us down to them).
                val dy = pos.y - landingPos.y
                if (dy < -8 || dy > 5) continue

                // Ensure the chest is NOT buried underground (block directly above chest MUST be AIR)
                val blockAbove = world.getBlockState(pos.up()).block
                if (blockAbove != Blocks.air) continue

                islandChests.add(tile)
            }
        }

        if (islandChests.isEmpty()) return null

        // Sort by closest horizontal distance on platform
        islandChests.sortWith(Comparator { c1, c2 ->
            val d1 = player.getDistanceSq(c1.pos)
            val d2 = player.getDistanceSq(c2.pos)
            d1.compareTo(d2)
        })

        return islandChests.firstOrNull()?.pos
    }

    fun tickLooting(): Boolean {
        val player = mc.thePlayer ?: return false
        val screen = mc.currentScreen

        if (screen is GuiChest) {
            openTicks++
            approachTicks = 0 // Reset approach counter once chest is open
            val container = screen.inventorySlots
            if (container is ContainerChest) {
                val chestInv = container.lowerChestInventory
                val now = System.currentTimeMillis()

                // Fast shift-click loot delay ~70ms
                if (now - lastLootTick >= 70) {
                    var foundValuable = false
                    for (i in 0 until chestInv.sizeInventory) {
                        val stack = chestInv.getStackInSlot(i)
                        // Use smart filter that compares against current inventory & sword enchant
                        if (stack != null && ItemFilter.shouldLoot(stack, player)) {
                            mc.playerController.windowClick(
                                container.windowId,
                                i,
                                0,
                                1, // Shift-click
                                player
                            )
                            lastLootTick = now
                            foundValuable = true
                            break
                        }
                    }

                    // If no valuable items remain or timeout exceeded (20 ticks = 1.0s)
                    if (!foundValuable || openTicks > 20) {
                        if (targetChestPos != null) {
                            lootedChests.add(targetChestPos!!)
                            targetChestPos = null
                        }
                        player.closeScreen()
                        openTicks = 0
                        return true // Finished this chest
                    }
                }
            }
            return false
        }

        return false
    }

    /**
     * Call every tick while walking towards chest
     * Returns true if stuck too long (skips chest)
     */
    fun tickApproach(): Boolean {
        approachTicks++
        if (approachTicks > 70) { // 3.5 seconds stuck without opening
            val badChest = targetChestPos
            if (badChest != null) {
                lootedChests.add(badChest) // Mark as done to avoid retrying
            }
            targetChestPos = null
            approachTicks = 0
            return true // Stuck, skip it
        }
        return false
    }

    fun lookAtBlock(pos: BlockPos) {
        val player = mc.thePlayer ?: return
        val dx = (pos.x + 0.5) - player.posX
        val dy = (pos.y + 0.5) - (player.posY + player.getEyeHeight())
        val dz = (pos.z + 0.5) - player.posZ
        val dist = sqrt(dx * dx + dz * dz)

        val targetYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val targetPitch = (-Math.toDegrees(atan2(dy, dist))).toFloat().coerceIn(-65f, 65f)

        // Smooth rotation interpolation with GCD
        val smoothYaw = interpolateAngle(player.rotationYaw, targetYaw, 0.40f)
        val smoothPitch = interpolateAngle(player.rotationPitch, targetPitch, 0.40f)

        player.rotationYaw = applyGCD(smoothYaw, player.rotationYaw)
        player.rotationPitch = applyGCD(smoothPitch, player.rotationPitch)
    }

    private fun applyGCD(targetAngle: Float, currentAngle: Float): Float {
        val sens = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f
        val gcd = sens * sens * sens * 1.2f
        if (gcd <= 0f) return targetAngle
        val delta = targetAngle - currentAngle
        val fixedDelta = Math.round(delta / gcd) * gcd
        return currentAngle + fixedDelta
    }

    private fun interpolateAngle(from: Float, to: Float, factor: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return from + diff * factor
    }
}
