package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class SusChunk extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
            .name("sensitivity").defaultValue(6).range(1, 40).build());

    private final Setting<Boolean> detectBeehives = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-beehives").defaultValue(true).build());

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

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
            .name("color").defaultValue(new SettingColor(255, 60, 60, 90)).build());

    private final Set<Long> flagged = new LinkedHashSet<>();
    private final Set<Long> scanned = new HashSet<>();
    private Object trackedWorld;
    private int cooldown;

    public SusChunk() {
        super(AddonTemplate.CATEGORY, "sus-chunk",
              "Detects suspicious chunks from amethyst geodes and full beehives");
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

        flagged.removeIf(k -> !mc.world.isChunkLoaded(
                ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k)));
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int amethyst = 0;
        int fullBeehives = 0;

        for (ChunkSection s : sections) {
            if (s == null || s.isEmpty()) continue;
            if (!s.getBlockStateContainer().hasAny(st ->
                    isAmethystSignal(st.getBlock()) || isHive(st.getBlock()))) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = s.getBlockState(x, y, z);
                        if (isAmethystSignal(st.getBlock())) amethyst++;
                        else if (detectBeehives.get() && isHive(st.getBlock())) {
                            int level = st.getOrEmpty(BeehiveBlock.HONEY_LEVEL).orElse(0);
                            if (level >= 5) fullBeehives++;
                        }
                    }
                }
            }
        }

        boolean flag = amethyst >= sensitivity.get()
                || (detectBeehives.get() && fullBeehives >= 2);

        if (flag && flagged.add(chunk.getPos().toLong())) {
            alert(chunk.getPos(), amethyst, fullBeehives);
        }
    }

    private static boolean isAmethystSignal(Block b) {
        return b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST
            || b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD
            || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.AMETHYST_CLUSTER
            || b == Blocks.CALCITE || b == Blocks.SMOOTH_BASALT;
    }

    private static boolean isHive(Block b) {
        return b == Blocks.BEEHIVE || b == Blocks.BEE_NEST;
    }

    private void alert(ChunkPos pos, int amethyst, int hives) {
        if (chatAlert.get() && mc.player != null) {
            mc.player.sendMessage(Text.literal(
                "§c[SusChunk] §fFlagged §e" + pos.getCenterX() + ", " + pos.getCenterZ()
                + " §7[amethyst:" + amethyst + " hives:" + hives + "]"
            ), false);
        }
        if (soundAlert.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || flagged.isEmpty()) return;
        SettingColor c = color.get();
        for (long key : flagged) {
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            double x = cx << 4, z = cz << 4;
            event.renderer.box(x, 63, z, x + 16, 63.15, z + 16,
                    c, c, ShapeMode.Both, 0);
        }
    }
}