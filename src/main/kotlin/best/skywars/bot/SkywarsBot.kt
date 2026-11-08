package best.skywars.bot

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.settings.KeyBinding
import net.minecraft.util.BlockPos
import net.minecraft.util.ChatComponentText
import net.minecraft.util.EnumChatFormatting
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.client.registry.ClientRegistry
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.lwjgl.input.Keyboard
import java.util.regex.Pattern

enum class BotState {
    LOBBY,
    IN_CAGE,
    LOOTING_CHESTS,
    PUSHING,
    COMBAT
}

@Mod(
    modid = SkywarsBot.MODID,
    name = SkywarsBot.NAME,
    version = SkywarsBot.VERSION,
    clientSideOnly = true
)
class SkywarsBot {

    companion object {
        const val MODID = "skywarsbot"
        const val NAME = "Skywars Autoplay Bot"
        const val VERSION = "1.0.0"

        val mc: Minecraft = Minecraft.getMinecraft()
        lateinit var toggleKey: KeyBinding

        var isEnabled = true
        var state = BotState.LOBBY
    }

    private var landingPos: BlockPos? = null
    private var midTarget: BlockPos? = null
    private var lootStartMs = 0L
    private var pushStuckMs = 0L
    private var loggedErr = false

    // How long to stay in the looting phase. Replays of won games show the human
    // loots for ~11-15s (opening many chests) BEFORE the first fight, so give the
    // bot a generous budget instead of bailing to combat after ~2s.
    private val lootBudgetMs = 14000L

