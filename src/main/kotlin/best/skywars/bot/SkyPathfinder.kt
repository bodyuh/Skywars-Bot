package best.skywars.bot

import net.minecraft.block.material.Material
import net.minecraft.client.Minecraft
import net.minecraft.client.settings.KeyBinding
import net.minecraft.util.BlockPos
import net.minecraft.world.World
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tick-driven A* navigator (adapted from familiar/Bedwars-Bot-V3 SimpleAStar).
 * Finds a walkable path on the island and follows it from the main client tick:
 * walking around obstacles and DOWN off a tree/elevation to reach chests.
 *
 * It does NOT bridge across the void — gaps are crossed in PUSHING state via the
 * Vape scaffold (see SkyBridge.tickPush).
 */
object SkyPathfinder {

    enum class Status { FOLLOWING, REACHED, FAILED }

    private val mc = Minecraft.getMinecraft()

    private data class Node(val pos: BlockPos, val parent: Node?, val g: Double, val h: Double) : Comparable<Node> {
        val f get() = g + h
        override fun compareTo(other: Node) = f.compareTo(other.f)
    }

    private var path: List<BlockPos>? = null
    private var index = 0
    private var lastTarget: BlockPos? = null
    private var lastComputeMs = 0L
    private var lastProgressMs = 0L
    private var lastDist = Double.MAX_VALUE
    private var lastJumpMs = 0L

    fun reset() {
        path = null
        index = 0
        lastTarget = null
        lastDist = Double.MAX_VALUE
    }

    /**
     * Navigate toward [target] (a chest or a standing block). Call every tick.
     * Returns REACHED when within reach, FAILED if no path exists.
     */
    fun navigate(target: BlockPos): Status {
        val player = mc.thePlayer ?: return Status.FAILED
        val world = mc.theWorld ?: return Status.FAILED
        val gs = mc.gameSettings ?: return Status.FAILED
        val now = System.currentTimeMillis()

        val flatDist = horizontalDist(player.posX, player.posZ, target.x + 0.5, target.z + 0.5)
        if (flatDist < 2.6 && abs(player.posY - target.y) < 3) return Status.REACHED

        val needRecompute = path == null || target != lastTarget ||
            (now - lastComputeMs > 600 && offPath(player))
        if (needRecompute) {
            val start = BlockPos(player.posX.toInt(), player.posY.toInt() - 1, player.posZ.toInt())
            val p = findPath(world, start, target)
            lastComputeMs = now
            lastProgressMs = now
            if (p == null || p.size < 2) {
                // No ground path (e.g. we're on a tree with only a drop). Fall back to
                // walking straight toward the target so we at least step off the edge.
                return walkStraight(player, gs, target, now)
            }
            path = p
            index = 0
            lastTarget = target
        }

        val pth = path ?: return walkStraight(player, gs, target, now)

        // advance waypoint
        if (index >= pth.size) return Status.REACHED
        var wp = pth[index]
        while (index < pth.size - 1 &&
            horizontalDist(player.posX, player.posZ, wp.x + 0.5, wp.z + 0.5) < 1.3) {
            index++
            wp = pth[index]
        }
        if (index >= pth.size - 1 &&
            horizontalDist(player.posX, player.posZ, wp.x + 0.5, wp.z + 0.5) < 1.3) {
            return Status.REACHED
        }

        // look-ahead waypoint for smoother facing
        val look = pth[minOf(index + 2, pth.size - 1)]
        faceTowards(player, look.x + 0.5, look.z + 0.5)

        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, true)

