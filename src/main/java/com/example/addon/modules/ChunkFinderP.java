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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class ChunkFinderP extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
            .name("min-y").defaultValue(-64).range(-64, 320).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
            .name("max-y").defaultValue(60).range(-64, 320).build());

    private final Setting<Integer> minScore = sgGeneral.add(new IntSetting.Builder()
            .name("min-score").defaultValue(12).range(1, 100).build());

    private final Setting<Integer> minAirRatio = sgGeneral.add(new IntSetting.Builder()
            .name("min-air-per-1000").defaultValue(30).range(0, 500).build());

    private final Setting<Boolean> requireLight = sgGeneral.add(new BoolSetting.Builder()
            .name("require-light").defaultValue(true).build());

    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder()
            .name("scan-range-chunks").defaultValue(16).range(1, 32).build());

    private final Setting<Integer> scanDelay = sgGeneral.add(new IntSetting.Builder()
            .name("scan-delay-ticks").defaultValue(10).range(1, 200).build());

    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-alert").defaultValue(true).build());

    private final Setting<Boolean> soundAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("sound-alert").defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("render").defaultValue(true).build());

    private final Setting<Double> plateY = sgRender.add(new DoubleSetting.Builder()
            .name("plate-y").defaultValue(63.0).range(-64, 320).sliderRange(-64, 320).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
            .name("color").defaultValue(new SettingColor(0, 255, 70, 90)).build());

    private final Map<Long, Integer> flagged = new LinkedHashMap<>();
    private final Set<Long> scanned = new HashSet<>();
    private Object trackedWorld;
    private int cooldown;

    public ChunkFinder() {
        super(AddonTemplate.CATEGORY, "chunk-finder",
              "Hunts underground bases via light sources, blocks and air pockets");
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
        int lights = 0;
        int airBlocks = 0;
        int totalBlocks = 0;

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
                        BlockState st = s.getBlockState(x, y, z);
                        Block b = st.getBlock();
                        totalBlocks++;

                        if (st.isAir()) { airBlocks++; continue; }

                        int w = weight(b);
                        if (w > 0) {
                            score += w;
                            if (isPlayerLight(b)) lights++;
                        }
                    }
                }
            }
        }

        int airPer1000 = totalBlocks > 0 ? (airBlocks * 1000 / totalBlocks) : 0;

        boolean flagLight = !requireLight.get() || lights >= 2;
        boolean flagAir   = airPer1000 >= minAirRatio.get();
        boolean flagScore = score >= minScore.get();

        boolean flag = (flagScore && flagAir && flagLight)
                    || (flagScore && lights >= 4);

        long key = chunk.getPos().toLong();
        if (flag) {
            if (!flagged.containsKey(key)) {
                alert(chunk.getPos(), score, lights, airPer1000);
            }
            flagged.put(key, score);
        } else {
            flagged.remove(key);
        }
    }

    private static int weight(Block b) {
        if (b == Blocks.CRAFTING_TABLE) return 10;
        if (b == Blocks.ENCHANTING_TABLE) return 15;
        if (b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL) return 8;
        if (b == Blocks.BREWING_STAND) return 8;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return 5;
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) return 4;
        if (b == Blocks.BARREL) return 4;
        if (b == Blocks.ENDER_CHEST) return 6;
        if (b == Blocks.SHULKER_BOX) return 5;
        if (b == Blocks.HOPPER || b == Blocks.DROPPER || b == Blocks.DISPENSER) return 3;
        if (b == Blocks.OBSIDIAN) return 2;
        if (isPlayerLight(b)) return 3;
        if (isPlayerBuilding(b)) return 1;
        return 0;
    }

    private static boolean isPlayerLight(Block b) {
        return b == Blocks.TORCH || b == Blocks.WALL_TORCH
            || b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH
            || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
            || b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN
            || b == Blocks.SHROOMLIGHT || b == Blocks.REDSTONE_LAMP
            || b == Blocks.OCHRE_FROGLIGHT || b == Blocks.VERDANT_FROGLIGHT
            || b == Blocks.PEARLESCENT_FROGLIGHT || b == Blocks.END_ROD
            || b == Blocks.JACK_O_LANTERN;
    }

    private static boolean isPlayerBuilding(Block b) {
        return b == Blocks.GLASS || b == Blocks.GLASS_PANE
            || b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS
            || b == Blocks.BIRCH_PLANKS || b == Blocks.JUNGLE_PLANKS
            || b == Blocks.ACACIA_PLANKS || b == Blocks.DARK_OAK_PLANKS
            || b == Blocks.OAK_DOOR || b == Blocks.SPRUCE_DOOR
            || b == Blocks.RED_BED || b == Blocks.WHITE_BED
            || b == Blocks.STONE_BRICKS || b == Blocks.BRICKS
            || b == Blocks.BOOKSHELF;
    }

    private void alert(ChunkPos pos, int score, int lights, int airPer1000) {
        if (chatAlert.get() && mc.player != null) {
            mc.player.sendMessage(Text.literal(
                "§a[ChunkFinder] §fBase §e" + pos.getCenterX() + ", " + pos.getCenterZ()
                + " §7[score:" + score + " lights:" + lights + " air:" + airPer1000 + "/1000]"
            ), false);
        }
        if (soundAlert.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 1f);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || flagged.isEmpty()) return;
        SettingColor c = color.get();
        double y = plateY.get();
        for (long key : flagged.keySet()) {
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            double x = cx << 4, z = cz << 4;
            event.renderer.box(x, y, z, x + 16, y + 0.15, z + 16,
                    c, c, ShapeMode.Both, 0);
        }
    }
}