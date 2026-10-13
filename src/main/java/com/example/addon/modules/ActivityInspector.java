package com.example.addon.modules;

import com.example.addon.AddonTemplate;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActivityInspector extends Module {

    private static final int RENDER_DISTANCE = 8;
    private static final int EARLY_RETURN = 200;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlerts  = settings.createGroup("Alerts");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // ── General ─────────────────────────────────────────────────
    private final Setting<Integer> lightThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("light-threshold")
        .description("Minimum light changes to flag a chunk.")
        .defaultValue(3).min(1).sliderRange(1, 50)
        .build());

    private final Setting<Integer> blockThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("block-threshold")
        .description("Minimum block score to flag a chunk.")
        .defaultValue(15).min(1).sliderRange(1, 100)
        .build());

    private final Setting<Boolean> detectLight = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-light")
        .description("Detect block light changes.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> detectBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-blocks")
        .description("Detect player-placed blocks.")
        .defaultValue(true)
        .build());

    // ── Alerts ──────────────────────────────────────────────────
    private final Setting<Boolean> alertToast = sgAlerts.add(new BoolSetting.Builder()
        .name("toast-on-spotted")
        .description("Show toast when a chunk is flagged.")
        .defaultValue(true)
        .build());

    // ── Render ──────────────────────────────────────────────────
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render a box around flagged chunk.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> boxColor = sgRender.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Box color.")
        .defaultValue(new SettingColor(110, 220, 220, 130))
        .visible(render::get)
        .build());

    private final Setting<Integer> boxY = sgRender.add(new IntSetting.Builder()
        .name("box-y")
        .description("Y level of the box.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 320)
        .visible(render::get)
        .build());

    // ── State ───────────────────────────────────────────────────
    private volatile ChunkPos flaggedChunk = null;
    private volatile int flaggedScore = 0;
    private ChunkPos lastPlayerChunk = null;

    private final Map<Long, LightSnapshot> lightSnapshots = new ConcurrentHashMap<>();

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "activity-inspector");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public ActivityInspector() {
        super(AddonTemplate.CATEGORY, "activity-inspector",
            "Detects player activity via light-change + block anomaly.");
    }

    @Override
    public void onActivate() {
        flaggedChunk = null;
        flaggedScore = 0;
        lastPlayerChunk = null;
        lightSnapshots.clear();
    }

    @Override
    public void onDeactivate() {
        flaggedChunk = null;
        flaggedScore = 0;
        lastPlayerChunk = null;
        lightSnapshots.clear();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent e) { onActivate(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent e) { onActivate(); }

    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (mc.world == null || mc.player == null) return;
        ChunkPos playerChunk = mc.player.getChunkPos();

        ChunkPos current = flaggedChunk;
        if (current != null) {
            int dx = Math.abs(current.x - playerChunk.x);
            int dz = Math.abs(current.z - playerChunk.z);
            if (Math.max(dx, dz) > RENDER_DISTANCE + 2) {
                flaggedChunk = null;
                flaggedScore = 0;
            }
        }

        if (playerChunk.equals(lastPlayerChunk)) return;
        lastPlayerChunk = playerChunk;

        if (!scanning.compareAndSet(false, true)) return;
        scanExecutor.submit(() -> {
            try { scanAround(playerChunk); }
            catch (Throwable ignored) {}
            finally { scanning.set(false); }
        });
    }

    private void scanAround(ChunkPos center) {
        if (mc.world == null) return;
        ChunkPos bestChunk = null;
        int bestScore = 0;

        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                WorldChunk chunk = mc.world.getChunk(cx, cz);
                if (chunk == null) continue;

                int score = 0;
                if (detectBlocks.get()) score += scoreChunkByBlocks(chunk);
                if (detectLight.get()) score += scoreChunkByLight(chunk);

                if (score >= blockThreshold.get() && score > bestScore) {
                    bestScore = score;
                    bestChunk = new ChunkPos(cx, cz);
                }
            }
        }

        if (bestChunk == null) return;
        if (bestScore > flaggedScore) {
            boolean isNew = !bestChunk.equals(flaggedChunk);
            flaggedChunk = bestChunk;
            flaggedScore = bestScore;
            if (isNew) onChunkSpotted(bestChunk, bestScore);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  BLOCK SCORING
    // ═══════════════════════════════════════════════════════════

    private int scoreChunkByBlocks(WorldChunk chunk) {
        ChunkSection[] arr = chunk.getSectionArray();
        int score = 0;
        for (int si = 0; si < arr.length; si++) {
            ChunkSection s = arr[si];
            if (s == null || s.isEmpty()) continue;
            if (!s.hasAny(state -> isInterestingBlock(state.getBlock()))) continue;

            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        Block b = s.getBlockState(x, y, z).getBlock();

                        // ─── Tier 1: 50 ───
                        if (b == Blocks.SPAWNER) {
                            score += 50;
                        }
                        // ─── Tier 2: 10 ───
                        else if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST
                              || b == Blocks.BARREL || b == Blocks.ENDER_CHEST) {
                            score += 10;
                        }
                        // ─── Tier 3: 5 (Redstone ของแพง) ───
                        else if (b == Blocks.REDSTONE_BLOCK
                              || b == Blocks.REDSTONE_TORCH
                              || b == Blocks.REDSTONE_WALL_TORCH) {
                            score += 5;
                        }
                        // ─── Tier 4: 3 (Rails) ───
                        else if (b == Blocks.RAIL
                              || b == Blocks.POWERED_RAIL
                              || b == Blocks.DETECTOR_RAIL
                              || b == Blocks.ACTIVATOR_RAIL) {
                            score += 3;
                        }
                        // ─── Tier 5: 1 (Player blocks ทั่วไป) ───
                        else if (isPlayerBlock(b)) {
                            score += 1;
                        }

                        if (score >= EARLY_RETURN) return score;
                    }
        }
        return score;
    }

    private boolean isInterestingBlock(Block b) {
        return b == Blocks.SPAWNER
            || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST
            || b == Blocks.BARREL || b == Blocks.ENDER_CHEST
            || isPlayerBlock(b);
    }

    /**
     * บล็อกที่ "ผู้เล่นวางได้" — ร่องรอยกิจกรรม
     * รวม: light sources, utility blocks, rails, redstone, containers
     */
    private boolean isPlayerBlock(Block b) {
        return b == Blocks.TORCH || b == Blocks.WALL_TORCH
            || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
            || b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN
            || b == Blocks.SHROOMLIGHT || b == Blocks.OCHRE_FROGLIGHT
            || b == Blocks.VERDANT_FROGLIGHT || b == Blocks.PEARLESCENT_FROGLIGHT
            || b == Blocks.REDSTONE_LAMP || b == Blocks.FURNACE
            || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.CRAFTING_TABLE || b == Blocks.ANVIL
            || b == Blocks.ENCHANTING_TABLE || b == Blocks.BREWING_STAND
            || b == Blocks.CAULDRON || b == Blocks.BEACON
            || b == Blocks.RESPAWN_ANCHOR || b == Blocks.LODESTONE
            || b == Blocks.BELL || b.toString().contains("shulker_box")

            // ─── Rails ────────────────────────────────────────
            || b == Blocks.RAIL
            || b == Blocks.POWERED_RAIL
            || b == Blocks.DETECTOR_RAIL
            || b == Blocks.ACTIVATOR_RAIL

            // ─── Redstone Components ─────────────────────────
            || b == Blocks.REDSTONE_BLOCK
            || b == Blocks.REDSTONE_TORCH
            || b == Blocks.REDSTONE_WALL_TORCH
            || b == Blocks.REDSTONE_WIRE
            || b == Blocks.REPEATER
            || b == Blocks.COMPARATOR
            || b == Blocks.OBSERVER
            || b == Blocks.PISTON
            || b == Blocks.STICKY_PISTON
            || b == Blocks.PISTON_HEAD
            || b == Blocks.MOVING_PISTON
            || b == Blocks.DISPENSER
            || b == Blocks.DROPPER
            || b == Blocks.HOPPER
            || b == Blocks.LEVER
            || b == Blocks.TRIPWIRE
            || b == Blocks.TRIPWIRE_HOOK
            || b == Blocks.TARGET
            || b == Blocks.DAYLIGHT_DETECTOR
            || b == Blocks.SCULK_SENSOR
            || b == Blocks.CALIBRATED_SCULK_SENSOR
            || b == Blocks.LIGHTNING_ROD
            || b == Blocks.NOTE_BLOCK
            || b == Blocks.JUKEBOX
            || b == Blocks.REDSTONE_ORE
            || b == Blocks.DEEPSLATE_REDSTONE_ORE;
    }

    // ═══════════════════════════════════════════════════════════
    //  LIGHT SCORING
    // ═══════════════════════════════════════════════════════════

    private int scoreChunkByLight(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        long key = ChunkPos.toLong(pos.x, pos.z);
        LightSnapshot old = lightSnapshots.get(key);
        LightSnapshot current = snapshotLight(chunk);
        if (old == null) { lightSnapshots.put(key, current); return 0; }

        int changed = 0;
        int max = Math.min(old.blockLight.length, current.blockLight.length);
        for (int i = 0; i < max; i++) {
            byte[] oldArr = old.blockLight[i];
            byte[] newArr = current.blockLight[i];
            if (oldArr == null || newArr == null) continue;
            int len = Math.min(oldArr.length, newArr.length);
            for (int j = 0; j < len; j++) {
                int ov = oldArr[j] & 0xFF;
                int nv = newArr[j] & 0xFF;
                if (nv > ov) changed++;
            }
        }
        lightSnapshots.put(key, current);
        return changed >= lightThreshold.get() ? changed : 0;
    }

    private LightSnapshot snapshotLight(WorldChunk chunk) {
        ChunkSection[] arr = chunk.getSectionArray();
        byte[][] blockLight = new byte[arr.length][];
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int i = 0; i < arr.length; i++) {
            ChunkSection s = arr[i];
            if (s == null) continue;

            byte[] sampled = new byte[16 * 16 * 16];
            int baseY = chunk.sectionIndexToCoord(i) << 4;
            for (int x = 0; x < 16; x += 2)
                for (int y = 0; y < 16; y += 2)
                    for (int z = 0; z < 16; z += 2) {
                        m.set(baseX + x, baseY + y, baseZ + z);
                        int level = mc.world.getLightLevel(LightType.BLOCK, m);
                        int idx = (y * 256) | (z * 16) | x;
                        sampled[idx] = (byte) level;
                    }
            blockLight[i] = sampled;
        }
        return new LightSnapshot(blockLight);
    }

    // ═══════════════════════════════════════════════════════════
    //  ALERT
    // ═══════════════════════════════════════════════════════════

    private void onChunkSpotted(ChunkPos pos, int score) {
        int centerX = (pos.x << 4) + 8;
        int centerZ = (pos.z << 4) + 8;

        if (alertToast.get() && mc.getToastManager() != null) {
            mc.execute(() -> SystemToast.show(
                mc.getToastManager(),
                SystemToast.Type.NARRATOR_TOGGLE,
                Text.literal("Activity Inspector: base detected"),
                Text.literal(centerX + ", " + centerZ + " | score " + score)
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  RENDER — Box at fixed Y
    // ═══════════════════════════════════════════════════════════

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!render.get() || mc.world == null || mc.player == null) return;

        ChunkPos pos = flaggedChunk;
        if (pos == null) return;

        Color c = new Color(boxColor.get());

        double x1 = pos.x * 16.0;
        double z1 = pos.z * 16.0;
        double x2 = x1 + 16.0;
        double z2 = z1 + 16.0;
        double y = boxY.get();

        event.renderer.box(x1, y, z1, x2, y + 0.04, z2, c, c, ShapeMode.Sides, 0);
    }

    @Override
    public String getInfoString() {
        if (flaggedChunk == null) return "searching";
        return "score " + flaggedScore;
    }

    // ═══════════════════════════════════════════════════════════
    //  INNER
    // ═══════════════════════════════════════════════════════════

    private static class LightSnapshot {
        final byte[][] blockLight;
        LightSnapshot(byte[][] blockLight) { this.blockLight = blockLight; }
    }
}