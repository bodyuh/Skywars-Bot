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
    BRIDGING,
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
    private var targetBridgePos: BlockPos? = null
    private var lootStartMs = 0L

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
            lootStartMs = 0L
            ChestLooter.reset()
            CombatEngine.reset()
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

        when (state) {
            BotState.LOBBY -> {
                // Idle in lobby or waiting
            }

            BotState.IN_CAGE -> {
                // Wait for cage to drop
                stopAllMovement()
            }

            BotState.LOOTING_CHESTS -> {
                // Wait until player lands on solid ground after dropping from cage
                if (landingPos == null) {
                    if (player.onGround) {
                        landingPos = BlockPos(player.posX, player.posY, player.posZ)
                        lootStartMs = System.currentTimeMillis()
                        logChat("${EnumChatFormatting.GREEN}Приземлился на остров (Y=${landingPos!!.y}). Ищу сундуки...")
                    } else {
                        // Still in air, do not walk or look around
                        stopAllMovement()
                        return
                    }
                }

                val origin = landingPos ?: BlockPos(player.posX, player.posY, player.posZ)

                // Defensive break-off: if an enemy is right on top of us while looting,
                // stop looting and fight. Kept tight (melee range) so enemies merely
                // standing on their own island don't interrupt the loot run.
                if (mc.currentScreen == null && CombatEngine.findNearestEnemy(5.0) != null) {
                    ChestLooter.targetChestPos = null
                    state = BotState.COMBAT
                    logChat("${EnumChatFormatting.RED}Враг на острове! Прерываю лут, в бой!")
                    return
                }

                // Currently in a chest GUI -> grab valuables
                if (mc.currentScreen != null) {
                    ChestLooter.tickLooting()
                    return
                }

                // Find next chest to loot
                var targetChest = ChestLooter.targetChestPos
                if (targetChest == null) {
                    targetChest = ChestLooter.findNextChest(origin)
                    ChestLooter.targetChestPos = targetChest
                }

                if (targetChest != null) {
                    val isStuck = ChestLooter.tickApproach()
                    if (isStuck) {
                        logChat("${EnumChatFormatting.YELLOW}Сундук заблокирован, пропускаю...")
                        return
                    }

                    val dist = player.getDistance(targetChest.x + 0.5, targetChest.y.toDouble(), targetChest.z + 0.5)
                    ChestLooter.lookAtBlock(targetChest)

                    if (dist > 2.8) {
                        // Walk to chest
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, true)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.keyCode, true)

                        // Auto-jump over 1-block obstacles/slabs on the island floor
                        if (player.isCollidedHorizontally && player.onGround) {
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, true)
                        } else {
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, false)
                        }
                    } else {
                        // In reach: stop and right click to open
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, false)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.keyCode, false)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, false)
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, true)
                        TimeHelper.setTimeout({
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, false)
                        }, 50)
                    }
                } else {
                    // No reachable chest found right now.
                    stopAllMovement()
                    val elapsed = System.currentTimeMillis() - lootStartMs
                    // Leave looting once we've cleared every reachable chest, OR after
                    // the loot budget expires (covers islands with no/late-loading chests).
                    if (ChestLooter.lootedChests.isNotEmpty() || elapsed > lootBudgetMs) {
                        state = BotState.COMBAT
                        logChat("${EnumChatFormatting.GREEN}Лут завершен (${ChestLooter.lootedChests.size} сунд.). Держу остров, ищу врагов в радиусе ${ENGAGE_RANGE.toInt()}м.")
                    }
                    // else: keep standing still while chunks/chests finish loading.
                }
            }

            BotState.BRIDGING -> {
                // Offensive auto-bridging across the void is disabled: it caused the
                // bot to chase players on other islands and walk into the void. Hold
                // the island and fight anyone who comes within range instead.
                SkyBridge.stopBridge()
                targetBridgePos = null
                state = BotState.COMBAT
            }

            BotState.COMBAT -> {
                // Only engage players within ENGAGE_RANGE (10 blocks). Enemies on other
                // islands are ignored entirely, so the bot never aims across the void.
                val nearestEnemy = CombatEngine.findNearestEnemy()
                if (nearestEnemy != null) {
                    CombatEngine.tickCombat()
                } else {
                    // Nobody in range: hold position, release keys, no aiming.
                    stopAllMovement()
                    CombatEngine.currentTarget = null
                }
            }
        }
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