    // Regex for kill tracking to detect Hypixel Bulldozer strength.
    // Matches real Hypixel SkyWars feed lines, e.g.:
    //   "X was thrown into the void by Y."
    //   "X died in close combat to Y."
    //   "X was shot by Y."
    // but NOT environmental deaths ("X fell into the void." / "X died.").
    private val killPattern = Pattern.compile(
        "(.+?) (?:was (?:killed|shot|thrown into the void|knocked into the void|blasted into the void|stabbed|impaled) by|died in close combat to|died to) ([^.!]+)[.!]"
    )

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        MinecraftForge.EVENT_BUS.register(this)
    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent) {
        toggleKey = KeyBinding("Toggle SkyWars Bot", Keyboard.KEY_R, "SkyWars Bot")
        ClientRegistry.registerKeyBinding(toggleKey)
    }

    private fun logChat(msg: String) {
        mc.thePlayer?.addChatMessage(ChatComponentText("${EnumChatFormatting.GOLD}[SW-Bot] ${EnumChatFormatting.RESET}$msg"))
    }

    @SubscribeEvent
    fun onKeyInput(event: InputEvent.KeyInputEvent) {
        if (toggleKey.isPressed) {
            isEnabled = !isEnabled
            if (isEnabled) {
                logChat("${EnumChatFormatting.GREEN}ВКЛЮЧЕН (Autoplay активен)")
            } else {
                logChat("${EnumChatFormatting.RED}ВЫКЛЮЧЕН")
                stopAllMovement()
                state = BotState.LOBBY
            }
        }
    }

    @SubscribeEvent
    fun onChat(event: ClientChatReceivedEvent) {
        val raw = event.message.unformattedText

        // 1. Detect match start. Real Hypixel SkyWars feed:
        //   countdown -> "The game starts in N seconds!"
        //   start     -> "Cages opened! FIGHT!"
        if (raw.contains("The game starts in", ignoreCase = true) ||
            raw.contains("Cages open in", ignoreCase = true)) {
            if (state != BotState.IN_CAGE) {
                state = BotState.IN_CAGE
                landingPos = null
                ChestLooter.reset()
                CombatEngine.reset()
            }
        }

        if (raw.contains("Cages opened!", ignoreCase = true) ||
            raw.contains("The game has started!", ignoreCase = true)) {
            state = BotState.LOOTING_CHESTS
            landingPos = null
            midTarget = null
            lootStartMs = System.currentTimeMillis()
            loggedErr = false
            ChestLooter.reset()
            CombatEngine.reset()
            SkyPathfinder.reset()
            logChat("${EnumChatFormatting.AQUA}Клетки открылись! Приземляюсь и лутаю остров...")
        }

        // 2. Track Kills for Strength (Bulldozer) perk detection
        val matcher = killPattern.matcher(raw)
        if (matcher.find()) {
            val killerRaw = matcher.group(2).trim()
            CombatEngine.onPlayerKill(killerRaw)
        }

        // 3. Match End -> Auto Requeue.
        // Real win line is "Winner - [VIP+] imnumbagain" (dash, not colon),
        // loss line is "You died! Want to play again?".
        if (raw.contains("Winner", ignoreCase = true) ||
            raw.contains("Reward Summary", ignoreCase = true) ||
            raw.contains("You died!", ignoreCase = true) ||
            raw.contains("Game ends in", ignoreCase = true)) {

            stopAllMovement()
            state = BotState.LOBBY
            logChat("${EnumChatFormatting.YELLOW}Катка завершена. Авто-реквей через 2.5 сек...")

            TimeHelper.setTimeout({
                if (isEnabled) {
                    mc.thePlayer?.sendChatMessage("/play skywars_solo_normal")
                }
            }, 2500)
        }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        if (!isEnabled) return

        try {
        when (state) {
            BotState.LOBBY -> {
                // Idle in lobby or waiting
            }

            BotState.IN_CAGE -> {
                // Wait for cage to drop
                stopAllMovement()
            }

            BotState.LOOTING_CHESTS -> {
                // Don't walk/look while still falling from the cage.
                if (!player.onGround) {
                    stopAllMovement()
                    return
                }

                // Defensive break-off: enemy right on top of us -> fight.
                if (mc.currentScreen == null && CombatEngine.findNearestEnemy(5.0) != null) {
                    ChestLooter.targetChestPos = null
                    stopAllMovement()
                    state = BotState.COMBAT
                    logChat("${EnumChatFormatting.RED}Враг на острове! Прерываю лут, в бой!")
                    return
                }

                // In a chest GUI -> grab valuables.
                if (mc.currentScreen != null) {
                    ChestLooter.tickLooting()
                    return
                }

                // Nearest un-looted chest to the player.
                var targetChest = ChestLooter.targetChestPos
                if (targetChest == null) {
                    targetChest = ChestLooter.findNextChest(BlockPos(player.posX, player.posY, player.posZ))
                    ChestLooter.targetChestPos = targetChest
                }

                if (targetChest != null) {
                    if (ChestLooter.tickApproach()) {
                        logChat("${EnumChatFormatting.YELLOW}Сундук заблокирован, пропускаю...")
                        return
                    }
                    // Turn toward the chest and walk straight to it.
                    ChestLooter.lookAtBlock(targetChest)
                    val dist = player.getDistance(targetChest.x + 0.5, player.posY, targetChest.z + 0.5)
                    if (dist > 2.6) {
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, true)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.keyCode, true)
                        if (player.isCollidedHorizontally && player.onGround) {
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, true)
                        } else {
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, false)
                        }
                    } else {
                        // In reach: stop and right-click to open.
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, false)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.keyCode, false)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, false)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, true)
                        TimeHelper.setTimeout({
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, false)
                        }, 60)
                    }
                } else {
                    // No chest in range right now.
                    stopAllMovement()
                    val elapsed = System.currentTimeMillis() - lootStartMs
                    if (ChestLooter.lootedChests.isNotEmpty() || elapsed > lootBudgetMs) {
                        midTarget = computeMid(player)
                        state = BotState.PUSHING
                        logChat("${EnumChatFormatting.GREEN}Лут завершен (${ChestLooter.lootedChests.size} сунд.). Пушу на мид!")
                    }
                    // else: wait a moment for chests/chunks to finish loading.
                }
            }

            BotState.PUSHING -> {
                // Rush the middle of the map. If an enemy comes within range, fight.
                if (CombatEngine.findNearestEnemy() != null) {
                    SkyBridge.stopPush()
                    state = BotState.COMBAT
                    return
                }
                // Bridge toward the nearest other player — they stand on real islands
                // (a mini island or centre), which is where we want to go. Only fall
                // back to the map centroid if nobody is loaded.
                val target = nearestPlayerPos(player)
                    ?: midTarget ?: computeMid(player).also { midTarget = it }
                val ok = SkyBridge.tickPush(target)
                if (!ok) {
                    // No blocks to scaffold across the void: hold the island.
                    SkyBridge.stopPush()
                    stopAllMovement()
                }
            }

            BotState.COMBAT -> {
                // Only engage players within ENGAGE_RANGE (10 blocks). Enemies on other
                // islands are ignored entirely, so the bot never aims across the void.
                val nearestEnemy = CombatEngine.findNearestEnemy()
                if (nearestEnemy != null) {
                    CombatEngine.tickCombat()
                } else {
                    // Nobody in range: resume pushing toward mid.
                    CombatEngine.releaseCombatKeys()
                    CombatEngine.currentTarget = null
                    state = BotState.PUSHING
                }
            }
        }
        } catch (e: Throwable) {
            // Never leave keys locked if something throws mid-tick.
            stopAllMovement()
            CombatEngine.releaseCombatKeys()
            SkyBridge.stopPush()
            SkyPathfinder.stop()
            if (!loggedErr) {
                logChat("${EnumChatFormatting.RED}Ошибка в тике (клавиши отпущены): ${e.message}")
                loggedErr = true
            }
            e.printStackTrace()
        }
    }

    /** Nearest living other player's position (their island), or null if none loaded. */
    private fun nearestPlayerPos(player: net.minecraft.entity.player.EntityPlayer): BlockPos? {
        val world = mc.theWorld ?: return null
        var best: net.minecraft.entity.player.EntityPlayer? = null
        var bestD = Double.MAX_VALUE
        for (p in world.playerEntities) {
            if (p === player || p !is net.minecraft.entity.player.EntityPlayer) continue
            if (p.isDead || p.health <= 0f) continue
            val d = player.getDistanceSqToEntity(p)
            if (d < bestD) { bestD = d; best = p }
        }
        val b = best ?: return null
        return BlockPos(b.posX, b.posY, b.posZ)
    }

    /** Estimate the map centre as the centroid of all loaded players. */
    private fun computeMid(player: net.minecraft.entity.player.EntityPlayer): BlockPos {
        val world = mc.theWorld
        var sx = 0.0; var sz = 0.0; var n = 0
        if (world != null) {
            for (p in world.playerEntities) { sx += p.posX; sz += p.posZ; n++ }
        }
        return if (n > 0) BlockPos(sx / n, player.posY, sz / n)
        else BlockPos(0.0, player.posY, 0.0)
    }

    private fun stopAllMovement() {
        val gs = mc.gameSettings ?: return
        KeyBinding.setKeyBindState(gs.keyBindForward.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindBack.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindLeft.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindRight.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindJump.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSneak.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindSprint.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindUseItem.keyCode, false)
        KeyBinding.setKeyBindState(gs.keyBindAttack.keyCode, false)
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGameOverlayEvent.Post) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return
        val font = mc.fontRendererObj ?: return

        val statusColor = if (isEnabled) EnumChatFormatting.GREEN else EnumChatFormatting.RED
        val statusText = "$statusColor[SW-BOT: ${if (isEnabled) "ON" else "OFF"}] ${EnumChatFormatting.WHITE}State: $state"
        font.drawStringWithShadow(statusText, 4f, 4f, 0xFFFFFF)

        val target = CombatEngine.currentTarget
        if (target != null) {
            val dist = mc.thePlayer?.getDistanceToEntity(target) ?: 0f
            val targetInfo = "${EnumChatFormatting.YELLOW}Target: ${target.name} (${String.format("%.1f", dist)}m)"
            font.drawStringWithShadow(targetInfo, 4f, 16f, 0xFFFFFF)

            if (CombatEngine.hasStrength(target)) {
                val rem = CombatEngine.getStrengthRemainingSec(target)
                val alert = "${EnumChatFormatting.RED}⚠ ВРАГ ПОД СИЛОЙ (${String.format("%.1f", rem)}s) - ДЕРЖУ ДИСТАНЦИЮ!"
                font.drawStringWithShadow(alert, 4f, 28f, 0xFF0000)
            }
        }
    }
}
