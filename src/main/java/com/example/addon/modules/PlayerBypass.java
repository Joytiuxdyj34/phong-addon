package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class PlayerBypass extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
            .name("min-y").defaultValue(-64).range(-64, 320).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
            .name("max-y").defaultValue(80).range(-64, 320).build());

    private final Setting<Integer> minScore = sgGeneral.add(new IntSetting.Builder()
            .name("min-score").defaultValue(8).range(1, 100).build());

    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder()
            .name("scan-range-chunks").defaultValue(16).range(1, 32).build());

    private final Setting<Integer> scanDelay = sgGeneral.add(new IntSetting.Builder()
            .name("scan-delay-ticks").defaultValue(10).range(1, 200).build());

    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-alert").defaultValue(true).build());

    private final Setting<Boolean> soundAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("sound-alert").defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
            .name("color").defaultValue(new SettingColor(255, 210, 25, 180)).build());

    private final Map<Long, Integer> flagged = new LinkedHashMap<>();
    private final Set<Long> scanned = new HashSet<>();
    private Object trackedWorld;
    private int cooldown;

    public PlayerBypass() {
        super(AddonTemplate.CATEGORY, "player-bypass",
              "Highlights chunks with heavy player activity signals");
    }

    @Override
    public void onActivate() { reset(); trackedWorld = mc.world; }
    @Override
    public void onDeactivate() { reset(); }

    private void reset() {
        flagged.clear();
        scanned.clear();
        trackedWorld = null;
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) { reset(); return; }
        if (trackedWorld != mc.world) { reset(); trackedWorld = mc.world; }

        if (--cooldown > 0) return;
        cooldown = scanDelay.get();

        int r = scanRange.get();
        ChunkPos center = mc.player.getChunkPos();

        for (int cx = center.x - r; cx <= center.x + r; cx++) {
            for (int cz = center.z - r; cz <= center.z + r; cz++) {
                long key = ChunkPos.toLong(cx, cz);
                if (!scanned.add(key)) continue;
                if (!mc.world.isChunkLoaded(cx, cz)) continue;
                scanChunk(mc.world.getChunk(cx, cz));
            }
        }

        flagged.keySet().removeIf(k -> !mc.world.isChunkLoaded(
                ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        int score = 0;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty()) continue;
            int sy = (bottom + i) << 4;
            if (sy > maxY.get()) continue;
            if (sy + 15 < minY.get()) continue;

            for (int y = 0; y < 16; y++) {
                int wy = sy + y;
                if (wy < minY.get() || wy > maxY.get()) continue;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        score += weight(s.getBlockState(x, y, z).getBlock());
                    }
                }
            }
        }

        long key = chunk.getPos().toLong();
        if (score >= minScore.get()) {
            if (!flagged.containsKey(key)) alert(chunk.getPos(), score);
            flagged.put(key, score);
        } else {
            flagged.remove(key);
        }
    }

    private static int weight(Block b) {
        if (b == Blocks.ENDER_CHEST) return 12;
        if (b == Blocks.ENCHANTING_TABLE) return 10;
        if (b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL) return 8;
        if (b == Blocks.BREWING_STAND) return 8;
        if (b == Blocks.BEACON) return 15;
        if (b == Blocks.CRAFTING_TABLE) return 6;
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) return 3;
        if (b == Blocks.BARREL || b == Blocks.SHULKER_BOX) return 3;
        if (b == Blocks.HOPPER || b == Blocks.DROPPER || b == Blocks.DISPENSER) return 2;
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) return 2;
        if (b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN) return 2;
        if (b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.SHROOMLIGHT) return 2;
        if (b == Blocks.REDSTONE_LAMP) return 3;
        if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) return 3;
        if (b == Blocks.GLASS || b == Blocks.GLASS_PANE) return 1;
        if (b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS
            || b == Blocks.BIRCH_PLANKS || b == Blocks.DARK_OAK_PLANKS
            || b == Blocks.JUNGLE_PLANKS || b == Blocks.ACACIA_PLANKS) return 1;
        if (b == Blocks.STONE_BRICKS || b == Blocks.BRICKS) return 1;
        if (b == Blocks.OAK_DOOR || b == Blocks.SPRUCE_DOOR || b == Blocks.IRON_DOOR) return 3;
        if (b == Blocks.RED_BED || b == Blocks.WHITE_BED) return 4;
        if (b == Blocks.REDSTONE_BLOCK || b == Blocks.REDSTONE_TORCH) return 2;
        if (b == Blocks.LEVER || b == Blocks.REPEATER || b == Blocks.COMPARATOR) return 2;
        return 0;
    }

    private void alert(ChunkPos pos, int score) {
        if (chatAlert.get() && mc.player != null) {
            mc.player.sendMessage(Text.literal(
                "§e[PlayerBypass] §fActivity §e" + pos.getCenterX() + ", " + pos.getCenterZ()
                + " §7[score:" + score + "]"
            ), false);
        }
        if (soundAlert.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (flagged.isEmpty()) return;
        SettingColor c = color.get();
        long now = System.currentTimeMillis();
        for (long key : flagged.keySet()) {
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            double x = cx << 4, z = cz << 4;
            double y = 64.25 + Math.sin((double)(now + key) / 420.0) * 0.12;
            event.renderer.box(x, y, z, x + 16, y + 0.16, z + 16,
                    c, c, ShapeMode.Both, 0);
        }
    }
}