        // predictive jump: next waypoint higher, or wall ahead, or stuck
        val nextHigher = index + 1 < pth.size && pth[index + 1].y > player.posY.toInt()
        val shouldJump = nextHigher || player.isCollidedHorizontally
        if (shouldJump && player.onGround && now - lastJumpMs > 350) {
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, true)
            lastJumpMs = now
            TimeHelper.setTimeout({ KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false) }, 110)
        }

        // stuck watchdog
        val d = horizontalDist(player.posX, player.posZ, target.x + 0.5, target.z + 0.5)
        if (d < lastDist - 0.25) { lastDist = d; lastProgressMs = now }
        if (now - lastProgressMs > 2500) { reset(); return Status.FAILED }

        return Status.FOLLOWING
    }

    /** Release the movement keys this navigator uses. */
    fun stop() {
        val gs = mc.gameSettings ?: return
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false)
    }

    private fun walkStraight(player: net.minecraft.entity.player.EntityPlayer,
                             gs: net.minecraft.client.settings.GameSettings,
                             target: BlockPos, now: Long): Status {
        faceTowards(player, target.x + 0.5, target.z + 0.5)
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, true)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, true)
        if (player.isCollidedHorizontally && player.onGround && now - lastJumpMs > 350) {
            KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, true)
            lastJumpMs = now
            TimeHelper.setTimeout({ KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false) }, 110)
        }
        val d = horizontalDist(player.posX, player.posZ, target.x + 0.5, target.z + 0.5)
        if (d < lastDist - 0.25) { lastDist = d; lastProgressMs = now }
        if (now - lastProgressMs > 2500) { reset(); return Status.FAILED }
        return Status.FOLLOWING
    }

    private fun offPath(player: net.minecraft.entity.player.EntityPlayer): Boolean {
        val pth = path ?: return true
        if (index >= pth.size) return true
        val wp = pth[index]
        return horizontalDist(player.posX, player.posZ, wp.x + 0.5, wp.z + 0.5) > 3.0
    }

    private fun faceTowards(player: net.minecraft.entity.player.EntityPlayer, x: Double, z: Double) {
        val dx = x - player.posX
        val dz = z - player.posZ
        val targetYaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        var dyaw = targetYaw - player.rotationYaw
        dyaw %= 360f
        if (dyaw < -180f) dyaw += 360f
        if (dyaw > 180f) dyaw -= 360f
        val step = 18f
        if (dyaw > step) dyaw = step
        if (dyaw < -step) dyaw = -step
        player.rotationYaw += dyaw
    }

    private fun horizontalDist(x1: Double, z1: Double, x2: Double, z2: Double): Double {
        val dx = x2 - x1; val dz = z2 - z1
        return sqrt(dx * dx + dz * dz)
    }

    // ---- A* ----
    private fun findPath(world: World, start: BlockPos, end: BlockPos): List<BlockPos>? {
        val open = PriorityQueue<Node>()
        val closed = HashSet<BlockPos>()
        val openMap = HashMap<BlockPos, Node>()
        val startNode = Node(start, null, 0.0, heuristic(start, end))
        open.add(startNode); openMap[start] = startNode
        var iters = 0
        val maxIters = 3000
        while (open.isNotEmpty() && iters < maxIters) {
            iters++
            val cur = open.poll()
            openMap.remove(cur.pos)
            if (cur.pos.distanceSq(end) < 9.0) return reconstruct(cur)
            closed.add(cur.pos)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val n = cur.pos.add(dx, dy, dz)
                if (closed.contains(n)) continue
                if (!isWalkable(world, n)) continue
                val g = cur.g + if (dx != 0 && dz != 0) 1.414 else 1.0
                val ex = openMap[n]
                if (ex != null && g >= ex.g) continue
                val nn = Node(n, cur, g, heuristic(n, end))
                open.add(nn); openMap[n] = nn
            }
        }
        return null
    }

    private fun isWalkable(world: World, pos: BlockPos): Boolean {
        val block = world.getBlockState(pos).block
        val a1 = world.getBlockState(pos.up()).block
        val a2 = world.getBlockState(pos.up(2)).block
        val solidOrAir = block.material.isSolid || block.material == Material.air
        val headroom = !a1.material.isSolid && !a2.material.isSolid
        val safe = block.material != Material.lava && block.material != Material.fire &&
            a1.material != Material.lava && a1.material != Material.fire
        return solidOrAir && headroom && safe
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = abs(a.x - b.x); val dy = abs(a.y - b.y); val dz = abs(a.z - b.z)
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }

    private fun reconstruct(node: Node): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        var c: Node? = node
        while (c != null) { out.add(0, c.pos); c = c.parent }
        return out
    }
}
